#!/usr/bin/env python3
"""按完整描述符检查 B2 符号候选，输出只用于人工确认，不自动合并。"""

import argparse
import collections
from pathlib import Path

import hostsym as hs

CACHE = Path("C:/Users/YGHF/AppData/Local/Temp/reamicro-analysis")


def profile_classes(path):
    return dict(line.partition("=")[2].rpartition("=")[::2]
                for line in path.read_text(encoding="utf-8").splitlines()
                if line.startswith(("class=", "typeAlias=")))


def mapped(token, known):
    if token.startswith("["):
        return "[" + mapped(token[1:], known)
    if token.startswith("L"):
        name = hs.dotted(token)
        return "L" + known.get(name, name).replace(".", "/") + ";"
    return token


def candidates(desc, known, index):
    params = [mapped(t, known) for t in hs.parse_params(desc)]
    ret = mapped(hs.return_type(desc), known)
    return [(c, n, d) for c, n, d in index[hs.shape(desc)]
            if hs.parse_params(d) == params and hs.return_type(d) == ret]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("classes", nargs="+")
    p.add_argument("--all", action="store_true")
    args = p.parse_args()
    a, b = hs.load(CACHE / "b1-features.pkl.gz"), hs.load(CACHE / "b2-features.pkl.gz")
    known = profile_classes(Path("app/src/main/assets/host-profiles/2.3.3-b2.profile"))
    index = collections.defaultdict(list)
    for c, e in b.items():
        for n, d in e["rawMeth"]:
            index[hs.shape(d)].append((c, n, d))
    for c in args.classes:
        print("\nCLASS", c, "->", known.get(c))
        if c not in a:
            print(b.get(c))
            continue
        e = a[c]
        print("SUPER", e["rawSup"], "FIELDS", e["rawFld"])
        print("CTOR", [x for x in e["rawMeth"] if x[0] == "<init>"])
        for n, d in e["rawMeth"]:
            if not hs.hookable(n) or (not args.all and ("$" in n or n.startswith("component"))):
                continue
            hits = candidates(d, known, index)
            print(n, d, "=>", hits[:12], "count", len(hits))
        actual = known.get(c)
        if actual in b:
            print("TARGET METHODS", b[actual]["rawMeth"])
            print("TARGET FIELDS", b[actual]["rawFld"])


if __name__ == "__main__":
    main()
