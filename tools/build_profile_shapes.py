#!/usr/bin/env python3
"""从特征缓存生成 profile 的结构指纹资产，供热更后的运行期校验使用。

资产格式（ASCII，UTF-8 无 BOM）：
  profile=<profileId>
  shape=<逻辑类>|<父类>|<接口 csv>|<方法描述符 csv>|<字段描述符 csv>

描述符统一做两件事：去掉空白、把构建间会改名的引用类型折叠成 L*。
Kotlin 侧（HostDexKit）用同一规则从 DexKit 读出的实时 dex 元数据计算指纹，
两边一致才算「该 profile 仍适用于当前 dex」。

  --profile   需要校验的 profile
  --features  该 profile 对应构建的特征缓存（dump_features.py 输出）
  --output    资产输出路径（app/src/main/assets/host-profiles/<id>.shapes）
"""

from __future__ import annotations

import argparse
import gzip
import pickle
import re
from pathlib import Path

REFERENCE = re.compile(r"L[^;]+;")
STABLE_TYPE_PREFIXES = (
    "Ljava/",
    "Landroid/",
    "Ljavax/",
    "Ldalvik/",
    "Lkotlin/jvm/functions/Function",
)


def normalize(descriptor: str) -> str:
    compact = "".join(descriptor.split())
    return REFERENCE.sub(
        lambda match: match.group(0)
        if match.group(0).startswith(STABLE_TYPE_PREFIXES)
        else "L*",
        compact,
    )


def as_descriptor(name: str) -> str:
    """特征缓存里父类/接口既有描述符形式，也有裸名形式，统一成描述符。"""
    if name.startswith("L") and name.endswith(";"):
        return name
    return f"L{name.replace('.', '/')};"


def member_sign(class_descriptor: str, name: str, descriptor: str) -> str:
    """DexKit 的 MethodData/FieldData.descriptor 是完整签名，不是裸描述符。

    method -> `Lowner;->name(params)ret`，field -> `Lowner;->name:type`。
    运行期（HostDexKit）直接读这两个字段，所以资产必须用同一形态。
    """
    if descriptor.startswith("("):
        return f"{class_descriptor}->{name}{descriptor}"
    return f"{class_descriptor}->{name}:{descriptor}"


def class_shape(entry: dict, class_descriptor: str) -> tuple[str, str, str, str]:
    super_name = entry.get("rawSup") or ""
    interfaces = sorted(normalize(as_descriptor(item)) for item in entry.get("rawIfc", ()))
    methods = sorted(
        normalize(member_sign(class_descriptor, name, descriptor))
        for name, descriptor in entry.get("rawMeth", ())
    )
    fields = sorted(
        normalize(member_sign(class_descriptor, name, descriptor))
        for name, descriptor in entry.get("rawFld", ())
    )
    return (
        normalize(as_descriptor(super_name)) if super_name else "",
        ",".join(interfaces),
        ",".join(methods),
        ",".join(fields),
    )


def parse_profile_classes(path: Path) -> dict[str, str]:
    classes: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("class="):
            body = line[len("class="):]
            key, _, value = body.rpartition("=")
            classes[key.strip()] = value.strip()
    return classes


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--features", type=Path, required=True)
    parser.add_argument("--profile-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    classes = parse_profile_classes(args.profile)
    with gzip.open(args.features, "rb") as handle:
        features = pickle.load(handle)

    lines = [f"profile={args.profile_id}"]
    written = 0
    skipped: list[str] = []
    for logical, actual in sorted(classes.items()):
        entry = features.get(actual)
        if entry is None:
            skipped.append(logical)
            continue
        class_descriptor = "L" + actual.replace(".", "/") + ";"
        super_name, interfaces, methods, fields = class_shape(entry, class_descriptor)
        lines.append(f"shape={logical}|{super_name}|{interfaces}|{methods}|{fields}")
        written += 1
    lines.append(f"# classes={written} skipped={len(skipped)}")

    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"profile={args.profile_id} shapes={written} skipped={len(skipped)}")
    for name in skipped[:10]:
        print(f"  skipped: {name}")
    print(f"output={args.output} bytes={args.output.stat().st_size}")


if __name__ == "__main__":
    main()
