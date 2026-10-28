#!/usr/bin/env python3
"""按完整指令及引用图迁移 RC3；有歧义的映射只记报告，不按短名猜测。"""

from collections import Counter, defaultdict
import gzip
import hashlib
import json
import os
from pathlib import Path
import pickle
import re
from zipfile import ZipFile

from androguard.core.dex import DEX
from loguru import logger

import hostsym
import retarget_profile as rp
from check_profile import ancestors

logger.remove()
ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ["TEMP"]) / "reamicro-analysis"
OUT = CACHE / "runtime/rc3"
REF = re.compile(r"L[^;]+;")
MEMBER = re.compile(r"(L[^;]+;)->([\w$<>-]+)(.*)")


def dotted(value):
    return value[1:-1].replace("/", ".")


def normalized(output):
    output = re.sub(r"->[^\s(]+", "->*", output)
    return rp.stable(output).replace(" ", "")


def load_code(label):
    cached = OUT / (label + "-code.pkl.gz")
    if cached.exists():
        with gzip.open(cached, "rb") as handle:
            return pickle.load(handle)
    result = {}
    apk = CACHE / ("RM2.3.3." + label.upper() + ".APK")
    with ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            for cls in DEX(archive.read(name)).get_classes():
                methods = {}
                for method in cls.get_methods():
                    instructions = tuple(
                        (i.get_name(), i.get_output()) for i in method.get_instructions()
                    ) if method.get_code() else ()
                    signature = method.get_descriptor().replace(" ", "")
                    methods[(method.get_name(), signature)] = {
                        "ins": instructions,
                        "static": bool(method.get_access_flags() & 8),
                        "fp": (
                            rp.stable(signature),
                            bool(method.get_access_flags() & 8),
                            tuple((op, normalized(text)) for op, text in instructions),
                        ),
                    }
                result[dotted(cls.name)] = {
                    "methods": methods,
                    "fields": tuple((f.get_name(), f.get_descriptor()) for f in cls.get_fields()),
                    "parents": tuple([cls.get_superclassname()] + list(cls.get_interfaces())),
                }
    with gzip.open(cached, "wb") as handle:
        pickle.dump(result, handle, protocol=4)
    return result


def index_methods(code):
    index = defaultdict(list)
    for owner, cls in code.items():
        for key, method in cls["methods"].items():
            index[method["fp"]].append((owner, key))
    return index


