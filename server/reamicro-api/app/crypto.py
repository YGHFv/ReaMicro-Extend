"""密钥派生、对称加密与口令哈希。

同步密钥等敏感值用服务器 SECRET_KEY 派生的密钥加密后落盘；密文带密钥 ID，
轮换 SECRET_KEY 时可识别旧密文并重新加密（见 app.backups.rotate_secret_key）。
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
from datetime import datetime, timezone
from typing import Any

from app import runtime
from app.config_store import load_config


def secret_cipher_key() -> bytes:
    material = str(load_config().get("secretKey", "")) or runtime.SECRET_KEY or runtime.ADMIN_PASSWORD
    if not material:
        raise ValueError("未配置 REAMICRO_SECRET_KEY 或管理密码")
    return hashlib.sha256(material.encode("utf-8")).digest()


def secret_key_id() -> str:
    material = str(load_config().get("secretKey", "")) or runtime.SECRET_KEY or runtime.ADMIN_PASSWORD
    return "key_" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def encrypt_secret(value: Any) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    nonce = secrets.token_bytes(12)
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    encrypted = AESGCM(secret_cipher_key()).encrypt(nonce, body, b"reamicro-task-v1")
    return "RCSEC2:" + secret_key_id() + ":" + base64.urlsafe_b64encode(nonce + encrypted).decode("ascii")


def decrypt_secret(value: str) -> Any:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    encoded = value.split(":", 2)[-1] if value.startswith("RCSEC2:") else value
    raw = base64.urlsafe_b64decode(encoded.encode("ascii"))
    body = AESGCM(secret_cipher_key()).decrypt(raw[:12], raw[12:], b"reamicro-task-v1")
    return json.loads(body.decode("utf-8"))


def password_hash(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 210_000)
    return "pbkdf2$210000$" + base64.urlsafe_b64encode(salt).decode() + "$" + base64.urlsafe_b64encode(digest).decode()


def password_matches(password: str, encoded: str) -> bool:
    try:
        scheme, rounds, salt_text, digest_text = encoded.split("$", 3)
        if scheme != "pbkdf2":
            return False
        salt = base64.urlsafe_b64decode(salt_text.encode())
        expected = base64.urlsafe_b64decode(digest_text.encode())
        actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, int(rounds))
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


def generate_long_secret(byte_length: int = 48) -> str:
    """生成适合 API Key 或初始密码的 URL 安全随机值。"""
    return secrets.token_urlsafe(max(32, byte_length))


def api_key_digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
