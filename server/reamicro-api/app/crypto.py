import base64
import hashlib
import hmac
import json
import secrets
from typing import Any

from app import runtime
from app.config_store import load_config


def secret_key_material(config: dict[str, Any]) -> str:
    material = str(config.get("secretKey", "")) or runtime.SECRET_KEY or runtime.ADMIN_PASSWORD
    if not material:
        raise ValueError("未配置 REAMICRO_SECRET_KEY 或管理密码")
    return material


def secret_key_id(material: str) -> str:
    return "key_" + hashlib.sha256(material.encode("utf-8")).hexdigest()[:12]


def encrypt_secret(value: Any) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    material = secret_key_material(load_config())
    nonce = secrets.token_bytes(12)
    body = json.dumps(value, ensure_ascii=False).encode("utf-8")
    encrypted = AESGCM(hashlib.sha256(material.encode("utf-8")).digest()).encrypt(nonce, body, b"reamicro-task-v1")
    return "RCSEC2:" + secret_key_id(material) + ":" + base64.urlsafe_b64encode(nonce + encrypted).decode("ascii")


def decrypt_secret(value: str) -> Any:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.exceptions import InvalidTag
    config = load_config()
    materials = [secret_key_material(config)]
    historical = config.get("secretKeyHistory", [])
    if isinstance(historical, list):
        materials.extend(item for item in historical if isinstance(item, str) and item)
    key_id = ""
    if value.startswith("RCSEC2:"):
        _, key_id, encoded = value.split(":", 2)
    else:
        encoded = value
    raw = base64.urlsafe_b64decode(encoded.encode("ascii"))
    if len(raw) < 28:
        raise ValueError("加密数据不完整")
    for material in dict.fromkeys(materials):
        if key_id and secret_key_id(material) != key_id:
            continue
        try:
            body = AESGCM(hashlib.sha256(material.encode("utf-8")).digest()).decrypt(raw[:12], raw[12:], b"reamicro-task-v1")
            return json.loads(body.decode("utf-8"))
        except InvalidTag:
            continue
    raise ValueError("无法使用服务器密钥解密数据")


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

    return secrets.token_urlsafe(max(32, byte_length))


def api_key_digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def validate_admin_password(value: str) -> str:
    if len(value) < 12:
        raise ValueError("管理员密码至少需要 12 位")
    return value
