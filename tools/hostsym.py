#!/usr/bin/env python3
"""混淆匹配共用的 DEX 描述符解析与形状计算。

注意：形状必须统计**全部**参数槽位（含 ``I`` / ``J`` / ``Z`` 等基元）。
只数引用类型会把 ``foo(int, Composer)`` 与 ``foo(long, Composer)`` 误判为同形状，
Composable 方法恰恰大量依赖基元参数（颜色、尺寸、composer 重写位），
因此这里的正确性直接决定 profile 的可信度。
"""

from __future__ import annotations

import gzip
import pickle
import re
from pathlib import Path

REFERENCE = re.compile(r"L(?:[\w$]+/)*[\w$]+;")
PINNED_PREFIXES = (
    "java.", "javax.", "android.", "dalvik.", "kotlin.jvm.functions.Function",
)
GENERIC = {
    "Ljava/lang/Object;", "Ljava/lang/String;", "Ljava/lang/CharSequence;",
    "Ljava/lang/Integer;", "Ljava/lang/Long;", "Ljava/lang/Boolean;",
    "Ljava/lang/Number;", "Ljava/lang/Comparable;", "Ljava/io/Serializable;",
    "Ljava/util/List;", "Ljava/util/Map;", "Ljava/util/Set;", "Ljava/util/Collection;",
    "Ljava/util/Iterator;", "Ljava/lang/Throwable;",
}
SKIP_METHOD_PREFIX = ("<init>", "<clinit>", "access$", "lambda$", "$r8$")


def parse_params(descriptor: str) -> list[str]:
    """逐槽位解析参数列表，数组按其首元素归类。"""
    close = descriptor.index(")")
    body = "".join(descriptor[1:close].split())
    out: list[str] = []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "[":
            probe = index
            while probe < len(body) and body[probe] == "[":
                probe += 1
            if body[probe] == "L":
                end = body.index(";", probe)
                out.append(body[index:end + 1])
                index = end + 1
            else:
                out.append(body[index:probe + 1])
                index = probe + 1
        elif char == "L":
            end = body.index(";", index)
            out.append(body[index:end + 1])
            index = end + 1
        else:
            out.append(char)
            index += 1
    return out


def return_type(descriptor: str) -> str:
    return descriptor[descriptor.index(")") + 1:].strip()


def kind(token: str) -> str:
    if token.startswith("["):
        return "["
    if token.startswith("L"):
        return "L"
    if token == "J" or token == "D":
        return "W"  # 宽类型：long/double 各占两个寄存器，必须与普通 int 区分
    return token


def shape(descriptor: str) -> tuple[int, str, str]:
    """(参数槽位数, 返回类型槽, 全部参数槽位类别串)。"""
    params = parse_params(descriptor)
    ret = return_type(descriptor)
    return (len(params), kind(ret), "".join(kind(token) for token in params))


def ref_tokens(tokens: list[str]) -> list[str]:
    return [token for token in tokens if token.startswith("L")]


def dotted(token: str) -> str:
    return token[1:-1].replace("/", ".")


def resolve_with(known: dict[str, str], token: str) -> str | None:
    """引用类型槽位的期望真实名；未知返回 None，基元返回哨兵 ``''``。"""
    if not token.startswith("L"):
        return ""
    name = dotted(token)
    if name in known:
        return known[name]
    return name if name.startswith(PINNED_PREFIXES) else None


def scored(token: str) -> bool:
    """该槽位是否具备区分度（引用类型且非通用容器/基元）。"""
    return token.startswith("L") and token not in GENERIC \
        and not dotted(token).startswith(PINNED_PREFIXES)


def load(path: Path) -> dict[str, dict]:
    with gzip.open(path, "rb") as handle:
        return pickle.load(handle)


def members(entry: dict) -> list[tuple[str, str]]:
    return list(entry["rawMeth"])


def hookable(name: str) -> bool:
    if name.startswith(SKIP_METHOD_PREFIX) or name in ("<init>", "<clinit>"):
        return False
    # Compose 合成 lambda 在 release 里会被删掉或改名，``$default`` / mangled 后缀要保留
    return True
