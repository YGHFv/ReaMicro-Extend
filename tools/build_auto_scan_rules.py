#!/usr/bin/env python3
"""生成真实 DexKit 定位规则；不把人工 profile 的短名写入扫描目标。"""

from collections import defaultdict
import argparse
import json
from pathlib import Path
import re
from zipfile import ZipFile

from androguard.core.dex import DEX
from loguru import logger

import hostsym
import retarget_profile as rp
from rc3_retarget import ROOT, CACHE, OUT

logger.remove()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, default=ROOT / "app/src/main/assets/host-profiles/2.3.3-rc3.profile")
    parser.add_argument("--apk", type=Path, default=CACHE / "RM2.3.3.RC3.APK")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/host-profiles/automatic.rules.json")
    parser.add_argument("--report-dir", type=Path, default=OUT)
    args = parser.parse_args()
    profile = rp.parse_profile(args.profile)
    profile_id = next(line.partition("=")[2] for line in profile["header"] if line.startswith("profile="))
    raw = {}
    strings_index = defaultdict(set)
    with ZipFile(args.apk) as archive:
        for entry in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            for cls in DEX(archive.read(entry)).get_classes():
                name = cls.name[1:-1].replace("/", ".")
                methods = []
                for method in cls.get_methods():
                    strings, opcodes = [], []
                    for i in method.get_instructions():
                        opcodes.append(i.get_op_value())
                        if i.get_name().startswith("const-string"):
                            # 常量使用 DEX 解码后的值，不把 Androguard 的展示引号写入规则。
                            strings.append(i.get_string())
                    methods.append({
                        "name": method.get_name(), "signature": method.get_descriptor().replace(" ", ""),
                        "static": bool(method.get_access_flags() & 8),
                        "strings": sorted(set(strings)), "opcodes": opcodes,
                    })
                    for string in strings:
                        strings_index[string].add(name)
                raw[name] = methods
    all_classes = profile["classes"] | profile["typeAliases"]
    class_rules = []
    for logical, actual in all_classes.items():
        if "." in actual and not actual.startswith("ReaMicro"):
            class_rules.append({"id": logical, "stable": actual})
            continue
        unique = sorted({s for m in raw[actual] for s in m["strings"]
                         if len(s) >= 5 and len(strings_index[s]) == 1}, key=lambda s: (len(s), s))
        if unique:
            class_rules.append({"id": logical, "strings": unique[:2]})
    known = {r["id"] for r in class_rules}
    # 无文本类型通过已定位宿主方法的精确 opcode 与参数槽位关联，不猜同形接口。
    for _ in range(5):
        newly = []
        for logical, actual in all_classes.items():
            if logical in known:
                continue
            for owner, owner_actual in all_classes.items():
                if owner not in known:
                    continue
                candidates = []
                for method in raw[owner_actual]:
                    tokens = hostsym.parse_params(method["signature"]) + [hostsym.return_type(method["signature"])]
                    for index, token in enumerate(tokens):
                        if token == "L" + actual.replace(".", "/") + ";" and method["opcodes"]:
                            candidates.append((len(method["opcodes"]), method, index if index < len(tokens) - 1 else -1))
                if candidates:
                    _, method, index = min(candidates, key=lambda v: v[0])
                    newly.append({"id": logical, "relation": {
                        "owner": owner, "signature": method["signature"], "static": method["static"],
                        "opcodes": method["opcodes"], "strings": method["strings"], "index": index,
                        **({"stable": method["name"]} if method["name"].startswith("<") else {}),
                    }})
                    break
        class_rules.extend(newly)
        known.update(r["id"] for r in newly)
    method_rules = []
    skipped = []
    for key, actual in profile["methods"].items():
        logical = key.partition("#")[0]
        owner = profile["methodOwners"].get(key) or all_classes.get(logical)
        methods = [m for m in raw.get(owner, []) if m["name"] == actual]
        if logical not in known or len(methods) != 1:
            skipped.append(key)
            continue
        method = methods[0]
        anchors = method["strings"]
        if not anchors and not method["opcodes"]:
            skipped.append(key)
            continue
        method_rules.append({
            "id": key, "owner": logical, "signature": method["signature"],
            "static": method["static"], "strings": anchors, "opcodes": method["opcodes"],
        })
    result = {"version": 1, "reference": profile_id, "classes": class_rules,
              "methods": method_rules, "fields": []}
    path = args.output
    path.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    args.report_dir.mkdir(parents=True, exist_ok=True)
    (args.report_dir / "automatic-rule-coverage.json").write_text(json.dumps({
        "classes": len(class_rules), "methods": len(method_rules),
        "notYetCoveredClasses": sorted(set(all_classes) - known), "notYetCoveredMethods": skipped,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print("rules", len(class_rules), len(method_rules), "bytes", path.stat().st_size)


if __name__ == "__main__":
    main()
