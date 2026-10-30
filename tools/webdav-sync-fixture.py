"""只保存任务自建的随机对象，校验鉴权、条件写、目录与二进制请求。"""

import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

ROOT = Path("C:/Users/YGHF/AppData/Local/Temp/reamicro-analysis/runtime/webdav-sync")
LOCK = threading.Lock()
FILES = {}
DIRS = {"/dav"}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def handle_request(self):
        path = unquote(self.path).rstrip("/")
        method = self.command
        code, data = 200, b""
        headers = {}
        with LOCK:
            if self.headers.get("Authorization") != "Basic cWE6cWEtcGFzc3dvcmQ=":
                code = 401
            elif not path.startswith("/dav/"):
                code = 403
            elif method == "MKCOL":
                if path in DIRS:
                    code = 405
                elif path.rsplit("/", 1)[0] not in DIRS:
                    code = 409
                else:
                    DIRS.add(path)
                    code = 201
            elif method == "PUT":
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                current = FILES.get(path)
                etag = None if current is None else '"' + hashlib.sha256(current).hexdigest() + '"'
                if self.headers.get("If-None-Match") == "*" and current is not None:
                    code = 412
                elif self.headers.get("If-Match") is not None and self.headers["If-Match"] != etag:
                    code = 412
                elif path.rsplit("/", 1)[0] not in DIRS:
                    code = 409
                else:
                    FILES[path] = body
                    code = 201 if current is None else 204
            elif method in ("GET", "HEAD"):
                if path in FILES:
                    data = FILES[path]
                    headers["ETag"] = '"' + hashlib.sha256(data).hexdigest() + '"'
                elif path not in DIRS:
                    code = 404
            elif method == "DELETE":
                if path in FILES:
                    del FILES[path]
                    code = 204
                elif path in DIRS:
                    if any(p.startswith(path + "/") for p in FILES):
                        code = 409
                    else:
                        DIRS.remove(path)
                        code = 204
                else:
                    code = 404
            else:
                code = 405
            with (ROOT / "fixture-requests.jsonl").open("a", encoding="utf-8") as output:
                output.write(json.dumps({"method": method, "path": path, "code": code,
                                         "files": len(FILES)}, ensure_ascii=False) + "\n")
        self.send_response(code)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        if method != "HEAD":
            self.wfile.write(data)

    do_GET = do_HEAD = do_PUT = do_DELETE = do_MKCOL = handle_request


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", 18791), Handler)
    print("Task-only WebDAV fixture 127.0.0.1:18791", flush=True)
    server.serve_forever()
