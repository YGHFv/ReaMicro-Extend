#!/usr/bin/env python3
"""编译并运行只读测试书回读器，不复制或改写用户数据库。"""

import argparse
import json
import subprocess
import zipfile
from pathlib import Path
from rc3_full_audit import OUT, ROOT, adb

parser = argparse.ArgumentParser()
parser.add_argument("label")
args = parser.parse_args()
assert args.label.replace("-", "").isalnum()
out = OUT / args.label
out.mkdir(exist_ok=False)
java = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
sdk = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
subprocess.run([str(java/"javac.exe"), "-encoding", "UTF-8", "--release", "8", "-cp",
                str(sdk/"platforms/android-33/android.jar"), "-d", str(out),
                str(ROOT/"tools/Rc3AcceptanceProbe.java")], check=True, capture_output=True)
subprocess.run([str(java/"java.exe"), "-cp", str(sdk/"build-tools/36.0.0/lib/d8.jar"),
                "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
                str(out/"Rc3AcceptanceProbe.class")], check=True, capture_output=True)
jar = out/"probe.jar"
with zipfile.ZipFile(jar, "x") as archive:
    archive.writestr("classes.dex", (out/"classes.dex").read_bytes())
remote = "/data/local/tmp/reamicro-rc3-read-fixture.jar"
adb("push", str(jar), remote)
data = adb("exec-out", "su", "-c",
           "CLASSPATH="+remote+" app_process /system/bin Rc3AcceptanceProbe")
rows = json.loads(data)
(out/"result.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(rows, ensure_ascii=False))
