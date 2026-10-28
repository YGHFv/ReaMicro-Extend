#!/usr/bin/env python3
"""保存当前进程日志，仅打印摘要，避免历史堆栈进入上下文。"""

import argparse
from collections import Counter
import re
import subprocess
from rc3_retarget import OUT
from b4_runtime_log import pid_lines


def adb(*args):
    return subprocess.check_output(["adb", "-s", "deb497bd", *args])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    options = parser.parse_args()
    assert re.fullmatch(r"[a-z0-9-]+", options.label)
    pid = adb("shell", "pidof", "app.zhendong.reamicro").decode().strip().split()[0]
    paths = adb("shell", "su", "-c", "ls -t /data/adb/lspd/log/modules_*.log").decode().splitlines()
    path = paths[0]
    assert re.fullmatch(r"/data/adb/lspd/log/modules_[\w.:-]+\.log", path)
    data = adb("exec-out", "su", "-c", "cat " + path).decode("utf-8", errors="replace")
    lines = pid_lines(data, pid)
    (OUT / (options.label + "-modules.log")).write_text("\n".join(lines) + "\n", encoding="utf-8")
    logcat = adb("logcat", "-d", "-v", "threadtime", "--pid=" + pid)
    (OUT / (options.label + "-logcat.log")).write_bytes(logcat)
    print("PID", pid, "module lines", len(lines))
    for line in lines:
        if any(key in line for key in ("symbols package=", "hook installed", "DexKit 扫描", "symbols audit")):
            print(line.split("] ")[-1][:900])
    errors = Counter()
    for line in lines:
        if re.search("failed|Exception|Error|not found", line, re.I) and not line.startswith("\tat "):
            errors[line.split("] ")[-1].strip()] += 1
    for text, count in errors.most_common(35):
        print(count, text[:750])
    fatals = [line for line in logcat.decode("utf-8", errors="replace").splitlines() if "FATAL EXCEPTION" in line]
    print("current PID fatal count", len(fatals))
    print("MT", adb("shell", "pidof", "bin.mt.plus").decode().strip(),
          "uptime", adb("shell", "cat", "/proc/uptime").decode().strip())


if __name__ == "__main__":
    main()
