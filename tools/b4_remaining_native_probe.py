#!/usr/bin/env python3
"""统一构建完成后，在隔离 app_process 中验收原生预览，不写入书库或设置。"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
SDK = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
SERIAL = os.environ.get("B4_ADB_SERIAL", "deb497bd")


def adb(*args):
    return subprocess.check_output(["adb", "-s", SERIAL, *args])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]+", args.label):
        raise ValueError("非法证据标签")
    out = Path(os.environ["TEMP"]) / "reamicro-analysis/runtime/b4" / args.label
    out.mkdir(exist_ok=False)
    subprocess.run([
        str(JAVA / "javac.exe"), "-encoding", "UTF-8", "--release", "8",
        "-cp", str(SDK / "platforms/android-33/android.jar"), "-d", str(out),
        str(ROOT / "tools/B4RemainingMigrationProbe.java"),
    ], check=True)
    subprocess.run([
        str(JAVA / "java.exe"), "-cp", str(SDK / "build-tools/36.0.0/lib/d8.jar"),
        "com.android.tools.r8.D8", "--min-api", "26", "--output", str(out),
        *map(str, out.glob("B4RemainingMigrationProbe*.class")),
    ], check=True)
    jar = out / "probe.jar"
    with zipfile.ZipFile(jar, "x") as archive:
        archive.writestr("classes.dex", (out / "classes.dex").read_bytes())
    # 专用目录仅在 /data/local/tmp 内创建，不修改用户书籍、数据库或持久化设置。
    remote = "/data/local/tmp/reamicro-remaining-" + args.label
    local = out / "fixture"
    local.mkdir()
    (local / "first.css").write_text(
        ":root { --accent: #ff0000; }\np { color: var(--accent); font-size: 18px; }\n",
        encoding="utf-8", newline="\n",
    )
    (local / "second.css").write_text("h1 { color: #0000ff; }\n", encoding="utf-8", newline="\n")
    (local / "sample.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60" viewBox="0 0 120 60">'
        '<rect width="120" height="60" fill="#ff0000"/></svg>\n',
        encoding="utf-8", newline="\n",
    )
    adb("shell", "mkdir", remote)
    adb("push", str(jar), remote + "/probe.jar")
    for path in local.iterdir():
        adb("push", str(path), remote + "/" + path.name)
    # 本地 release 直接复制到专用目录，不替换宿主或 MT；测试进程与应用进程完全隔离。
    module = ROOT / "app/build/outputs/apk/release/app-release.apk"
    adb("push", str(module), remote + "/module.apk")
    paths = adb("shell", "pm", "path", "app.zhendong.reamicro").decode("utf-8").splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
        raise ValueError("无法确定唯一宿主 APK")
    host = paths[0].removeprefix("package:").strip()
    if not re.fullmatch(r"/data/app/[\w/~+=.-]+/base\.apk", host):
        raise ValueError("意外宿主 APK 路径")
    before = {
        "hostPid": adb("shell", "pidof", "app.zhendong.reamicro").decode("utf-8").strip(),
        "mtPid": adb("shell", "pidof", "bin.mt.plus").decode("utf-8").strip(),
        "uptime": adb("shell", "cat", "/proc/uptime").decode("utf-8").strip(),
    }
    command = (
        "CLASSPATH=" + remote + "/probe.jar app_process /system/bin "
        "B4RemainingMigrationProbe " + host + " " + remote + "/module.apk " + remote
    )
    log = out / "native-probe.log"
    with log.open("xb") as stream:
        result = subprocess.run(
            ["adb", "-s", SERIAL, "exec-out", "su", "-c", command],
            stdout=stream, stderr=subprocess.STDOUT, timeout=300,
        )
    text = log.read_text(encoding="utf-8", errors="replace")
    after = {
        "hostPid": adb("shell", "pidof", "app.zhendong.reamicro").decode("utf-8").strip(),
        "mtPid": adb("shell", "pidof", "bin.mt.plus").decode("utf-8").strip(),
        "uptime": adb("shell", "cat", "/proc/uptime").decode("utf-8").strip(),
    }
    (out / "probe-metadata.json").write_text(
        json.dumps({"before": before, "after": after, "exitCode": result.returncode,
                    "pass": "REMAINING_NATIVE_PROBE_PASS" in text}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(text)
    print("Evidence:", out)
    if result.returncode or "REMAINING_NATIVE_PROBE_PASS" not in text:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
