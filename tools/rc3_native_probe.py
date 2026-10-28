#!/usr/bin/env python3
"""把既有隔离回归 runner 按精确 ABI 表重定位到 RC3，不启动宿主 Application。"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

from rc3_retarget import ROOT, OUT
import retarget_profile as rp

JAVA = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
SDK = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")


def adb(*args):
    return subprocess.check_output(["adb", "-s", "deb497bd", *args])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    options = parser.parse_args()
    assert re.fullmatch(r"[a-z0-9-]+", options.label)
    out = OUT / options.label
    out.mkdir(exist_ok=False)
    source = (ROOT / "tools/B4RemainingMigrationProbe.java").read_text(encoding="utf-8")
    graph = json.loads((OUT / "graph-retarget.json").read_text(encoding="utf-8"))
    source = re.sub(r'host.loadClass\("([^"]+)"\)', lambda m: 'host.loadClass("' +
                    graph["mapping"].get(m[1], m[1]) + '")', source)
    source = source.replace("2.3.3-b4.profile", "2.3.3-rc3.profile")
    # 给生产 asset 读取提供本测试模块路径，不初始化 Application。
    source = source.replace("List<String> failures = new ArrayList<>();",
        'Class<?> injected = module.loadClass("com.reamicro.fix.core.InjectedModuleContext");\n'
        'android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo();\n'
        'info.sourceDir = args[1]; info.publicSourceDir = args[1];\n'
        'invoke(injected, "configure", field(injected, "INSTANCE", null), '
        'new Class<?>[]{android.content.pm.ApplicationInfo.class}, info);\n'
        'List<String> failures = new ArrayList<>();')
    src = out / "B4RemainingMigrationProbe.java"
    src.write_text(source, encoding="utf-8")
    subprocess.run([str(JAVA / "javac.exe"), "-encoding", "UTF-8", "--release", "8",
                    "-cp", str(SDK / "platforms/android-33/android.jar"), "-d", str(out), str(src)], check=True)
    subprocess.run([str(JAVA / "java.exe"), "-cp", str(SDK / "build-tools/36.0.0/lib/d8.jar"),
                    "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
                    *map(str, out.glob("B4RemainingMigrationProbe*.class"))], check=True)
    jar = out / "probe.jar"
    with zipfile.ZipFile(jar, "x") as archive:
        archive.writestr("classes.dex", (out / "classes.dex").read_bytes())
    remote = "/data/local/tmp/reamicro-remaining-" + options.label
    adb("shell", "mkdir", remote)
    adb("push", str(jar), remote + "/probe.jar")
    for name, text in {
        "first.css": ":root { --accent: #ff0000; }\np { color: var(--accent); font-size: 18px; }\n",
        "second.css": "h1 { color: #0000ff; }\n",
        "sample.svg": '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60" viewBox="0 0 120 60"><rect width="120" height="60" fill="#ff0000"/></svg>\n',
    }.items():
        path = out / name
        path.write_text(text, encoding="utf-8")
        adb("push", str(path), remote + "/" + name)
    adb("push", str(ROOT / "app/build/outputs/apk/release/app-release.apk"), remote + "/module.apk")
    host = adb("shell", "pm", "path", "app.zhendong.reamicro").decode().strip().removeprefix("package:")
    assert re.fullmatch(r"/data/app/[\w/~+=.-]+/base\.apk", host)
    command = "CLASSPATH=" + remote + "/probe.jar app_process /system/bin B4RemainingMigrationProbe " + host + " " + remote + "/module.apk " + remote
    log = out / "native-probe.log"
    with log.open("xb") as stream:
        result = subprocess.run(["adb", "-s", "deb497bd", "exec-out", "su", "-c", command],
                                stdout=stream, stderr=subprocess.STDOUT, timeout=300)
    text = log.read_text(encoding="utf-8", errors="replace")
    print("\n".join(line for line in text.splitlines() if "PASS" in line or "FAIL" in line or "Caused by:" in line))
    print("Evidence", log, "exit", result.returncode)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
