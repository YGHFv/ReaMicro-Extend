"""生成内容包清单；配置 Ed25519 PEM 私钥时同时签名。"""
import argparse
import base64
import hashlib
import json
import time
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default="data/packages")
    parser.add_argument("--kind", required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--payload", required=True)
    parser.add_argument("--name", default="")
    parser.add_argument("--description", default="")
    parser.add_argument("--content-id", default="")
    parser.add_argument("--alias", action="append", default=[])
    parser.add_argument("--build-time", type=int, default=0)
    parser.add_argument("--private-key")
    args = parser.parse_args()

    source = Path(args.payload)
    body = source.read_bytes()
    sha256 = hashlib.sha256(body).hexdigest()
    target = Path(args.root) / args.kind / args.id
    target.mkdir(parents=True, exist_ok=True)
    payload_name = source.name
    (target / payload_name).write_bytes(body)
    content_id = args.content_id or args.id
    build_time = args.build_time or int(time.time() * 1000)
    signed_message = (
        f"{args.id}\n{args.kind}\n{content_id}\n{args.version}\n{build_time}\n{sha256}".encode("utf-8") + body
    )
    signature = ""
    if args.private_key:
        from cryptography.hazmat.primitives import serialization
        private_key = serialization.load_pem_private_key(Path(args.private_key).read_bytes(), password=None)
        signature = base64.b64encode(private_key.sign(signed_message)).decode("ascii")
    manifest = {
        "packageId": args.id,
        "kind": args.kind,
        "version": args.version,
        "buildTime": build_time,
        "schemaVersion": 1,
        "minModuleVersion": "2.0.0",
        "sha256": sha256,
        "signature": signature,
        "payload": payload_name,
        "name": args.name or args.id,
        "description": args.description,
        "contentId": content_id,
        "aliases": args.alias,
    }
    (target / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
