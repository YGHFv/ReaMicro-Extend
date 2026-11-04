import argparse
import base64
import html
import json
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

OUT = Path("C:/Users/YGHF/AppData/Local/Temp/reamicro-analysis/runtime/webdav-qa")
OUT.mkdir(exist_ok=True)
LOCK = threading.Lock()
USER, PASSWORD = "reamicro-qa", "b2-fixture-only"
AUTH = "Basic " + base64.b64encode(f"{USER}:{PASSWORD}".encode("utf-8")).decode("ascii")
TITLE = "ReaMicro-B2-WebDAV-QA-20261024"
BOOK = (TITLE + "\n\n第一章 网盘适配\n仅用于本轮独立 WebDAV 测试。\n\n第二章 阅读回归\n不包含任何用户内容。\n").encode("utf-8")
FILES = {"/Books/" + TITLE + ".txt": BOOK}
DIRS = {"/", "/Books", "/Backup"}
SLOW = "/Books/" + TITLE + "-Slow.txt"
SLOW_BYTES = 8 * 1024 * 1024
STATE = {"sequence": 0}


def record(**data):
    with LOCK:
        STATE["sequence"] += 1
        data.update(sequence=STATE["sequence"], time=datetime.now(timezone.utc).isoformat())
        with (OUT / "requests.jsonl").open("ab") as output:
            output.write(json.dumps(data, ensure_ascii=False).encode("utf-8") + b"\n")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def reply(self, code, body=b"", content_type="text/plain"):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Type", content_type)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
        record(method=self.command, path=self.path, status=code, bytes=len(body))

    def prepare(self):
        self.name = unquote(urlsplit(self.path).path).rstrip("/") or "/"
        if self.headers.get("Authorization") != AUTH:
            self.reply(401)
            return False
        length = int(self.headers.get("Content-Length", "0"))
        if length and self.command != "PUT":
            self.rfile.read(length)
        return True

    def do_OPTIONS(self):
        if self.prepare():
            self.reply(200)

    def do_PROPFIND(self):
        if not self.prepare():
            return
        if self.name not in DIRS and self.name not in FILES and self.name != SLOW:
            self.reply(404)
            return
        names = [self.name]
        if self.name in DIRS and self.headers.get("Depth") != "0":
            names += sorted(name for name in DIRS | FILES.keys() | {SLOW}
                            if name != self.name and (name.rsplit("/", 1)[0] or "/") == self.name)
        rows = []
        for name in names:
            directory = name in DIRS
            size = SLOW_BYTES if name == SLOW else len(FILES.get(name, b""))
            display = name.rsplit("/", 1)[-1] or "Root"
            href = quote(name) + ("/" if directory and name != "/" else "")
            rows.append(f"""<D:response><D:href>{href}</D:href><D:propstat><D:prop>
<D:displayname>{html.escape(display)}</D:displayname><D:resourcetype>{'<D:collection/>' if directory else ''}</D:resourcetype>
<D:getcontentlength>{size}</D:getcontentlength><D:getlastmodified>Sat, 24 Oct 2026 04:00:00 GMT</D:getlastmodified>
</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>""")
        body = ('<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:">'
                + "".join(rows) + "</D:multistatus>").encode("utf-8")
        self.reply(207, body, "application/xml; charset=utf-8")

    def do_GET(self):
        if not self.prepare():
            return
        if self.name != SLOW:
            self.reply(200, FILES[self.name]) if self.name in FILES else self.reply(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(SLOW_BYTES))
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.end_headers()
        written = 0
        record(method="GET", path=self.path, status=200, event="slow-start")
        try:
            while written < SLOW_BYTES:
                chunk = b"ReaMicro QA slow download.\n" * 128
                chunk = chunk[:min(len(chunk), SLOW_BYTES - written)]
                self.wfile.write(chunk)
                self.wfile.flush()
                written += len(chunk)
                time.sleep(0.15)
            record(method="GET", path=self.path, event="slow-complete", bytes=written)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            record(method="GET", path=self.path, event="slow-disconnected", bytes=written)
            self.close_connection = True

    def do_PUT(self):
        if self.prepare():
            FILES[self.name] = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            (OUT / ("upload-" + Path(self.name).name)).write_bytes(FILES[self.name])
            self.reply(201)

    def do_MKCOL(self):
        if self.prepare():
            DIRS.add(self.name)
            self.reply(201)

    def do_MOVE(self):
        if not self.prepare():
            return
        target = unquote(urlsplit(self.headers.get("Destination", "")).path).rstrip("/")
        if not target or target in FILES or target in DIRS:
            self.reply(412)
            return
        if self.name in FILES:
            FILES[target] = FILES.pop(self.name)
        elif self.name in DIRS:
            DIRS.remove(self.name)
            DIRS.add(target)
        else:
            self.reply(404)
            return
        self.reply(201)

    def do_DELETE(self):
        if self.prepare():
            FILES.pop(self.name, None)
            DIRS.discard(self.name)
            self.reply(204)

    def do_POST(self):
        if self.prepare():
            self.reply(404)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    (OUT / "server.json").write_bytes(json.dumps(
        {"port": args.port, "url": f"http://127.0.0.1:{args.port}/", "username": USER,
         "password": PASSWORD, "bookTitle": TITLE, "bookSize": len(BOOK), "slowTitle": TITLE + "-Slow",
         "localOnly": True, "fixtureOnly": True}, indent=2).encode("utf-8"))
    print(f"Fixture-only WebDAV listening on 127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
