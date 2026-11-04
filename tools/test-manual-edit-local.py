"""本机保存手动源契约证据，设备独立进程不访问用户数据库。"""

from pathlib import Path
import os
import subprocess
import zipfile
import sys
import b4_device as device

root = Path(__file__).resolve().parents[1]
label = sys.argv[1] if len(sys.argv) > 1 else "contracts"
assert label.replace("-", "").isalnum()
out = Path(os.environ["TEMP"]) / "reamicro-analysis/runtime/manual-edit-233-local" / label
out.mkdir(exist_ok=False)
source = (root / "tools/Rc3ProtocolProbe.java").read_text(encoding="utf-8").replace(
    "host-profiles/2.3.3-rc3.profile", "host-profiles/2.3.3.profile")
(out / "Rc3ProtocolProbe.java").write_text(source, encoding="utf-8")
java = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
sdk = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "--release", "8",
    "-cp", str(sdk / "platforms/android-33/android.jar"), "-d", str(out),
    str(out / "Rc3ProtocolProbe.java"), str(root / "tools/Manual233SourceContract.java")], check=True, capture_output=True)
subprocess.run([str(java / "java.exe"), "-cp", str(sdk / "build-tools/36.0.0/lib/d8.jar"),
    "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
    *map(str, out.glob("*.class"))], check=True, capture_output=True)
with zipfile.ZipFile(out / "test.jar", "x") as archive:
    archive.writestr("classes.dex", (out / "classes.dex").read_bytes())
remote = "/data/local/tmp/reamicro-manual233-contract"
device.adb("shell", "mkdir", "-p", remote)
for local, name in [(out / "test.jar", "test.jar"), (root / "app/build/outputs/apk/release/app-release.apk", "module.apk"),
                    (root / "source-files/manual-edit.rmsource", "source.rmsource")]:
    device.adb("push", str(local), remote + "/" + name)
host = device.adb("shell", "pm", "path", device.PACKAGE).decode().strip().removeprefix("package:")
data = device.adb("exec-out", "su", "-c", "CLASSPATH=" + remote +
    "/test.jar app_process /system/bin Manual233SourceContract " + host + " " +
    remote + "/module.apk " + remote + " " + remote + "/source.rmsource")
(out / "result.log").write_bytes(data)
print(data.decode("utf-8", errors="replace")[-3500:])
assert b"MANUAL_SOURCE_FAILURES=0" in data
