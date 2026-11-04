#!/usr/bin/env python3
"""Extract rename-invariant structural features from an APK into a compact cache.

The cache is deliberately small: only what survives R8 and actually separates two
classes from each other (descriptor shapes, embedded literal strings, instruction
counts, field-write order). Opcode histograms let the differ tell apart methods
that share a signature.
"""

from __future__ import annotations

import argparse
import gzip
import pickle
import re
from pathlib import Path

from androguard.core.apk import APK
from androguard.core.dex import DEX
from loguru import logger

logger.remove()

STABLE_TYPE_PREFIXES = (
    "Ljava/",
    "Landroid/",
    "Ljavax/",
    "Ldalvik/",
    "Lkotlin/jvm/functions/Function",
)
REFERENCE = re.compile(r"L[^;]+;")
ACCESS_MASK = 0x0001 | 0x0002 | 0x0004 | 0x0008 | 0x0010 | 0x1000
INTERFACE_MASK = 0x0200 | 0x0400 | 0x1000
CONST_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
FIELD_REF = re.compile(r"->([A-Za-z_$][\w$]*)\s")


def stable(descriptor: str) -> str:
    def replace(match: re.Match[str]) -> str:
        value = match.group(0)
        return value if value.startswith(STABLE_TYPE_PREFIXES) else "L*"

    return REFERENCE.sub(replace, descriptor)


def count_params(params: str) -> int:
    count = 0
    index = 0
    while index < len(params):
        while index < len(params) and params[index] == "[":
            index += 1
        if index >= len(params):
            break
        if params[index] == "L":
            end = params.find(";", index)
            if end < 0:
                break
            index = end + 1
        else:
            index += 1
        count += 1
    return count


def method_entry(method) -> dict:
    descriptor = method.get_descriptor()
    close = descriptor.rindex(")")
    params = descriptor[1:close]
    code = method.get_code()
    strings: list[str] = []
    writes: list[str] = []
    opcodes: list[str] = []
    count = 0
    if code is not None:
        for instruction in code.get_bc().get_instructions():
            count += 1
            name = instruction.get_name()
            opcodes.append(name)
            if name == "const-string":
                # Androguard prints "vN, \"literal\""; keep the literal only.
                match = CONST_STRING.search(instruction.get_output())
                if match:
                    strings.append(match.group(1))
            elif name.startswith("iput") and len(writes) < 64:
                match = FIELD_REF.search(instruction.get_output())
                if match and match.group(1) not in writes:
                    writes.append(match.group(1))
    return {
        "n": method.get_name(),
        # Access flags are excluded on purpose: R8 rewrites visibility and
        # synthetic markers, so they cannot be part of an identity fingerprint.
        "s": (count_params(params), stable(params), stable(descriptor[close + 1:])),
        "a": method.get_access_flags() & ACCESS_MASK,
        "i": count,
        "t": tuple(sorted(set(strings))),
        "w": tuple(writes),
        "o": tuple(sorted(set(opcodes))),
    }


def class_entry(cls) -> dict:
    super_name = cls.get_superclassname() or ""
    interfaces = []
    for item in cls.get_interfaces() or []:
        raw = item if isinstance(item, str) else item.name
        interfaces.append(stable(f"L{raw};").strip("L;"))
    return {
        "acc": cls.get_access_flags(),
        "shape": cls.get_access_flags() & INTERFACE_MASK,
        "sup": stable(f"L{super_name};").strip("L;") if super_name else "",
        "ifc": tuple(sorted(interfaces)),
        "meth": tuple(method_entry(m) for m in cls.get_methods()),
        "fld": tuple(sorted(stable(f.get_descriptor()) for f in cls.get_fields())),
        "fnames": tuple(f.get_name() for f in cls.get_fields()),
        # Raw descriptors are what the second matching round walks in parallel:
        # two mapped members whose descriptors differ only by renamed types reveal
        # those types' own mapping. Only classes the differ still needs are kept.
        "rawMeth": tuple((m.get_name(), m.get_descriptor()) for m in cls.get_methods()),
        "rawFld": tuple((f.get_name(), f.get_descriptor()) for f in cls.get_fields()),
        "rawSup": super_name,
        "rawIfc": tuple(sorted(item if isinstance(item, str) else item.name for item in (cls.get_interfaces() or []))),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    apk = APK(str(args.apk))
    classes: dict[str, dict] = {}
    for raw in apk.get_all_dex():
        for cls in DEX(raw).get_classes():
            name = cls.name[1:-1].replace("/", ".")
            classes[name] = class_entry(cls)
    with gzip.open(args.output, "wb") as handle:
        pickle.dump(classes, handle, protocol=4)
    print(f"apk={args.apk.name} classes={len(classes)} output={args.output}")


if __name__ == "__main__":
    main()
