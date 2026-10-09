"""使用本机 Gradle 依赖缓存编译生产兼容代码，运行不依赖设备的回归检查。"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile


root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"


def jar(group, name, version=None):
    directory = cache / group / name
    if version:
        directory /= version
    return next(directory.rglob("*.jar"))


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.20")
runtime = [stdlib, jar("org.json", "json", "20240303")]
compiler = [jar("org.jetbrains.kotlin", name, "2.4.20") for name in (
    "kotlin-compiler-embeddable", "kotlin-build-tools-api", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [stdlib, jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), jar("org.jetbrains", "annotations")]
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if os.environ.get("JAVA_HOME") else shutil.which("java")
sources = [root / "app/src/main/java/com/reamicro/fix/hook" / name for name in (
    "ReaderHostSignatures.kt", "ReaderMarginsSnapshot.kt", "ReaderBottomBarComposeScope.kt")]
sources += [root / "app/src/main/java/com/reamicro/fix/online/download/OnlineChapterUpdateMessage.kt"]
sources += sorted((root / "tools/compat-tests").glob("*.kt"))

# 配置重建曾导致新版构造器异常；映射必须留在 FontProvider，不重新引入该依赖。
font_source = (root / "app/src/main/java/com/reamicro/fix/hook/ReaderFontCompletionHook.kt").read_text(encoding="utf-8")
assert "ReaderEpubConfig" not in font_source and "PagerInput" not in font_source
assert '"withName"' in font_source and '"getBuildInFonts"' in font_source
build = root / "build"
build.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="reader-compat-", dir=build) as directory:
    output = Path(directory) / "regression.jar"
    subprocess.run([java, "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-no-stdlib", "-no-reflect", "-classpath", os.pathsep.join(map(str, runtime)),
                    "-d", str(output), *map(str, sources)], check=True, timeout=120)
    subprocess.run([java, "-cp", os.pathsep.join(map(str, [output, *runtime])), "ReaderHostCompatRegressionKt"],
                   check=True, timeout=30)
