#!/usr/bin/env python3
"""只读审计 B4 的日志、profile 成员和源码字面量依赖，不把候选缺口当成已确认故障。"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

import hostsym as hs

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ["TEMP"]) / "reamicro-analysis"
PACKAGE = "app.zhendong.reamicro"
SERIAL = os.environ.get("B4_ADB_SERIAL", "deb497bd")


def save(directory: Path, name: str, value):
    (directory / name).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )


def parse_profile(path: Path):
    result = {kind: {} for kind in ("class", "typeAlias", "methodOwner", "method", "field")}
    for line in path.read_text(encoding="utf-8").splitlines():
        kind, _, entry = line.partition("=")
        if kind in result:
            key, _, value = entry.rpartition("=")
            result[kind][key] = value
    return result


def ancestors(host, name):
    found = set()
    while name in host and name not in found:
        found.add(name)
        yield name
        parent = host[name].get("rawSup", host[name].get("sup", ""))
        name = parent.removeprefix("L").removesuffix(";").replace("/", ".")


def audit(directory: Path):
    reference = hs.load(CACHE / "b1-features.pkl.gz")
    host = hs.load(CACHE / "b4-features.pkl.gz")
    profile = parse_profile(ROOT / "app/src/main/assets/host-profiles/2.3.3-b4.profile")
    classes = profile["class"] | profile["typeAlias"]
    literal_gaps = {}
    reflective_sites = []
    baseline = {}
    for path in (ROOT / "app/src/main/java").rglob("*"):
        if path.suffix not in (".kt", ".java"):
            continue
        raw = path.read_bytes()
        relative = path.relative_to(ROOT).as_posix()
        baseline[relative] = hashlib.sha256(raw).hexdigest()
        for number, line in enumerate(raw.decode("utf-8").splitlines(), 1):
            for match in re.finditer(r'"([A-Za-z][\w.$\\]+)"', line):
                name = match[1].replace("\\$", "$")
                if name not in reference or name in classes or name in host:
                    continue
                if any(key.startswith(name + "#") for key in profile["methodOwner"]):
                    continue
                literal_gaps.setdefault(name, []).append({"file": relative, "line": number})
            if re.search(r'\.(?:getDeclaredMethod|getMethod|getDeclaredField|getField)\("[A-Za-z_]', line):
                reflective_sites.append({"file": relative, "line": number, "source": line.strip()})
    missing_targets = {name: target for name, target in classes.items() if target not in host}
    missing_members = []
    for kind, member_key in (("method", "rawMeth"), ("field", "rawFld")):
        for key, actual in profile[kind].items():
            owner, _, logical = key.partition("#")
            target = profile["methodOwner"].get(key) if kind == "method" else None
            target = target or classes.get(owner, owner)
            if not any(
                actual in {name for name, _ in host[parent][member_key]}
                for parent in ancestors(host, target)
            ):
                missing_members.append({
                    "kind": kind, "logical": key, "target": target, "member": actual,
                })
    save(directory, "baseline-hashes.json", baseline)
    save(directory, "literal-class-gaps.json", literal_gaps)
    save(directory, "raw-reflective-sites.json", reflective_sites)
    save(directory, "missing-profile-targets.json", missing_targets)
    save(directory, "missing-profile-members.json", missing_members)
    summary = {
        "profileClasses": len(profile["class"]), "profileAliases": len(profile["typeAlias"]),
        "methods": len(profile["method"]), "fields": len(profile["field"]),
        "literalClassGapCandidates": len(literal_gaps),
        "missingTargets": len(missing_targets), "missingMembers": len(missing_members),
        "rawReflectiveSites": len(reflective_sites),
    }
    save(directory, "summary.json", summary)
    (directory / "git-status.bin").write_bytes(subprocess.check_output(
        ["git", "status", "--porcelain=v1", "-z"], cwd=ROOT,
    ))
    print(json.dumps(summary, ensure_ascii=False))
    for name, sites in sorted(literal_gaps.items()):
        print(name, " ".join(f"{site['file']}:{site['line']}" for site in sites[:2]))
    for row in missing_members:
        print("member candidate:", row)


def logs(directory: Path):
    def adb(*args):
        return subprocess.check_output(["adb", "-s", SERIAL, *args])

    # 所有设备命令只读取；保存完整原文，不通过关键词过滤丢失异常原因。
    pid = adb("shell", "pidof", PACKAGE).decode("utf-8").strip().split()[0]
    logcat = adb("logcat", "-d", "-v", "threadtime", "--pid=" + pid)
    (directory / f"logcat-pid{pid}.log").write_bytes(logcat)
    names = adb("shell", "su", "-c", "ls -t /data/adb/lspd/log/modules_*.log")
    module_path = names.decode("utf-8").splitlines()[0].strip()
    if not re.fullmatch(r"/data/adb/lspd/log/modules_[\w.:-]+\.log", module_path):
        raise ValueError("拒绝读取意外日志路径")
    modules = adb("exec-out", "su", "-c", "cat " + module_path)
    (directory / "modules-all.log").write_bytes(modules)
    from b4_runtime_log import pid_lines
    current = pid_lines(modules.decode("utf-8", errors="replace"), pid)
    (directory / f"modules-pid{pid}.log").write_text(
        "\n".join(current) + "\n", encoding="utf-8", newline="\n",
    )
    errors = [line for line in current if re.search(
        r"failed|not found|unavailable|skipped|disabled|FATAL|Exception", line, re.I,
    ) and "symbols mismatch 2.3.3-b" not in line]
    # 历史问题按文本归并；必须另行核对发生时间及宿主 PID，不能直接视作当前错误。
    historical = Counter()
    for line in modules.decode("utf-8", errors="replace").splitlines():
        if PACKAGE not in line or "ReaMicro" not in line:
            continue
        if not re.search(r"failed|not found|unavailable|skipped|disabled|FATAL|Exception", line, re.I):
            continue
        if "symbols mismatch 2.3.3-b" in line:
            continue
        message = line.partition("]")[2]
        message = re.sub(r"^\s*\([^)]*\)\[[^]]*\]\s*", "", message)
        historical[message] += 1
    metadata = {
        "pid": pid, "moduleLogPath": module_path, "currentModuleLines": len(current),
        "currentDiagnosticCandidates": errors,
        "historicalCandidates": historical.most_common(),
        "deviceUptime": adb("shell", "cat", "/proc/uptime").decode("utf-8").strip(),
        "mtPid": adb("shell", "pidof", "bin.mt.plus").decode("utf-8").strip(),
    }
    save(directory, "runtime-summary.json", metadata)
    print("current PID", pid, "module lines", len(current), "diagnostic candidates", len(errors))
    print("\n".join(errors))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    parser.add_argument("--logs", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]+", args.label):
        raise ValueError("非法证据标签")
    directory = CACHE / "runtime/b4" / args.label
    directory.mkdir(exist_ok=False)
    audit(directory)
    if args.logs:
        logs(directory)
    print("Evidence:", directory)


if __name__ == "__main__":
    main()
