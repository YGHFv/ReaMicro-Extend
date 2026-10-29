#!/usr/bin/env python3
"""在隔离 app_process 中跑生产功能回归，原始失败保留，不触碰宿主用户数据。"""

import argparse
from pathlib import Path
import subprocess
import zipfile

from rc3_full_audit import ROOT, OUT, adb

parser = argparse.ArgumentParser()
parser.add_argument("label")
parser.add_argument("--http",action="store_true")
options = parser.parse_args()
assert options.label.replace("-", "").isalnum()
out = OUT / options.label
out.mkdir(parents=True, exist_ok=False)
java = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
sdk = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
subprocess.run([str(java/"javac.exe"), "-encoding", "UTF-8", "--release", "8", "-cp",
                str(sdk/"platforms/android-33/android.jar"), "-d", str(out),
                str(ROOT/"tools/Rc3ProtocolProbe.java"), str(ROOT/"tools/Rc3FeatureProbe.java")], check=True)
subprocess.run([str(java/"java.exe"), "-cp", str(sdk/"build-tools/36.0.0/lib/d8.jar"),
                "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
                *map(str,out.glob("*.class"))], check=True)
jar=out/"probe.jar"
with zipfile.ZipFile(jar,"x") as archive:
    archive.writestr("classes.dex",(out/"classes.dex").read_bytes())
remote="/data/local/tmp/reamicro-full-audit-"+options.label
adb("shell","mkdir",remote)
adb("push",str(jar),remote+"/probe.jar")
adb("push",str(ROOT/"app/build/outputs/apk/release/app-release.apk"),remote+"/module.apk")
epub=out/"epub"
(epub/"META-INF").mkdir(parents=True)
(epub/"OEBPS").mkdir()
(epub/"META-INF/container.xml").write_text(
    '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
    '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>'
    '</rootfiles></container>',encoding="utf-8")
(epub/"OEBPS/content.opf").write_text(
    '<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">'
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">full-audit-fixture</dc:identifier>'
    '<dc:title>RC3 Audit Fixture</dc:title><dc:creator>Fixture Author</dc:creator><dc:publisher>Fixture Publisher</dc:publisher>'
    '<dc:language>en</dc:language><meta property="dcterms:modified">2026-10-29T00:00:00Z</meta></metadata>'
    '<manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest>'
    '<spine><itemref idref="one"/></spine></package>',encoding="utf-8")
(epub/"OEBPS/one.xhtml").write_text(
    '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head>'
    '<body><p>FullAuditNeedle. Independent test content.</p></body></html>',encoding="utf-8")
adb("push",str(epub),remote+"/epub")
host=adb("shell","pm","path","app.zhendong.reamicro").strip().removeprefix("package:")
command="CLASSPATH="+remote+"/probe.jar app_process /system/bin Rc3FeatureProbe "+host+" "+remote+"/module.apk "+remote
if options.http:
    command+=" http"
with (out/"result.log").open("xb") as log:
    result=subprocess.run(["adb","-s","deb497bd","exec-out","su","-c",command],
                          stdout=log,stderr=subprocess.STDOUT,timeout=180)
text=(out/"result.log").read_text(encoding="utf-8",errors="replace")
print("\n".join(line for line in text.splitlines()
                if any(key in line for key in ("PASS","FAIL","Exception","Caused by:"))))
raise SystemExit(result.returncode)
