#!/usr/bin/env python3
"""Build the root wake helper in a temporary directory with the installed clang."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile


def verify_static_elf(path: Path, require_arm64: bool = True) -> None:
    data = path.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("wake-timer must be a little-endian ELF64 executable")
    machine = struct.unpack_from("<H", data, 18)[0]
    if require_arm64 and machine != 183:
        raise ValueError(f"wake-timer must target Android arm64, ELF machine={machine}")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize, phcount = struct.unpack_from("<HH", data, 54)
    types = [struct.unpack_from("<I", data, phoff + i * phsize)[0] for i in range(phcount)]
    if 2 in types or 3 in types:
        raise ValueError("wake-timer must have no dynamic dependencies or interpreter")


def compile_helper(output: Path, *, testing: bool = False, no_alarm: bool = False,
                   boottime: bool = False) -> None:
    root = Path(__file__).resolve().parents[1]
    clang = os.environ.get("CC") or shutil.which("clang")
    if not clang:
        raise RuntimeError("clang is required; on Termux install the clang package")
    command = [
        clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-fno-builtin", "-fno-stack-protector", "-ffunction-sections", "-fdata-sections",
        "-nostdlib", "-static", "-Wl,-e,_start", "-Wl,--gc-sections", "-Wl,--build-id=none",
    ]
    if testing:
        command.append("-DREAMICRO_TIMER_TEST=1")
        if no_alarm:
            command.append("-DREAMICRO_TEST_NO_ALARM=1")
        if boottime:
            command.append("-DREAMICRO_TEST_BOOTTIME=1")
    else:
        command.append("--target=aarch64-linux-android24")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(command + [str(root / "tools/native/wake-timer.c"), "-o", str(output)], check=True)
    verify_static_elf(output, require_arm64=not testing)
    output.chmod(0o755)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=root / "module/reamicro-automation/bin/wake-timer")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="reamicro-wake-build.") as directory:
        artifact = Path(directory) / "wake-timer"
        compile_helper(artifact)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(artifact, args.output)
        args.output.chmod(0o755)
    print(f"Static Android arm64 wake helper: {args.output}")


if __name__ == "__main__":
    main()