def main():
    source, target = load_code("b4"), load_code("rc3")
    src_raw = hostsym.load(CACHE / "b4-features.pkl.gz")
    dst_raw = hostsym.load(CACHE / "rc3-features.pkl.gz")
    profile = rp.parse_profile(ROOT / "app/src/main/assets/host-profiles/2.3.3-b4.profile")
    left_index, right_index = index_methods(source), index_methods(target)
    mapping = {}
    evidence = {}
    pairs = {}
    fields = {}
    conflicts = []
    votes = defaultdict(Counter)

    def claim(old, new, why):
        if old not in source or new not in target:
            return
        votes[old][new] += 8 if ":instance-owner" in why else 1
        if old in mapping:
            if mapping[old] != new:
                conflicts.append([old, mapping[old], new, why])
            return
        if old.startswith(("java.", "android.", "javax.")) and old != new:
            return
        mapping[old] = new
        evidence[old] = why

    def types(before, after, why):
        left, right = REF.findall(before), REF.findall(after)
        if len(left) != len(right) or rp.stable(before) != rp.stable(after):
            return
        for old, new in zip(left, right):
            claim(dotted(old), dotted(new), why)

    def pair(old_owner, old_key, new_owner, new_key, why):
        k = (old_owner, old_key)
        if k in pairs:
            return
        pairs[k] = (new_owner, new_key)
        types(old_key[1], new_key[1], why + ":signature")
        old_method = source[old_owner]["methods"][old_key]
        new_method = target[new_owner]["methods"][new_key]
        if not old_method["static"] or old_key[0] == "<init>":
            claim(old_owner, new_owner, why + ":instance-owner")
        # 完全一致的指令序列才按相同位置传播字段与引用；不对相似方法做位置猜测。
        if old_method["fp"] != new_method["fp"]:
            return
        for (op, a), (_, b) in zip(old_method["ins"], new_method["ins"]):
            if op in ("const-string", "const-string/jumbo"):
                continue
            types(a, b, why + ":instruction")
            fa, fb = MEMBER.search(a), MEMBER.search(b)
            if fa and fb and op.startswith(("iget", "iput", "sget", "sput")):
                fields[(dotted(fa[1]), fa[2])] = (dotted(fb[1]), fb[2])

    # 唯一且非平凡的完整方法指纹作为种子，保留寄存器、常量与分支信息。
    seeds = []
    for fp, old in left_index.items():
        new = right_index.get(fp, [])
        if len(old) == len(new) == 1 and len(fp[2]) >= 8:
            seeds.append((old[0], new[0]))
    for (a, ak), (b, bk) in seeds:
        pair(a, ak, b, bk, "unique-full-code")
    # 静态门面可拆分；普通实例类用多条独立引用投票排除内联造成的单次误配。
    for old, candidates in votes.items():
        ranked = candidates.most_common()
        if ranked[0][1] >= 3 and (len(ranked) == 1 or ranked[0][1] >= ranked[1][1] * 3):
            mapping[old] = ranked[0][0]
            evidence[old] = f"reference-votes:{ranked[:3]}"
    for name in source:
        if "." in name and name in target and name not in mapping:
            claim(name, name, "stable-qualified-name")

    for round_number in range(12):
        previous = (len(mapping), len(pairs), len(fields))
        call_votes = defaultdict(Counter)
        for (old, ak), (new, bk) in list(pairs.items()):
            am, bm = source[old]["methods"][ak], target[new]["methods"][bk]
            if am["fp"] != bm["fp"]:
                continue
            for (op, a), (_, b) in zip(am["ins"], bm["ins"]):
                if not op.startswith("invoke-"):
                    continue
                ma, mb = MEMBER.search(a), MEMBER.search(b)
                if ma is None or mb is None:
                    continue
                ao, bo = dotted(ma[1]), dotted(mb[1])
                ka = (ma[2], ma[3].replace(" ", ""))
                kb = (mb[2], mb[3].replace(" ", ""))
                if ka not in source.get(ao, {}).get("methods", {}) or kb not in target.get(bo, {}).get("methods", {}):
                    continue
                if not source[ao]["methods"][ka]["static"] and mapping.get(ao) != bo:
                    continue
                call_votes[(ao, ka)][(bo, kb)] += 1
        for (ao, ka), candidates in call_votes.items():
            if len(candidates) == 1:
                bo, kb = next(iter(candidates))
                pair(ao, ka, bo, kb, "aligned-caller")
        for old, new in list(mapping.items()):
            a, b = source[old], target[new]
            if len(a["parents"]) == len(b["parents"]):
                for pa, pb in zip(a["parents"], b["parents"]):
                    types(pa, pb, "mapped-parent")
            for ak, am in a["methods"].items():
                if (old, ak) in pairs:
                    continue
                candidates = [
                    (bk, bm) for bk, bm in b["methods"].items() if am["fp"] == bm["fp"]
                ]
                if len(candidates) > 1:
                    # 已知引用类型可区分无方法体接口与相同形状重载。
                    desc = REF.sub(lambda m: "L" + mapping.get(dotted(m[0]), dotted(m[0])).replace(".", "/") + ";", ak[1])
                    candidates = [(bk, bm) for bk, bm in candidates if bk[1] == desc]
                if len(candidates) == 1:
                    pair(old, ak, new, candidates[0][0], "mapped-owner-full-code")
            for af, ad in a["fields"]:
                if (old, af) in fields:
                    continue
                expected = REF.sub(lambda m: "L" + mapping.get(dotted(m[0]), dotted(m[0])).replace(".", "/") + ";", ad)
                hits = [(bf, bd) for bf, bd in b["fields"] if bd == expected]
                if len(hits) == 1 and sum(d == ad for _, d in a["fields"]) == 1:
                    fields[(old, af)] = (new, hits[0][0])
        print("round", round_number, "classes/methods/fields", len(mapping), len(pairs), len(fields))
        if previous == (len(mapping), len(pairs), len(fields)):
            break

    needed = profile["classes"] | profile["typeAliases"]
    result = {bucket: {} for bucket in ("classes", "typeAliases", "methods", "fields", "methodOwners")}
    pending = {"classes": [], "methods": [], "fields": []}
    for bucket in ("classes", "typeAliases"):
        for logical, old in profile[bucket].items():
            if old in mapping:
                result[bucket][logical] = mapping[old]
            else:
                pending["classes"].append([logical, old])
    for key, method in profile["methods"].items():
        logical = key.partition("#")[0]
        owner = profile["methodOwners"].get(key) or needed.get(logical, logical)
        found = set()
        for parent in ancestors(src_raw, owner):
            for mk in source[parent]["methods"]:
                if mk[0] == method and (parent, mk) in pairs:
                    found.add(pairs[(parent, mk)])
            if found:
                break
        names = {(own, mk[0]) for own, mk in found}
        if len(names) == 1:
            own, name = next(iter(names))
            result["methods"][key] = name
            mapped_owner = result["classes"].get(logical) or result["typeAliases"].get(logical)
            if own not in ancestors(dst_raw, mapped_owner or "") or key in profile["methodOwners"]:
                result["methodOwners"][key] = own
        else:
            pending["methods"].append([key, owner, method, list(names)])
    for key, field in profile["fields"].items():
        logical = key.partition("#")[0]
        owner = needed.get(logical, logical)
        found = [fields[(p, field)] for p in ancestors(src_raw, owner) if (p, field) in fields]
        if found:
            result["fields"][key] = found[0][1]
        else:
            pending["fields"].append([key, owner, field])
    report = {"mapping": mapping, "evidence": evidence, "profile": result, "votes": votes,
              "pending": pending, "conflicts": conflicts,
              "methodPairs": [[a, list(ak), b, list(bk)] for (a, ak), (b, bk) in pairs.items()],
              "fieldPairs": [[a, af, b, bf] for (a, af), (b, bf) in fields.items()]}
    (OUT / "graph-retarget.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("pending", {k: len(v) for k, v in pending.items()}, "conflicts", len(conflicts))
    for key, rows in pending.items():
        for row in rows[:35]:
            print(key, *row)


if __name__ == "__main__":
    main()
