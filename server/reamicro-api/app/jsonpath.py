import re
from typing import Any

_TOKEN_RE = re.compile(r"^([^\[]+)(?:\[(\d+|\*)\])?$")
_LEADING_SELECTOR_RE = re.compile(r"^\[(\d+|\*)\]")


MAX_VALUES = 5000


def values(node: Any, raw_rule: str) -> list[Any]:

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

    if isinstance(value, list):
        return list(value)
    return [] if value is None else [value]


def candidate_roots(node: Any) -> list[Any]:

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

    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):

        return str(int(value)) if float(value).is_integer() else str(value)
    if isinstance(value, str):
        return value.strip()
    return str(value).strip()


def rule_string(node: Any, rule: str) -> str:

    selector = (rule or "").split("<js>", 1)[0].split("@js:", 1)[0].strip()
    if not selector:
        return ""
    found = values(node, selector)
    return primitive(found[0]) if found else ""
