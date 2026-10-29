#!/usr/bin/env python3
"""自建在线源与发现服务，仅提供验收文本；章节数通过本机文件控制。"""

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from rc3_full_audit import OUT, adb

BASE = "http://127.0.0.1:18782"
FOLDER = OUT / "continue-online-service"
SOURCE = {
    "bookSourceName": "RC3-Audit-Online",
    "bookSourceUrl": BASE,
    "reamicroSourceId": "rc3-audit-online",
    "searchUrl": BASE + "/search?q={{key}}",
    "ruleSearch": {"bookList": "$.books", "name": "$.name", "author": "$.author",
                   "bookUrl": "$.bookUrl", "lastChapter": "$.lastChapter"},
    "ruleBookInfo": {"name": "$.name", "author": "$.author", "tocUrl": "$.tocUrl",
                     "intro": "$.intro", "lastChapter": "$.lastChapter"},
    "ruleToc": {"chapterList": "$.chapters", "chapterName": "$.name", "chapterUrl": "$.url"},
    "ruleContent": {"content": "$.content"},
    "exploreUrl": "Audit::" + BASE + "/search",
    "ruleExplore": {"bookList": "$.books", "name": "$.name", "author": "$.author",
                    "bookUrl": "$.bookUrl", "lastChapter": "$.lastChapter"},
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        count = int((FOLDER/"chapters.txt").read_text(encoding="utf-8"))
        book = {"name": "RC3 Audit Online Book", "author": "ReaMicro QA",
                "bookUrl": BASE+"/book", "tocUrl": BASE+"/toc", "intro": "Task-owned fixture.",
                "lastChapter": "Chapter "+str(count)}
        path = urlsplit(self.path).path
        code = 200
        if path == "/search":
            value = {"books": [book]}
        elif path == "/book":
            value = book
        elif path == "/toc":
            value = {"chapters": [{"name": "Chapter "+str(n), "url": BASE+"/chapter/"+str(n)}
                                 for n in range(1, count+1)]}
        elif path.startswith("/chapter/") and path.rsplit("/", 1)[-1].isdigit():
            number = int(path.rsplit("/", 1)[-1])
            value = {"content": "\n\n".join(
                f"OnlineAuditChapter{number} paragraph {p}. " + "A task-owned story follows a quiet river. "*8
                for p in range(5))}
        else:
            code, value = 404, {"error": "Fixture path not found"}
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        with (FOLDER/"requests.jsonl").open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps({"path": self.path, "code": code, "chapters": count})+"\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("prepare", "serve"))
    args = parser.parse_args()
    if args.mode == "prepare":
        FOLDER.mkdir(exist_ok=False)
        (FOLDER/"chapters.txt").write_text("2", encoding="utf-8")
        path = FOLDER/"RC3-Audit-Online.json"
        path.write_text(json.dumps(SOURCE, ensure_ascii=False, indent=2), encoding="utf-8")
        print(adb("push", str(path), "/sdcard/Download/RC3-Audit-Online.json"))
    else:
        server = ThreadingHTTPServer(("127.0.0.1", 18782), Handler)
        server.daemon_threads = True
        print("Task-only online source 127.0.0.1:18782", flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()
