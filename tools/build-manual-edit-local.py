"""只重建本地手动编辑功能源，不重建其他源或更改发布配置。"""

from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--version", default="233-local-1")
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.version):
    parser.error("Invalid source version")
ROOT = Path(__file__).resolve().parents[1]
BUILD_ROOT = ROOT / "external-sources/build/manual-edit-local"
BUILD_ROOT.mkdir(parents=True, exist_ok=True)
# 每次使用全新输出目录，避免已删除的旧 class 被误打入功能源。
OUT = Path(tempfile.mkdtemp(prefix=args.version + "-", dir=BUILD_ROOT))
classes, dex = OUT / "classes", OUT / "dex"
classes.mkdir(exist_ok=True)
dex.mkdir(exist_ok=True)
java = Path("C:/Program Files/Android/Android Studio1/jbr/bin")
sdk = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")
cache = Path("C:/Users/YGHF/.gradle/caches/modules-2/files-2.1")
module = ROOT / "app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar"
android = sdk / "platforms/android-37.0/android.jar"
aar = next((cache / "io.github.libxposed/api/102.0.0").rglob("*.aar"))
with zipfile.ZipFile(aar) as archive:
    (OUT / "libxposed.jar").write_bytes(archive.read("classes.jar"))


def jar(group, name, version):
    return next((cache / group / name / version).rglob("*.jar"))


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20")
compiler = [jar("org.jetbrains.kotlin", name, "2.4.20") for name in (
    "kotlin-compiler-embeddable", "kotlin-build-tools-api", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [stdlib, jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
             next((cache / "org.jetbrains/annotations").rglob("*.jar"))]
cp = os.pathsep.join(map(str, [module, android, stdlib, OUT / "libxposed.jar"]))
sources = sorted((ROOT / "external-sources/src/main/kotlin").rglob("*.kt"))
result = subprocess.run([str(java / "java.exe"), "-cp", os.pathsep.join(map(str, compiler)),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17", "-no-stdlib", "-no-reflect",
    "-classpath", cp, "-d", str(classes), *map(str, sources)], capture_output=True)
(OUT / "kotlin.log").write_bytes(result.stdout + result.stderr)
if result.returncode:
    print((result.stdout + result.stderr).decode("utf-8", errors="replace")[-9000:])
    raise SystemExit(result.returncode)
provider = ROOT / "external-sources/src/main/java/com/reamicro/fix/external/source/ManualEditFeatureProvider.java"
result = subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "--release", "17",
    "-classpath", str(classes) + os.pathsep + cp, "-d", str(classes), str(provider)], capture_output=True)
(OUT / "java.log").write_bytes(result.stdout + result.stderr)
if result.returncode:
    print((result.stdout + result.stderr).decode("utf-8", errors="replace")[-4000:])
    raise SystemExit(result.returncode)
d8args = [str(java / "java.exe"), "-cp", str(sdk / "build-tools/36.0.0/lib/d8.jar"),
          "com.android.tools.r8.D8", "--min-api", "26", "--lib", str(android)]
for path in [module, stdlib, OUT / "libxposed.jar"]:
    d8args += ["--classpath", str(path)]
result = subprocess.run(d8args + ["--output", str(dex), *map(str, classes.rglob("*.class"))],
                        capture_output=True)
(OUT / "d8.log").write_bytes(result.stdout + result.stderr)
if result.returncode:
    print((result.stdout + result.stderr).decode("utf-8", errors="replace")[-4000:])
    raise SystemExit(result.returncode)
target = ROOT / "source-files/manual-edit.rmsource"
manifest = {"apiVersion": 1, "id": "manual-edit", "name": "ManualEdit",
            "entryClass": "com.reamicro.fix.external.source.ManualEditFeatureProvider",
            "version": args.version}
temp = target.with_suffix(".rmsource.tmp")
with zipfile.ZipFile(temp, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("classes.dex", (dex / "classes.dex").read_bytes())
    archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False))
temp.replace(target)
print("Local manual source rebuilt", hashlib.sha256(target.read_bytes()).hexdigest())
print("Build logs", OUT)
