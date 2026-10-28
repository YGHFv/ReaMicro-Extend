#!/usr/bin/env python3
"""Fetch a binary file exposed by the MT MCP server."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import pathlib
import threading
import time
import urllib.request


class McpClient:
    def __init__(self, endpoint: str) -> None:
        self.endpoint = endpoint
        self._next_id = 0
        self._lock = threading.Lock()

    def call(
        self,
        name: str,
        arguments: dict[str, object],
        attempts: int = 5,
    ) -> dict[str, object]:
        with self._lock:
            self._next_id += 1
            request_id = self._next_id
        payload = json.dumps(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": "tools/call",
                "params": {"name": name, "arguments": arguments},
            },
            separators=(",", ":"),
        ).encode()
        request = urllib.request.Request(
            self.endpoint,
            data=payload,
            headers={
                "Accept": "application/json, text/event-stream",
                "Content-Type": "application/json",
            },
        )
        for attempt in range(1, attempts + 1):
            try:
                with urllib.request.urlopen(request, timeout=180) as response:
                    envelope = json.load(response)
                break
            except (OSError, TimeoutError):
                if attempt == attempts:
                    raise
                time.sleep(min(attempt, 3))
        else:
            raise AssertionError("unreachable")
        text = envelope["result"]["content"][0]["text"]
        result = json.loads(text)
        if not result.get("ok"):
            raise RuntimeError(result.get("error") or text)
        return result["data"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("endpoint")
    parser.add_argument("remote_path")
    parser.add_argument("output_path", type=pathlib.Path)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--chunk-bytes", type=int, default=65536)
    args = parser.parse_args()

    client = McpClient(args.endpoint)
    stat = client.call("mt_file_stat", {"path": args.remote_path})
    size = int(stat["size"])
    target_version = str(stat["targetVersion"])
    args.output_path.parent.mkdir(parents=True, exist_ok=True)

    chunks: dict[int, bytes] = {}

    def fetch(offset: int) -> tuple[int, bytes]:
        data = client.call(
            "mt_file_read_bytes",
            {
                "path": args.remote_path,
                "targetVersion": target_version,
                "byteOffset": offset,
                "maxBytes": args.chunk_bytes,
            },
        )
        return offset, bytes.fromhex(str(data["hex"]))

    offsets = range(0, size, args.chunk_bytes)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        for offset, chunk in executor.map(fetch, offsets):
            chunks[offset] = chunk

    with args.output_path.open("wb") as output:
        for offset in offsets:
            output.write(chunks[offset])

    actual_size = args.output_path.stat().st_size
    if actual_size != size:
        raise RuntimeError(f"size mismatch: expected {size}, got {actual_size}")
    print(args.output_path)


if __name__ == "__main__":
    main()
