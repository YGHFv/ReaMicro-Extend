#!/usr/bin/env python3
"""按已验证的宿主 HTTP 类型生成流式 DEX，编译用 stub 不进入最终资产。"""

import argparse
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile
import zlib

from androguard.core.dex import DEX
from loguru import logger

import retarget_profile as rp

logger.remove()
CLASS = "ReaMicroStreamingRequestBody"
JAVA = Path("C:/Program Files/Android/Android Studio1/jbr")
SDK = Path("C:/Users/YGHF/AppData/Local/Android/Sdk")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True)
    args = parser.parse_args()
    profile = rp.parse_profile(args.profile)
    profile_id = next(line.partition("=")[2] for line in profile["header"] if line.startswith("profile="))
    if profile_id not in ("2.3.3-b2", "2.3.3-b4", "2.3.3-rc3", "2.3.3"):
        raise ValueError("HTTP 载体协议尚未对该宿主取证")
    classes = profile["classes"] | profile["typeAliases"]
    body, media, sink = [
        classes["ReaMicroHost.Http" + role] for role in ("RequestBody", "MediaType", "BufferedSink")
    ]
    if any("." in name for name in (body, media, sink)):
        raise ValueError("该载体生成器只接受已核实的根包宿主类型")
    with tempfile.TemporaryDirectory(prefix="reamicro-http-carrier-") as temp:
        work = Path(temp)
        sources = {
            body: f"public abstract class {body} {{ public {body}() {{}} "
                  f"public abstract long a(); public abstract {media} b(); "
                  f"public abstract void e({sink} value) throws java.io.IOException; }}",
            media: f"public final class {media} {{}}",
            sink: f"public interface {sink} {{ {sink} write(byte[] bytes, int start, int count) throws java.io.IOException; }}",
            CLASS: f"""import java.io.*;
public final class {CLASS} extends {body} {{
    private final File file;
    private final {media} media;
    public {CLASS}(File file, {media} media) {{ this.file = file; this.media = media; }}
    public long a() {{ return file.length(); }}
    public {media} b() {{ return media; }}
    public static Object linkageMarker() {{ return new Object(); }}
    public void e({sink} sink) throws IOException {{
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {{
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) sink.write(buffer, 0, count);
        }}
    }}
}}""",
        }
        for name, source in sources.items():
            (work / f"{name}.java").write_text(source + "\n", encoding="utf-8", newline="\n")
        subprocess.run(
            [str(JAVA / "bin/javac.exe"), "-encoding", "UTF-8", "--release", "8",
             *map(str, work.glob("*.java"))], check=True,
        )
        output = work / "dex"
        output.mkdir()
        subprocess.run(
            [str(JAVA / "bin/java.exe"), "-cp", str(SDK / "build-tools/36.0.0/lib/d8.jar"),
             "com.android.tools.r8.D8", "--min-api", "26", "--output", str(output),
             str(work / f"{CLASS}.class")], check=True,
        )
        data = bytearray((output / "classes.dex").read_bytes())
        dex = DEX(bytes(data))
        methods = list(dex.get_methods())
        old = next(i for i, method in enumerate(methods)
                   if method.get_class_name() == f"L{body};" and method.get_name() == "<init>")
        parent = next(i for i, method in enumerate(methods)
                      if method.get_class_name() == "Ljava/lang/Object;" and method.get_name() == "<init>")
        constructor = next(method for method in dex.get_class(f"L{CLASS};").get_methods()
                           if method.get_name() == "<init>")
        # 两版宿主均删除 RequestBody 空构造器，真实子类直接调用 Object 构造器。
        offset, patches = constructor.get_code_off() + 16, 0
        for instruction in constructor.get_instructions():
            if instruction.get_name() == "invoke-direct" and struct.unpack_from("<H", data, offset + 2)[0] == old:
                struct.pack_into("<H", data, offset + 2, parent)
                patches += 1
            offset += instruction.get_length()
        assert patches == 1
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
        assert [cls.get_name() for cls in DEX(bytes(data)).get_classes()] == [f"L{CLASS};"]
        target = Path("app/src/main/assets/host-bridges") / f"http-{profile_id}.dex"
        target.write_bytes(data)
        print(target, len(data), hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
