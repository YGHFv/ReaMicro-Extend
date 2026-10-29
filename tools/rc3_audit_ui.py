#!/usr/bin/env python3
"""仅使用实时界面树操作阅微；不读取截图，不对 MT 或锁屏输入触摸。"""

import argparse
import json
import os
import re
import time

os.environ["B4_ADB_SERIAL"] = "deb497bd"
from b4_device import adb, foreground, ui
from rc3_full_audit import OUT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("tree", "click", "bounds", "back", "swipe", "long"))
    parser.add_argument("label")
    parser.add_argument("target", nargs="?")
    parser.add_argument("--picker", action="store_true")
    args = parser.parse_args()
    if args.picker:
        import b4_device
        b4_device.PACKAGE = "com.google.android.documentsui"
    foreground()
    OUT.mkdir(parents=True, exist_ok=True)
    data, root = ui()
    path = OUT / (args.label + ".xml")
    with path.open("xb") as handle:
        handle.write(data)
    if args.mode == "tree":
        for node in root.iter("node"):
            text = node.get("text") or node.get("content-desc")
            text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]+", "<email>", text or "")
            if text or node.get("clickable") == "true":
                print(node.get("bounds"), text or "<button>", node.get("class"),
                      "checked=" + node.get("checked", ""))
        return
    if args.mode == "back":
        foreground()
        adb("shell", "input", "keyevent", "4")
        return
    if args.mode == "swipe":
        values = args.target.split(",")
        assert len(values) == 5 and all(value.isdigit() for value in values)
        foreground()
        adb("shell", "input", "swipe", *values)
        return
    parents = {child: parent for parent in root.iter() for child in parent}
    key = "long-clickable" if args.mode == "long" else "clickable"
    matches = []
    for node in root.iter("node"):
        if args.mode == "bounds":
            match = node.get("bounds") == args.target
        else:
            match = node.get("text") == args.target or node.get("content-desc") == args.target
        if not match:
            continue
        while node.get(key) != "true" and node in parents:
            node = parents[node]
        if node.get(key) == "true" and node.get("enabled") == "true" and node not in matches:
            matches.append(node)
    if len(matches) != 1:
        raise RuntimeError("实时目标不唯一: " + str(len(matches)))
    node = matches[0]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    x, y = str((x1+x2)//2), str((y1+y2)//2)
    foreground()
    if args.mode == "long":
        adb("shell", "input", "swipe", x, y, x, y, "750")
    else:
        adb("shell", "input", "tap", x, y)
    print("Clicked", args.target, node.get("bounds"))
    time.sleep(.5)


if __name__ == "__main__":
    main()
