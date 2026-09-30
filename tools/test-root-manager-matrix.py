"""Run one identical sandbox suite with all three real ARM64 BusyBox binaries.
Does not invoke su, managers, app_process, Gradle, or device installation.
The Magisk binary must first be extracted from a digest-verified official release.
"""
from pathlib import Path
import ast
import hashlib
import json
import os
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "outputs/root-source-review"
SUITE = ROOT / "tools/test-module-lifecycle.py"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    provenance = json.loads((OUT / "magisk-busybox-provenance.json").read_text())
    binaries = {
        "ksu": ROOT.parent / "KernelSU-main/userspace/ksud/bin/aarch64/busybox",
        "apatch": ROOT.parent / "APatch-main/app/libs/arm64-v8a/libbusybox.so",
        "magisk": Path(provenance["binary"]),
    }
    if not provenance["github_digest_verified"] or sha(binaries["magisk"]) != provenance["binary_sha256"]:
        raise RuntimeError("Magisk binary provenance/digest mismatch")
    expected = sum(isinstance(n, ast.FunctionDef) and n.name.startswith("test_")
                   for n in ast.walk(ast.parse(SUITE.read_text())))
    files = [SUITE, *sorted((ROOT / "module/reamicro-automation").rglob("*"))]
    hashes = {str(p.relative_to(ROOT)): sha(p) for p in files if p.is_file()}
    report = {"scope": "real BusyBox + sandbox module scripts; NOT installed-manager/device validation",
              "expected_tests_per_provider": expected, "source_sha256": hashes, "providers": {},
              "apk_built": False, "installed": False, "device_manager_tests": False}
    for provider, binary in binaries.items():
        env = os.environ.copy()
        env["REAMICRO_TEST_BUSYBOX"] = str(binary)
        start = time.monotonic()
        result = subprocess.run([sys.executable, str(SUITE), "-v"], env=env,
                                capture_output=True, text=True, timeout=1200)
        log = result.stdout + result.stderr
        (OUT / f"{provider}-matrix-final.log").write_text(log)
        match = re.search(r"Ran (\d+) tests?", log)
        count = int(match.group(1)) if match else 0
        item = {"binary": str(binary), "binary_sha256": sha(binary),
                "exit_code": result.returncode, "tests_run": count,
                "passed": result.returncode == 0 and count == expected,
                "seconds": round(time.monotonic() - start, 2)}
        report["providers"][provider] = item
        print(provider, json.dumps(item), flush=True)
        (OUT / "manager-matrix.json").write_text(json.dumps(report, indent=2))
    report["sources_unchanged_during_tests"] = hashes == {
        str(p.relative_to(ROOT)): sha(p) for p in files if p.is_file()
    }
    report["all_passed"] = report["sources_unchanged_during_tests"] and all(
        x["passed"] for x in report["providers"].values()
    )
    (OUT / "manager-matrix.json").write_text(json.dumps(report, indent=2))
    return 0 if report["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
