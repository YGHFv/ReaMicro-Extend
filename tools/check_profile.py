#!/usr/bin/env python3
"""host profile 静态一致性检查：目标冲突、悬空引用、指纹自相矛盾。

为什么必须有这一步：profile 是「一个逻辑名 -> 一个混淆名」的表，
R8 允许把多个逻辑类合并到同一个目标，但我们的 hook 语义要求一对一。
两个逻辑类指向同一个短名，至少有一个是错的，而且错的那条会静默改变行为
（比如把书架浮层的 hook 装到文件源枚举上）。
"""

from __future__ import annotations

import argparse
import collections
import json
import re
from pathlib import Path

import hostsym as hs

MARKER_ONLY = re.compile(r"^[\w.$]+\$\w*(lambda\$|Ref|__|Kt\$$)")


def ancestors(features: dict[str, dict], name: str) -> list[str]:
    # 两侧使用相同的继承规则，接口也参与查找，并阻断循环元数据。
    chain: list[str] = []
    pending = [name]
    seen: set[str] = set()
    while pending:
        current = pending.pop(0)
        if current in seen or current not in features:
            continue
        seen.add(current)
        chain.append(current)
        entry = features[current]
        parents = [entry.get("rawSup", "")] + list(entry.get("rawIfc", []))
        pending.extend(parent[1:-1].replace("/", ".") for parent in parents if parent.startswith("L"))
    return chain


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", type=Path)
    parser.add_argument("--obfuscated", type=Path, help="混淆侧特征库，用于校验目标真实存在")
    parser.add_argument("--reference", type=Path, help="未混淆侧特征库，用于校验源真实存在")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()

    classes: dict[str, str] = {}
    aliases: dict[str, str] = {}
    method_owners: dict[str, str] = {}
    methods: dict[str, str] = {}
    fields: dict[str, str] = {}
    required: list[str] = []
    absent: list[str] = []
    for raw in args.profile.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("class="):
            logical, _sep, actual = line[6:].rpartition("=")
            classes[logical] = actual
        elif line.startswith("typeAlias="):
            logical, _sep, actual = line[10:].rpartition("=")
            aliases[logical] = actual
        elif line.startswith("methodOwner="):
            key, _sep, actual = line[12:].rpartition("=")
            method_owners[key] = actual
        elif line.startswith("method="):
            key, _sep, actual = line[7:].rpartition("=")
            methods[key] = actual
        elif line.startswith("field="):
            key, _sep, actual = line[6:].rpartition("=")
            fields[key] = actual
        elif line.startswith("requiredClass="):
            required.append(line[14:])
        elif line.startswith("absentClass="):
            absent.append(line[12:])

    problems: list[str] = []
    for key, actual in methods.items():
        if actual in ("<init>", "<clinit>") or key.split("#", 1)[-1] in ("<init>", "<clinit>"):
            problems.append(f"constructor used as method: {key} -> {actual}")

    inverse: dict[str, list[str]] = collections.defaultdict(list)
    for logical, actual in classes.items():
        inverse[actual].append(logical)
    # Kotlin 标记接口（无成员）会被 R8 合并，允许共享目标
    reference = hs.load(args.reference) if args.reference else {}
    for actual, owners in sorted(inverse.items()):
        if len(owners) < 2:
            continue
        empty_marker = all(
            len((reference.get(owner) or {}).get("meth", [1])) <= 1 for owner in owners
        )
        if not empty_marker:
            problems.append(f"target collision: {actual} <- {owners}")

    if args.obfuscated:
        obfuscated = hs.load(args.obfuscated)
        for logical, actual in sorted((classes | aliases).items()):
            if actual not in obfuscated:
                problems.append(f"missing target: {logical} -> {actual}")
        for logical in required:
            if logical not in obfuscated:
                problems.append(f"requiredClass NOT in obfuscated build: {logical}")
        for logical in absent:
            if logical in obfuscated:
                problems.append(f"absentClass actually present: {logical}")
        for key, actual in sorted(methods.items()):
            owner = key.split("#", 1)[0]
            target = method_owners.get(key) or (classes | aliases).get(owner, owner if owner in obfuscated else None)
            if target is None:
                problems.append(f"method owner unmapped: {key}")
                continue
            # 宿主方法常在父类声明（如 updateUiState 在 MVIViewModel 而非 ReaderViewModel），
            # 运行期按父类链查找，所以链上任何一环有该名字都算成立。
            chain = ancestors(obfuscated, target)
            if not any(actual in {n for n, _d in obfuscated[step]["rawMeth"]} for step in chain):
                problems.append(f"method not found: {key} -> {target}#{actual}")
        for key, actual in sorted(fields.items()):
            owner = key.split("#", 1)[0]
            target = (classes | aliases).get(owner, owner if owner in obfuscated else None)
            if target is None:
                problems.append(f"field owner unmapped: {key}")
            elif not any(actual in {n for n, _d in obfuscated[step]["rawFld"]}
                         for step in ancestors(obfuscated, target)):
                problems.append(f"field not found: {key} -> {target}#{actual}")

    if args.reference:
        for key in list(methods) + list(fields):
            owner, _sep, member = key.partition("#")
            entry = reference.get(owner)
            if entry is None:
                problems.append(f"unknown source class in reference: {owner}")
                continue
            members = "rawMeth" if key in methods else "rawFld"
            names = {n for step in ancestors(reference, owner) for n, _d in reference[step][members]}
            if member not in names:
                problems.append(f"member not in reference: {key}")

    print(f"classes={len(classes)} methods={len(methods)} fields={len(fields)}")
    print(f"problems={len(problems)}")
    for item in problems:
        print("  !", item)
    if args.json:
        args.json.write_text(json.dumps({"problems": problems}, ensure_ascii=False, indent=2),
                             encoding="utf-8")


if __name__ == "__main__":
    main()
