#!/usr/bin/env python3
"""逐项验收记录：分离静态、协议及真实界面证据，不把安装成功作为功能通过。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from rc3_retarget import ROOT, CACHE, load_code
from fetch_mcp_binary import McpClient
import retarget_profile as rp

OUT = CACHE / "runtime/rc3/full-audit-20261029"


def save(name, data):
    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / name).open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def adb(*args):
    return subprocess.check_output(["adb", "-s", "deb497bd", *args]).decode("utf-8", errors="replace")


def baseline():
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
    hashes = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
              for name in paths if name and (ROOT / name).is_file()}
    status = subprocess.check_output(["git", "status", "--porcelain=v1", "-z"], cwd=ROOT).decode("utf-8")
    save("baseline.json", {
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
        "status": status, "hashes": hashes,
        "host": adb("shell", "dumpsys", "package", "app.zhendong.reamicro"),
        "module": adb("shell", "dumpsys", "package", "com.reamicro.fix"),
        "mtPid": adb("shell", "pidof", "bin.mt.plus").strip(),
        "hostPid": adb("shell", "pidof", "app.zhendong.reamicro").strip(),
        "bootId": adb("shell", "cat", "/proc/sys/kernel/random/boot_id").strip(),
        "apkSha256": hashlib.sha256((CACHE / "RM2.3.3.RC3.APK").read_bytes()).hexdigest(),
    })
    client = McpClient("http://192.168.10.43:8787/mcp")
    workspaces = client.call("mt_apk_workspace_list", {})
    save("mt-workspaces.json", workspaces)
    print("Baseline saved; tracked files", len(hashes), "preexisting status entries", status.count("\0"))
    print(json.dumps(workspaces, ensure_ascii=False)[:3500])


def raw_sites():
    source, target = load_code("b4"), load_code("rc3")
    old = rp.parse_profile(ROOT / "app/src/main/assets/host-profiles/2.3.3-b4.profile")
    new = rp.parse_profile(ROOT / "app/src/main/assets/host-profiles/2.3.3-rc3.profile")
    old_classes = old["classes"] | old["typeAliases"]
    new_classes = new["classes"] | new["typeAliases"]
    rows = []
    for path in (ROOT / "app/src/main/java/com/reamicro/fix").rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        declarations = {}
        for match in re.finditer(r'val\s+(\w+)\s*=\s*(?:\w+\.)*(?:cls|hostClass)\("([^"]+)"\)', text):
            declarations[match[1]] = match[2]
        for number, line in enumerate(text.splitlines(), 1):
            for match in re.finditer(r'(\w+)\.(getDeclaredMethod|getDeclaredField|hostAbiMethod|hostAbiField)\("([^"]+)"', line):
                variable, kind, name = match.groups()
                logical = declarations.get(variable)
                if logical not in old_classes or logical not in new_classes:
                    continue
                before, after = old_classes[logical], new_classes[logical]
                def candidates(code, owner):
                    return [list(key) for key in code.get(owner, {}).get(
                        "fields" if kind.endswith("Field") else "methods", {}
                    ) if key[0] == name]
                a, b = candidates(source, before), candidates(target, after)
                rows.append({
                    "file": str(path.relative_to(ROOT)), "line": number, "api": kind,
                    "logical": logical, "oldOwner": before, "newOwner": after, "name": name,
                    "old": a, "new": b, "raw": not kind.startswith("hostAbi"),
                })
    save("raw-sites.json", rows)
    print("Matched sites", len(rows))
    for row in rows:
        if row["raw"] and row["old"] != row["new"]:
            print(row["file"], row["line"], row["logical"], row["name"], row["old"], "->", row["new"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("baseline", "raw-sites"))
    args = parser.parse_args()
    (baseline if args.mode == "baseline" else raw_sites)()
