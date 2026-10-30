"""在独立 app_process 验证新宿主 ABI 与任务自建 WebDAV，不操作用户书架。"""

import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ["TEMP"]) / "reamicro-analysis/runtime/webdav-sync/contract"
JAVA = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
SDK = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
REMOTE = "/data/local/tmp/reamicro-webdav-sync-contract"
OUT.mkdir(parents=True, exist_ok=True)
source = (ROOT / "tools/Rc3ProtocolProbe.java").read_text(encoding="utf-8")
source = source.replace("host-profiles/2.3.3-rc3.profile", "host-profiles/2.3.3.profile")
(OUT / "Rc3ProtocolProbe.java").write_text(source, encoding="utf-8", newline="\n")
subprocess.run([
    str(JAVA / "javac.exe"), "-encoding", "UTF-8", "--release", "8", "-cp",
    str(SDK / "platforms/android-33/android.jar"), "-d", str(OUT),
    str(OUT / "Rc3ProtocolProbe.java"), str(ROOT / "tools/WebDavSyncContract.java"),
], check=True)
subprocess.run([
    str(JAVA / "java.exe"), "-cp", str(SDK / "build-tools/36.0.0/lib/d8.jar"),
    "com.android.tools.r8.D8", "--min-api", "26", "--output", str(OUT),
    *map(str, OUT.glob("*.class")),
], check=True)
with zipfile.ZipFile(OUT / "probe.jar", "w") as archive:
    archive.write(OUT / "classes.dex", "classes.dex")


def adb(*args):
    return subprocess.check_output(["adb", *args])


adb("shell", "mkdir", "-p", REMOTE)
adb("push", str(OUT / "probe.jar"), REMOTE + "/probe.jar")
adb("push", str(ROOT / "app/build/outputs/apk/release/app-release.apk"), REMOTE + "/module.apk")
adb("reverse", "tcp:18791", "tcp:18791")
host = adb("shell", "pm", "path", "app.zhendong.reamicro").decode().strip().removeprefix("package:")
command = (f"CLASSPATH={REMOTE}/probe.jar app_process /system/bin WebDavSyncContract "
           f"{host} {REMOTE}/module.apk {REMOTE} http://127.0.0.1:18791/dav")
with (OUT / "result.log").open("wb") as log:
    result = subprocess.run(["adb", "exec-out", "su", "-c", command],
                            stdout=log, stderr=subprocess.STDOUT, timeout=180)
text = (OUT / "result.log").read_text(encoding="utf-8", errors="replace")
print("\n".join(line for line in text.splitlines() if any(x in line for x in
      ("PASS", "FAIL", "Exception", "Caused by", "AssertionError", "Error:"))))
if "WEBDAV_SYNC_FAILURES=0" not in text:
    raise SystemExit(1)
raise SystemExit(result.returncode)
