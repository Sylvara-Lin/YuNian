#!/usr/bin/env python3
"""
签发客户端访问 /api/update/check 所需的签名参数。
这是「按客户端分发」的凭证：只有拿到 md5/expires 的请求才能通过 nginx。

客户端需缓存该凭证（有效期 TTL 天），过期后重新通过分发渠道获取。
"""
import base64
import hashlib
import io
import os
import sys
import time

BASE = "/root/lianyu-update"
SECRETS = os.path.join(BASE, "secrets.env")
URI = "/api/update/check"
PREFIX = "lianyu-update-v1"


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
    days = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    env = load_env(SECRETS)
    secret = env["UPDATE_HMAC_SECRET"]
    expires = int(time.time()) + days * 86400
    raw = f"{PREFIX}|{expires}|{URI}|{secret}".encode()
    md5 = b64url(hashlib.md5(raw).digest())
    print(f"md5={md5}")
    print(f"expires={expires}")
    print(f"url=https://lianyu.chat{URI}?md5={md5}&expires={expires}")


if __name__ == "__main__":
    main()
