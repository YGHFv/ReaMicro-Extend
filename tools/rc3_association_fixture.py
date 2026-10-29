#!/usr/bin/env python3
"""复用已检查的测试书复制器，仅从本轮 ID 51 创建独立关联测试记录。"""

import subprocess
import time
import zipfile
from pathlib import Path
from rc3_full_audit import OUT, ROOT, adb

out = OUT / "continue-association-fixture"
out.mkdir(exist_ok=False)
source = (ROOT/"tools/B4AssociationFixture.java").read_text(encoding="utf-8")
for old, new in (
    ("RM-B4-Association-QA-20261026", "RC3 Audit Fixture Association"),
    ("id=133 AND title='RM-B4-WebDAV-QA-20261025'", "id=51 AND title='RC3 Audit Fixture'"),
    ("WHERE book_id=133", "WHERE book_id=51"),
):
    assert old in source
    source = source.replace(old, new)
java = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
sdk = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
src = out/"B4AssociationFixture.java"
src.write_text(source, encoding="utf-8")
subprocess.run([str(java/"javac.exe"), "-encoding", "UTF-8", "--release", "8", "-cp",
                str(sdk/"platforms/android-33/android.jar"), "-d", str(out), str(src)],
               check=True, capture_output=True)
subprocess.run([str(java/"java.exe"), "-cp", str(sdk/"build-tools/36.0.0/lib/d8.jar"),
                "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
                str(out/"B4AssociationFixture.class")], check=True, capture_output=True)
jar = out/"fixture.jar"
with zipfile.ZipFile(jar, "x") as archive:
    archive.writestr("classes.dex", (out/"classes.dex").read_bytes())
assert adb("shell", "pidof", "bin.mt.plus").strip() == "18580"
remote = "/data/local/tmp/reamicro-rc3-association-fixture.jar"
adb("push", str(jar), remote)
adb("shell", "am", "force-stop", "app.zhendong.reamicro")
try:
    result = adb("exec-out", "su", "-c",
        "CLASSPATH="+remote+" app_process /system/bin B4AssociationFixture create")
    (out/"create.log").write_text(result, encoding="utf-8")
    assert "Created task-only association fixture" in result
    print(result)
finally:
    adb("shell", "am", "start", "-n", "app.zhendong.reamicro/.MainActivity")
    time.sleep(5)
    assert adb("shell", "pidof", "bin.mt.plus").strip() == "18580"
