"""书源使用的轻量 JSONPath 兼容器。

这是模块端 `OnlineJsonPathCompat.kt` 的 Python 移植，**语义必须与模块一致**——
服务端按这套语义判定"规则还能不能取到数据"，如果两边解析结果不同，检测结论就没有意义。

支持的语法（只覆盖书源规则实际用到的部分）：

    $.a.b          字段路径
    .a.b / a.b     省略 $ 前缀
    $              整个节点
    a[0] / a[*]    数组下标与通配
    *  / *[*]      对象或数组的全部子节点
    $..name        递归下降，且下降后继续执行剩余路径
    a||b           取第一个非空结果
    a&&b           合并全部结果

刻意不支持过滤表达式（`[?(...)]`）与脚本（`@js:`）：书源里用得少，
而且服务端没有 JS 引擎，遇到时按"无法检测"处理，不假装成功。
"""
import re
from typing import Any

_TOKEN_RE = re.compile(r"^([^\[]+)(?:\[(\d+|\*)\])?$")
_LEADING_SELECTOR_RE = re.compile(r"^\[(\d+|\*)\]")

# 递归下降与字段访问的最大展开量，防止畸形规则在大响应上炸开。
MAX_VALUES = 5000


def values(node: Any, raw_rule: str) -> list[Any]:
    """按规则取值。`&&` 合并全部分支，`||` 取第一个非空分支。"""
    rule = (raw_rule or "").strip()
    if node is None or not rule:
        return []
    if "&&" in rule:
        merged: list[Any] = []
        for part in rule.split("&&"):
            merged.extend(values(node, part.strip()))
            if len(merged) >= MAX_VALUES:
                break
        return merged[:MAX_VALUES]
    for part in rule.split("||"):
        found = _values_single(node, part.strip())
        if found:
            return found[:MAX_VALUES]
    return []


def _values_single(node: Any, raw_rule: str) -> list[Any]:
    if node is None or not raw_rule:
        return []
    if raw_rule.startswith("$.."):
        return _recursive_path_values(node, raw_rule[3:])
    path = raw_rule
    if path.startswith("$."):
        path = path[2:]
    elif path == "$":
        return [node]
    elif path.startswith("."):
        path = path[1:]
    return _follow_path([node], path)


def _recursive_path_values(node: Any, descendant_path: str) -> list[Any]:
    name = descendant_path.split(".", 1)[0].split("[", 1)[0]
    if not name:
        return []
    current = _recursive_values(node, name)
    remaining = descendant_path[len(name):]
    selector = _LEADING_SELECTOR_RE.match(remaining)
    if selector:
        current = _apply_array_selector(current, selector.group(1))
        remaining = remaining[selector.end():]
    remaining = remaining.removeprefix(".")
    return _follow_path(current, remaining)


def _follow_path(initial: list[Any], raw_path: str) -> list[Any]:
    if not raw_path.strip():
        return _valid(initial)
    current = initial
    for token in (part for part in raw_path.split(".") if part.strip()):
        stepped: list[Any] = []
        for value in current:
            stepped.extend(_step(value, token))
            if len(stepped) >= MAX_VALUES:
                break
        current = stepped
        if not current:
            return []
    return _valid(current)


def _step(value: Any, token: str) -> list[Any]:
    if value is None:
        return []
    if token in {"*", "*[*]"}:
        children = _children(value)
        if token.endswith("[*]"):
            flattened: list[Any] = []
            for child in children:
                flattened.extend(_array_items(child))
            return flattened
        return children
    match = _TOKEN_RE.match(token)
    if not match:
        return []
    name, index = match.group(1), match.group(2) or ""
    if isinstance(value, dict):
        found = [value.get(name)]
    elif isinstance(value, list):
        found = [item.get(name) for item in value if isinstance(item, dict)]
    else:
        return []
    if index == "":
        return found
    if index == "*":
        flattened = []
        for item in found:
            flattened.extend(_array_items(item))
        return flattened
    return _apply_array_selector(found, index)


def _apply_array_selector(items: list[Any], selector: str) -> list[Any]:
    if selector == "*":
        flattened: list[Any] = []
        for item in items:
            flattened.extend(_array_items(item))
        return flattened
    try:
        position = int(selector)
    except ValueError:
        return []
    picked = []
    for item in items:
        if isinstance(item, list) and 0 <= position < len(item):
            picked.append(item[position])
    return picked


def _children(value: Any) -> list[Any]:
    if isinstance(value, dict):
        return list(value.values())
    if isinstance(value, list):
        return list(value)
    return []


def _array_items(value: Any) -> list[Any]:
    if isinstance(value, list):
        return list(value)
    if isinstance(value, dict):
        return list(value.values())
    if value is None:
        return []
    return [value]


def _recursive_values(node: Any, name: str) -> list[Any]:
    found: list[Any] = []

    def visit(item: Any) -> None:
        if len(found) >= MAX_VALUES:
            return
        if isinstance(item, dict):
            if name in item:
                found.append(item[name])
            for child in item.values():
                visit(child)
        elif isinstance(item, list):
            for child in item:
                visit(child)

    visit(node)
    return _valid(found)


def _valid(items: list[Any]) -> list[Any]:
    return [item for item in items if item is not None]


def rule_items(value: Any) -> list[Any]:
    """把取到的值摊平成条目列表。数组摊开，单值包成一项。"""
    if isinstance(value, list):
        return list(value)
    return [] if value is None else [value]


def candidate_roots(node: Any) -> list[Any]:
    """书源常把列表埋在 data / result / rows 之类的包裹层里。

    与模块端 `onlineJsonCandidateRoots` 保持一致：先试节点本身，再试这些常见包裹键，
    以及 data 下再套一层的情况。
    """
    wrappers = ("data", "result", "book", "chapter", "rows", "ret_data")
    roots: list[Any] = []
    seen: list[int] = []

    def add(value: Any) -> None:
        if value is None:
            return
        marker = id(value)
        if marker in seen:
            return
        seen.append(marker)
        roots.append(value)

    add(node)
    if isinstance(node, dict):
        for key in wrappers:
            add(node.get(key))
        nested = node.get("data")
        if isinstance(nested, dict):
            for key in wrappers:
                add(nested.get(key))
    return roots


def rule_values(node: Any, rule: str) -> list[Any]:
    """按书源列表规则取条目。规则里的 `<js>` / `@js:` 段直接截掉，脚本部分不执行。"""
    if node is None or not (rule or "").strip():
        return []
    selector = rule.split("<js>", 1)[0].split("@js:", 1)[0].strip()
    if not selector:
        return []
    for candidate in candidate_roots(node):
        found: list[Any] = []
        for value in values(candidate, selector):
            found.extend(rule_items(value))
        if found:
            return found
    return []


def primitive(value: Any) -> str:
    """取标量文本。与模块端 onlineJsonPrimitive 一致：布尔与数字转字符串，其余原样。"""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        # 整数不要显示成 1.0
        return str(int(value)) if float(value).is_integer() else str(value)
    if isinstance(value, str):
        return value.strip()
    return str(value).strip()


def rule_string(node: Any, rule: str) -> str:
    """按规则取第一个标量值。"""
    selector = (rule or "").split("<js>", 1)[0].split("@js:", 1)[0].strip()
    if not selector:
        return ""
    found = values(node, selector)
    return primitive(found[0]) if found else ""
