#!/usr/bin/env python3
"""仅对本轮测试书执行真实界面验收，保存文本证据，不载入截图。"""

import argparse
import base64
import hashlib
import json
import re
import time

import rc3_audit_ui
import b4_device as device
from rc3_full_audit import OUT


def tree(label, package="app.zhendong.reamicro"):
    device.PACKAGE = package
    data, root = device.ui()
    with (OUT / (label + ".xml")).open("xb") as stream:
        stream.write(data)
    return root


def click(root, text=None, bounds=None, long=False):
    parents = {child: parent for parent in root.iter() for child in parent}
    key = "long-clickable" if long else "clickable"
    nodes = []
    for node in root.iter("node"):
        match = node.get("bounds") == bounds if bounds else (
            node.get("text") == text or node.get("content-desc") == text)
        if not match:
            continue
        while node.get(key) != "true" and node in parents:
            node = parents[node]
        if node.get(key) == "true" and node.get("enabled") == "true" and node not in nodes:
            nodes.append(node)
    if len(nodes) != 1:
        raise RuntimeError("实时目标不唯一: " + str((text, bounds, len(nodes))))
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[0].get("bounds")))
    device.foreground()
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    if long:
        device.adb("shell", "input", "swipe", x, y, x, y, "750")
    else:
        device.adb("shell", "input", "tap", x, y)
    time.sleep(.5)


def set_text(value):
    device.foreground()
    encoded = base64.b64encode(value.encode("utf-8")).decode("ascii")
    result = device.adb(
        "shell", "uiautomator", "runtest", "/data/local/tmp/reamicro-input.jar",
        "-c", "UiSetText", "-e", "text64", encoded,
    ).decode("utf-8", errors="replace")
    if "INSTRUMENTATION_STATUS_CODE: 0" not in result:
        raise RuntimeError(result)


def fixture_state(label):
    data = device.adb(
        "exec-out", "su", "-c",
        "CLASSPATH=/data/local/tmp/reamicro-full-audit-third-probe/probe.jar "
        "app_process /system/bin Rc3FeatureProbe --fixture-state",
    )
    rows = json.loads(data)
    for row in rows:
        if row["containerExists"]:
            path = "/data/user/0/app.zhendong.reamicro/files/" + str(row["uid"]) + "/books/" + row["uuid"]
            row["opfSha256"] = hashlib.sha256(device.adb("exec-out", "su", "-c",
                "cat " + path + "/OEBPS/content.opf")).hexdigest()
            row["contentSha256"] = hashlib.sha256(device.adb("exec-out", "su", "-c",
                "cat " + path + "/OEBPS/one.xhtml")).hexdigest()
    with (OUT / (label + ".json")).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(rows, stream, ensure_ascii=False, indent=2)
    print(json.dumps(rows, ensure_ascii=False))
    return rows


def duplicate(label, decision):
    before = fixture_state(label + "-before")
    root = tree(label + "-shelf")
    assert any(n.get("text") == "书架" for n in root.iter("node"))
    click(root, bounds="[871,97][1015,241]")
    click(tree(label + "-sheet"), "本地存储")
    picker = "com.google.android.documentsui"
    root = tree(label + "-picker", picker)
    click(root, "搜索")
    device.foreground()
    device.adb("shell", "input", "text", "RC3-Audit-Import")
    device.adb("shell", "input", "keyevent", "66")
    click(tree(label + "-results", picker), "RC3-Audit-Import.epub")
    click(tree(label + "-decision"), decision)
    time.sleep(3)
    after = fixture_state(label + "-after")
    print("Row count", len(before), "->", len(after))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("duplicate", "state"))
    parser.add_argument("label")
    parser.add_argument("--decision", choices=("取消导入", "独立导入", "覆盖导入"))
    args = parser.parse_args()
    assert re.fullmatch(r"[a-z0-9-]+", args.label)
    if args.mode == "duplicate":
        assert args.decision
        duplicate(args.label, args.decision)
    else:
        fixture_state(args.label)
