"""Publish the exact Root ZIP bundled in every APK; fail closed on drift.

Usage: python3 tools/export-bundled-module.py --output artifacts debug.apk release.apk
Temporary comparison ZIPs are created in the system temp directory, not the source tree.
"""
import argparse
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import zipfile


def load_builder():
    spec = importlib.util.spec_from_file_location(
        "reamicro_module_builder", Path(__file__).with_name("build-module.py")
    )
    module = importlib.util.module_from_spec(spec)
    # Avoid __pycache__ in the source tree.
    exec(compile(Path(spec.origin).read_bytes(), spec.origin, "exec"), module.__dict__)
    return module


def contents(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt Root module ZIP")
        entries = {}
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            if entry.filename in entries:
                raise ValueError(f"Duplicate module entry: {entry.filename}")
            entries[entry.filename] = (
                archive.read(entry), (entry.external_attr >> 16) & 0o777
            )
        return entries


def export(apks, output):
    builder = load_builder()
    with tempfile.TemporaryDirectory(prefix="reamicro-module-check-") as directory:
        reference = builder.build_module(output_directory=Path(directory))
        filename = reference.name
        expected = contents(reference.read_bytes())
    bundled = None
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            candidates = [
                entry for entry in archive.infolist()
                if entry.filename.startswith("assets/module/") and entry.filename.endswith(".zip")
            ]
            if len(candidates) != 1 or candidates[0].filename != f"assets/module/{filename}":
                raise ValueError(f"{apk}: missing, stale or multiple bundled module ZIPs")
            data = archive.read(candidates[0])
        if contents(data) != expected:
            raise ValueError(f"{apk}: module file list, contents or Unix permissions differ from source")
        if bundled is not None and data != bundled:
            raise ValueError(f"{apk}: debug/release bundled ZIP bytes differ")
        bundled = data
    if bundled is None:
        raise ValueError("No APKs provided")
    output.mkdir(parents=True, exist_ok=True)
    destination = output / filename
    destination.write_bytes(bundled)
    print(f"Verified and exported {destination}")
    print(f"SHA-256: {hashlib.sha256(bundled).hexdigest()}")
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("apks", type=Path, nargs="+")
    args = parser.parse_args()
    export(args.apks, args.output)
