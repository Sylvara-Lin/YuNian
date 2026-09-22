#!/usr/bin/env python3
"""
端到端自检：模拟 LianYu 客户端调用加密更新接口。
  1) 生成 P-256 设备密钥并签名请求
  2) 通过 nginx 网关（携带 X-LianYu-Key + 网关签名）请求
  3) 验签并解密响应，输出清单
用法: python3 e2e_update_check.py [base_url]
"""
import base64
import hashlib
import hmac
import io
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

BASE = "/root/lianyu-update"
SECRETS = os.path.join(BASE, "secrets.env")
PREFIX = "lianyu-update-v1"
PATH = "/api/update/check"
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()


def load_env(p):
    env = {}
    with io.open(p, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def b64url(b: bytes) -> str:
    return base64.b64encode(b).decode().replace("+", "-").replace("/", "_").rstrip("=")


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "https://lianyu.chat"
    env = load_env(SECRETS)
    app_key = env["UPDATE_APP_KEY"]
    hmac_secret = env["UPDATE_HMAC_SECRET"]

    # 设备密钥（模拟 AndroidKeyStore 不可导出私钥）
    priv = ec.generate_private_key(ec.SECP256R1())
    spki = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    key_id = hashlib.sha256(spki).hexdigest()[:32]
    device_id = hashlib.sha256(b"e2e-test-device").hexdigest()[:32]

    nonce = secrets.token_hex(16)
    ts = int(time.time())
    payload = "\n".join([PREFIX, "GET", PATH, EMPTY_SHA256, str(ts), nonce, app_key, device_id])
    sig = priv.sign(payload.encode(), ec.ECDSA(hashes.SHA256()))

    # 网关签名
    expires = ts + 3600
    gate = b64url(hashlib.md5(f"{PREFIX}|{expires}|{PATH}|{hmac_secret}".encode()).digest())

    url = f"{base}{PATH}?md5={gate}&expires={expires}"
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/json")
    req.add_header("X-LianYu-Key", app_key)
    req.add_header("X-LianYu-Sig-Version", "v1")
    req.add_header("X-LianYu-Ts", str(ts))
    req.add_header("X-LianYu-Nonce", nonce)
    req.add_header("X-LianYu-Body-SHA256", EMPTY_SHA256)
    req.add_header("X-LianYu-Device-Id", device_id)
    req.add_header("X-LianYu-Key-Id", key_id)
    req.add_header("X-LianYu-Pub", base64.b64encode(spki).decode())
    req.add_header("X-LianYu-Sig", base64.b64encode(sig).decode())

    print(f"[e2e] GET {url}")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            body = r.read().decode()
            print(f"[e2e] HTTP {r.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"[e2e] HTTP {e.code} FAILED: {body[:400]}")
        return 1

    envj = json.loads(body)
    # 验签
    sig_key = hashlib.sha256(f"{app_key}|lianyu-update-sig-v1".encode()).digest()
    msg = f"1|{envj['ts']}|{envj['nonce']}|{envj['iv']}|{envj['tag']}|{envj['data']}"
    expect = base64.b64encode(hmac.new(sig_key, msg.encode(), hashlib.sha256).digest()).decode()
    assert hmac.compare_digest(expect, envj["sig"]), "response signature mismatch"
    print("[e2e] response signature OK")

    # 解密
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    aes_key = hashlib.sha256(f"{app_key}|lianyu-update-v1".encode()).digest()
    ct = base64.b64decode(envj["data"]) + base64.b64decode(envj["tag"])
    plain = AESGCM(aes_key).decrypt(base64.b64decode(envj["iv"]), ct, None).decode()
    print("[e2e] decrypted manifest:")
    print(json.dumps(json.loads(plain), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
