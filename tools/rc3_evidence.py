#!/usr/bin/env python3
"""RC3 双源取证入口：只读 REA 与 MT，输出限制为简短摘要。"""

import argparse
import json

import b4_analysis as analysis
from rc3_retarget import CACHE, OUT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("rea", "mt", "inspect"))
    parser.add_argument("label")
    parser.add_argument("args", nargs="*")
    options = parser.parse_args()
    analysis.APK = CACHE / "RM2.3.3.RC3.APK"
    analysis.OUT = OUT
    if options.mode == "rea":
        analysis.rea(options.label, options.args)
    elif options.mode == "mt":
        analysis.mt(options.label, options.args[0], "edzdz22y", "rc3")
    else:
        data = json.loads((OUT / (options.label + ".json")).read_text(encoding="utf-8"))
        text = json.dumps(data, ensure_ascii=False, indent=2)
        needle = options.args[0] if options.args else ""
        if needle:
            pos = text.find(needle)
            print(text[max(0, pos - 100):pos + 7000])
        else:
            print(text[:7000])


if __name__ == "__main__":
    main()
