import html
from pathlib import Path
from typing import Any

DEFAULT_PAGE_SIZE = 50
MAX_PAGE_SIZE = 200


def normalize_page(value: Any, default: int = 1) -> int:
    try:
        page = int(value)
    except (TypeError, ValueError):
        return default
    return page if page >= 1 else default


def normalize_page_size(value: Any, default: int = DEFAULT_PAGE_SIZE) -> int:
    try:
        size = int(value)
    except (TypeError, ValueError):
        return default
    return max(1, min(size, MAX_PAGE_SIZE))


def paginate(items: list[Any], page: int, page_size: int = DEFAULT_PAGE_SIZE) -> tuple[list[Any], dict[str, Any]]:

    total = len(items)
    page_size = normalize_page_size(page_size)
    pages = max(1, (total + page_size - 1) // page_size)
    page = min(max(1, normalize_page(page)), pages)
    start = (page - 1) * page_size
    return items[start:start + page_size], {
        "page": page,
        "pages": pages,
        "pageSize": page_size,
        "total": total,
        "start": start + 1 if total else 0,
        "end": min(start + page_size, total),
    }


def pager_html(info: dict[str, Any], base_path: str, extra: dict[str, str] | None = None) -> str:

    esc = lambda value: html.escape(str(value), quote=True)
    params = {key: value for key, value in (extra or {}).items() if str(value).strip()}

    def link(page: int, label: str, disabled: bool) -> str:
        if disabled:
            return f"<span class='button subtle' aria-disabled='true'>{esc(label)}</span>"
        query = "&".join(
            f"{key}={html.escape(str(value), quote=True)}"
            for key, value in {**params, "page": page}.items()
        )
        return f"<a class='button subtle' href='{esc(base_path)}?{query}'>{esc(label)}</a>"

    if info["total"] == 0:
        return "<p class='hint-tight'>暂无记录</p>"
    summary = f"第 {info['start']}–{info['end']} 条，共 {info['total']} 条"
    if info["pages"] <= 1:
        return f"<p class='hint-tight'>{esc(summary)}</p>"
    return (
        "<div class='pager'>"
        + link(1, "首页", info["page"] == 1)
        + link(info["page"] - 1, "上一页", info["page"] <= 1)
        + f"<span class='hint-tight'>{esc(summary)} · 第 {info['page']}/{info['pages']} 页</span>"
        + link(info["page"] + 1, "下一页", info["page"] >= info["pages"])
        + link(info["pages"], "末页", info["page"] == info["pages"])
        + "</div>"
    )


def read_tail_lines(path: Path, max_lines: int, chunk_size: int = 64 * 1024) -> list[str]:

    if not path.is_file():
        return []
    try:
        size = path.stat().st_size
    except OSError:
        return []
    if size == 0:
        return []
    buffer = b""
    try:
        with path.open("rb") as stream:
            position = size
            while position > 0 and buffer.count(b"\n") <= max_lines:
                step = min(chunk_size, position)
                position -= step
                stream.seek(position)
                buffer = stream.read(step) + buffer
    except OSError:
        return []
    lines = buffer.decode("utf-8", errors="replace").splitlines()

    if position > 0 and len(lines) > 1:
        lines = lines[1:]
    return lines[-max_lines:]
