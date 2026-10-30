"""只读保存 MT 同步链路证据，避免把整段反编译内容送入对话。"""

import json
import sys
from pathlib import Path

from _r11_mt import call

OUT = Path("C:/Users/YGHF/AppData/Local/Temp/reamicro-analysis/runtime/webdav-sync")
BASE = {"workspaceId": "zz3ctgjp", "editSessionId": ""}
OUT.mkdir(parents=True, exist_ok=True)


def read_class(name):
    chunks = []
    start = 0
    while True:
        data = call("mt_apk_read_text", {
            **BASE, "locator": f"dex_class:L{name};", "limit": 2000,
            "maxChars": 131072, "startLine": start, "startColumn": 0,
        })
        window = data["textWindow"]
        chunks.append(window["text"])
        if not data["pagination"]["hasMore"]:
            break
        start = window["nextLine"]
    path = OUT / f"{name}.smali"
    path.write_text("".join(chunks), encoding="utf-8", newline="\n")
    print(name, path.stat().st_size)


if sys.argv[1:] == ["search"]:
    data = call("mt_apk_search", {
        **BASE, "query": "S3", "target": "dex_string_members",
        "queryType": "literal", "caseSensitive": False, "matchMode": "contains",
        "prefix": "", "includeMatchOffsets": False, "limit": 80, "snippetMaxChars": 180,
    })
    (OUT / "s3-strings.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    for hit in data["hits"]:
        print(hit["locator"], hit["hit"]["snippet"][:140])
else:
    for name in sys.argv[1:]:
        read_class(name)
