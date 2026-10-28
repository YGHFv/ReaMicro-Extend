#!/usr/bin/env python3
"""根据已确认协程符号生成极小 DEX，不把编译用的宿主 stub 打进资产。"""

import argparse
import os
import subprocess
import tempfile
from pathlib import Path

from b2_probe import profile_classes

CLASS_NAME = "ReaMicroContinuationCarrier"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--profile", type=Path, default=Path("app/src/main/assets/host-profiles/2.3.3-b2.profile"))
    p.add_argument("--sdk", type=Path, default=Path("C:/Users/YGHF/AppData/Local/Android/Sdk"))
    p.add_argument("--java-home", type=Path, default=Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-17")))
    args = p.parse_args()
    text = args.profile.read_text(encoding="utf-8")
    classes = profile_classes(args.profile)
    continuation = classes["kotlin.coroutines.Continuation"]
    parent = classes["kotlin.coroutines.jvm.internal.SuspendLambda"]
    resume = next(line.rpartition("=")[2] for line in text.splitlines()
                  if line.startswith("method=kotlin.coroutines.jvm.internal.BaseContinuationImpl#invokeSuspend="))
    profile_id = next(line[8:] for line in text.splitlines() if line.startswith("profile="))
    # 当前 B2 的协程符号都是根包名；其他形态不能悄悄生成错误的 Java 引用。
    if "." in continuation or "." in parent:
        raise ValueError("该生成器需要根包混淆类名")
    with tempfile.TemporaryDirectory(prefix="reamicro-carrier-") as temp:
        work = Path(temp)
        (work / f"{continuation}.java").write_text(f"public interface {continuation} {{}}\n", encoding="utf-8")
        (work / f"{parent}.java").write_text(
            f"public abstract class {parent} {{ public {parent}(int a, {continuation} c) {{}} "
            f"public abstract Object {resume}(Object result); }}\n", encoding="utf-8")
        (work / f"{CLASS_NAME}.java").write_text(
            f"public final class {CLASS_NAME} extends {parent} {{ "
            f"public {CLASS_NAME}({continuation} completion) {{ super(2, completion); }} "
            f"@Override public Object {resume}(Object result) {{ return result; }} }}\n", encoding="utf-8")
        subprocess.run([str(args.java_home / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8",
                        *[str(p) for p in work.glob("*.java")]], check=True)
        out = work / "dex"
        out.mkdir()
        d8 = args.sdk / "build-tools/36.0.0/lib/d8.jar"
        subprocess.run([str(args.java_home / "bin/java.exe"), "-cp", str(d8), "com.android.tools.r8.D8", "--min-api", "26",
                        "--output", str(out), str(work / f"{CLASS_NAME}.class")], check=True)
        target = Path("app/src/main/assets/host-bridges") / f"{profile_id}.dex"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((out / "classes.dex").read_bytes())
        print(target, target.stat().st_size)


if __name__ == "__main__":
    main()
