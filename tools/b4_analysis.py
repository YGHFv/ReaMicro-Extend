#!/usr/bin/env python3
"""B4 双源只读取证：REA 读取本地目标 APK，MT 读取对应的只读 APK 工作区。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from fetch_mcp_binary import McpClient

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ["TEMP"]) / "reamicro-analysis"
OUT = CACHE / "runtime/b4"
APK = CACHE / "RM2.3.3.B4.APK"
REA = ROOT / ".tools/rea-6.7.0/node_modules/rea-agents/scripts/rea.mjs"
JAR = ROOT / ".tools/rea-6.7.0/jadx-headless-mcp-0.7.1-all.jar"
PIN = "6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75"
ENDPOINT = "http://192.168.10.43:8787/mcp"


def save(label, data):
    if not label or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for c in label):
        raise ValueError("证据名称只能使用小写字母、数字、横线或下划线")
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{label}.json"
    # 不覆盖旧证据，避免把不同 APK 或工具调用的结论混在一起。
    with path.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("Evidence:", path)


def rea(label, command):
    if hashlib.sha256(JAR.read_bytes()).hexdigest() != PIN:
        raise ValueError("JADX 引擎摘要不符，拒绝执行")
    allowed = {
        "inspect-android-package", "inspect-android-class", "inspect-android-method",
        "trace-android-references", "search-android-classes",
    }
    if not command or command[0] not in allowed:
        raise ValueError("仅允许本地 APK 静态读取命令")
    command = list(command)
    if command[0] in {
        "inspect-android-class", "inspect-android-method", "trace-android-references",
    } and len(command) > 1 and "." not in command[1]:
        # REA/JADX 为根包添加展示前缀；该前缀绝不能写入真实 DEX profile。
        command[1] = "defpackage." + command[1]
    env = os.environ.copy()
    env.update({
        "JAVA_HOME": "C:/Program Files/Android/Android Studio1/jbr",
        "REA_JADX_MCP_JAR": str(JAR),
        "REA_JADX_HEAP_MIB": "4096",
        "REA_JADX_ACTIVE_PROCESSOR_COUNT": "4",
        "REA_JADX_OPERATION_TIMEOUT_MS": "240000",
    })
    started = time.monotonic()
    result = subprocess.run(
        ["node", str(REA), command[0], str(APK), *command[1:], "--format", "json"],
        cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    data = {
        "reaVersion": "6.7.0",
        "apkSha256": hashlib.sha256(APK.read_bytes()).hexdigest(),
        "command": command,
        "exitCode": result.returncode,
        "elapsedSeconds": time.monotonic() - started,
        "stderr": result.stderr.decode("utf-8", errors="replace"),
        "result": json.loads(result.stdout.decode("utf-8")),
    }
    save(label, data)
    print("exitCode", result.returncode, "seconds", round(data["elapsedSeconds"], 1))
    if result.returncode:
        print(json.dumps(data["result"], ensure_ascii=False)[:2000])
    raise SystemExit(result.returncode)


def mt(label, locator, workspace, reference_build):
    # B4 由服务端可用 APK 列表定位；不创建编辑会话，不写入 MT Home 或调用生命周期工具。
    if not locator.startswith(("dex_class:", "dex_method:", "dex_field:")):
        raise ValueError("只允许 DEX 类、方法或字段只读 locator")
    result = McpClient(ENDPOINT).call("mt_apk_read_text", {
        "workspaceId": workspace,
        "editSessionId": "",
        "locator": locator,
        "limit": 2000,
        "maxChars": 131072,
        "startLine": 0,
        "startColumn": 0,
    })
    save(label, {
        "endpoint": ENDPOINT,
        "referenceBuild": reference_build,
        "workspaceId": workspace,
        "locator": locator,
        "result": result,
    })
    window = result.get("textWindow", {})
    print(window.get("text", "")[:1400])
    print("pagination", result.get("pagination"))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="mode", required=True)
    rea_parser = sub.add_parser("rea")
    rea_parser.add_argument("label")
    rea_parser.add_argument("command", nargs=argparse.REMAINDER)
    mt_parser = sub.add_parser("mt")
    mt_parser.add_argument("label")
    mt_parser.add_argument("locator")
    # 端点漂移到 K50U 后工作区会重建；2026-10-27 用 mt_apk_workspace_open 重新取得。
    mt_parser.add_argument("--workspace", default="gv89uj9u")
    mt_parser.add_argument("--reference-build", choices=("b3", "b4"), default="b4")
    args = parser.parse_args()
    if args.mode == "rea":
        rea(args.label, args.command)
    else:
        mt(args.label, args.locator, args.workspace, args.reference_build)


if __name__ == "__main__":
    main()
