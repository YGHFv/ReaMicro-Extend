#!/usr/bin/env python3
"""固定 B4 验收设备；仅操作阅微前台，并保存本轮 UI、日志和截图证据。"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

# 设备可用环境变量 B4_ADB_SERIAL 覆盖；默认指向当前调试机 K50U（Redmi 22081212C）。
SERIAL = os.environ.get("B4_ADB_SERIAL", "adb-deb497bd-ECI5X6._adb-tls-connect._tcp")
PACKAGE = "app.zhendong.reamicro"
OUT = Path(os.environ["TEMP"]) / "reamicro-analysis/runtime/b4/round2"
UI = "/data/local/tmp/reamicro-b4-ui.xml"


def adb(*args):
    return subprocess.check_output(["adb", "-s", SERIAL, *args])


def foreground():
    text = adb("shell", "dumpsys", "activity", "activities").decode("utf-8", errors="replace")
    lines = [
        line for line in text.splitlines()
        if re.search(r"topResumedActivity=|topResumedActivity:|mResumedActivity:", line, re.I)
    ]
    if not any(PACKAGE + "/" in line for line in lines):
        raise SystemExit("当前前台不是阅微，拒绝触摸或界面抓取，保护 MT 与其他应用。")


def ui():
    for attempt in range(3):
        foreground()
        # dump 失败也可能返回 0；每次用唯一文件，禁止把旧 XML 当成当前界面。
        path = UI.removesuffix(".xml") + "-" + uuid.uuid4().hex + ".xml"
        result = adb("shell", "uiautomator", "dump", path)
        if path.encode("utf-8") in result:
            data = adb("shell", "cat", path)
            return data, ET.fromstring(data)
        if attempt < 2:
            time.sleep(1)
    raise RuntimeError("连续三次无法获取新 UI 树，拒绝读取旧文件或点击旧坐标。")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("ui", "logs", "screen", "click", "back"))
    parser.add_argument("label", nargs="?", default="current")
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.mode == "logs":
        pid = adb("shell", "pidof", PACKAGE).decode("utf-8").strip()
        if not pid:
            raise SystemExit("阅微进程不在运行；不自动启动其他应用。")
        data = adb("logcat", "-d", "-v", "threadtime", "--pid=" + pid.split()[0])
        (OUT / f"{args.label}.log").write_bytes(data)
        paths = adb("shell", "su", "-c", "ls -t /data/adb/lspd/log/verbose_*.log").decode("utf-8").splitlines()
        if paths:
            path = paths[0].strip()
            if not re.fullmatch(r"/data/adb/lspd/log/verbose_[\w.:-]+\.log", path):
                raise SystemExit("拒绝读取意外的 LSPosed 日志路径：" + path)
            lsp = adb("shell", "su", "-c", "cat " + path)
            (OUT / f"{args.label}.lsp.log").write_bytes(lsp)
            data += lsp
        for line in data.decode("utf-8", errors="replace").splitlines():
            if re.search(r"ReaMicro symbols package|hook installed|native coroutine protocol|FATAL EXCEPTION|ReaMicro.*failed", line):
                print(line)
        return
    foreground()
    if args.mode == "back":
        adb("shell", "input", "keyevent", "4")
    elif args.mode == "screen":
        path = OUT / f"{args.label}.png"
        path.write_bytes(adb("exec-out", "screencap", "-p"))
        print(path)
    else:
        data, root = ui()
        if args.mode == "ui":
            (OUT / f"{args.label}.xml").write_bytes(data)
            for node in root.iter("node"):
                if node.get("text") or node.get("content-desc"):
                    print(node.get("bounds"), node.get("text") or node.get("content-desc"))
            return
        matches = [
            node for node in root.iter("node")
            if node.get("text") == args.label or node.get("content-desc") == args.label
        ]
        parents = {child: parent for parent in root.iter() for child in parent}
        targets = []
        for node in matches:
            while node.get("clickable") != "true" and node in parents:
                node = parents[node]
            if node not in targets:
                targets.append(node)
        # 同一按钮的图标描述和文字可能重复，按实际可点击父节点去重。
        if len(targets) != 1:
            raise SystemExit("没有唯一的实时点击目标，拒绝旧坐标：" + args.label)
        node = targets[0]
        if node.get("clickable") != "true" or node.get("enabled") != "true":
            raise SystemExit("目标不是可用按钮：" + args.label)
        numbers = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(numbers) != 4:
            raise SystemExit("目标无有效边界")
        foreground()
        adb("shell", "input", "tap", str((numbers[0] + numbers[2]) // 2), str((numbers[1] + numbers[3]) // 2))


if __name__ == "__main__":
    main()
