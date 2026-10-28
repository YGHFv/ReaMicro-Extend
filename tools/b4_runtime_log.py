#!/usr/bin/env python3
"""读取当前阅微 PID 的模块日志；不依赖容易被系统噪声挤掉的 verbose 日志。"""

import argparse
import os
from pathlib import Path
import re

from b4_device import adb, PACKAGE


def pid_lines(text, pid):
    selected = False
    lines = []
    for line in text.splitlines():
        if re.match(r"^\[\s*\d{4}-", line):
            selected = re.search(r":\s*" + re.escape(pid) + r":", line) is not None
        # 异常续行没有 PID；仍属上一条日志，必须保留堆栈和 Caused by。
        if selected:
            lines.append(line)
    return lines


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    assert re.fullmatch(r"[a-zA-Z0-9_-]+", args.label)
    pid = adb("shell", "pidof", PACKAGE).decode("utf-8").strip().split()[0]
    paths = adb("shell", "su", "-c", "ls -t /data/adb/lspd/log/modules_*.log").decode("utf-8").splitlines()
    path = paths[0]
    assert re.fullmatch(r"/data/adb/lspd/log/modules_[\w.:-]+\.log", path)
    data = adb("exec-out", "su", "-c", "cat " + path)
    lines = pid_lines(data.decode("utf-8", errors="replace"), pid)
    out = Path(os.environ["TEMP"]) / "reamicro-analysis/runtime/b4/round3"
    out.mkdir(parents=True, exist_ok=True)
    with (out / f"{args.label}-pid{pid}.log").open("x", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    print("PID", pid, "module log lines", len(lines))
    for line in lines:
        if args.all or re.search(r"symbols package|hook installed|native list|B4 |barcode|callback|render|FATAL", line):
            print(line)


if __name__ == "__main__":
    main()
