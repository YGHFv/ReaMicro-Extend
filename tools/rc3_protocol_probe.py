#!/usr/bin/env python3
"""运行不访问用户网盘或书库的 RC3 原生协议门禁。"""

import subprocess
from pathlib import Path
import zipfile
import sys
from rc3_retarget import ROOT, OUT
from rc3_runtime import adb

label = sys.argv[1]
assert label.replace("-", "").isalnum()
out = OUT / label
out.mkdir(exist_ok=False)
java = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
sdk = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "--release", "8", "-cp",
                str(sdk / "platforms/android-33/android.jar"), "-d", str(out),
                str(ROOT / "tools/Rc3ProtocolProbe.java")], check=True)
subprocess.run([str(java / "java.exe"), "-cp", str(sdk / "build-tools/36.0.0/lib/d8.jar"),
                "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
                str(out / "Rc3ProtocolProbe.class")], check=True)
jar = out / "probe.jar"
with zipfile.ZipFile(jar, "x") as z:
    z.writestr("classes.dex", (out / "classes.dex").read_bytes())
remote = "/data/local/tmp/reamicro-rc3-" + label
adb("shell", "mkdir", remote)
adb("push", str(jar), remote + "/probe.jar")
adb("push", str(ROOT / "app/build/outputs/apk/release/app-release.apk"), remote + "/module.apk")
host = adb("shell", "pm", "path", "app.zhendong.reamicro").decode().strip().removeprefix("package:")
command = "CLASSPATH=" + remote + "/probe.jar app_process /system/bin Rc3ProtocolProbe " + host + " " + remote + "/module.apk " + remote
with (out / "result.log").open("xb") as log:
    result = subprocess.run(["adb", "-s", "deb497bd", "exec-out", "su", "-c", command],
                            stdout=log, stderr=subprocess.STDOUT, timeout=180)
text = (out / "result.log").read_text(encoding="utf-8", errors="replace")
print("\n".join(line for line in text.splitlines() if "PASS" in line or "FAIL" in line or "Exception" in line or "Caused" in line))
raise SystemExit(result.returncode)
