#!/usr/bin/env python3
"""专用本机 WebDAV，复用既有回环测试账号，不连接真实网盘。"""

from http.server import ThreadingHTTPServer
from rc3_full_audit import OUT
import b2_webdav_test_server as fixture

fixture.OUT = OUT / "resume-webdav-service"
fixture.OUT.mkdir(exist_ok=False)
fixture.TITLE = "RC3-Audit-WebDAV"
fixture.BOOK = b"RC3-Audit-WebDAV\n\nChapter One\nFullAuditNetworkNeedle. Task-owned content.\n"
fixture.FILES = {"/Books/RC3-Audit-WebDAV.txt": fixture.BOOK}
fixture.DIRS = {"/", "/Books", "/Backup"}
fixture.SLOW = "/Books/RC3-Audit-WebDAV-Slow.txt"
server = ThreadingHTTPServer(("127.0.0.1", 18765), fixture.Handler)
server.daemon_threads = True
print("Task-only WebDAV 127.0.0.1:18765", flush=True)
try:
    server.serve_forever()
finally:
    server.server_close()
