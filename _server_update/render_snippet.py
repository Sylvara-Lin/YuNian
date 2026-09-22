#!/usr/bin/env python3
"""用服务器密钥渲染 nginx 更新通道片段（幂等）。"""
import io
import os
import sys

BASE = "/root/lianyu-update"
TEMPLATE = os.path.join(BASE, "nginx_update_snippet.template")
OUT = os.path.join(BASE, "nginx_update_snippet.conf")
SECRETS = os.path.join(BASE, "secrets.env")


def load_env(p):
    env = {}
    with io.open(p, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def main():
    env = load_env(SECRETS)
    tmpl = io.open(TEMPLATE, "r", encoding="utf-8").read()

    required = ["UPDATE_APP_KEY", "UPDATE_HMAC_SECRET", "UPDATE_DL_SECRET"]
    for k in required:
        if not env.get(k):
            print(f"[render] missing {k}", file=sys.stderr)
            sys.exit(1)

    out = (
        tmpl.replace("__APP_KEY__", env["UPDATE_APP_KEY"])
        .replace("__HMAC_SECRET__", env["UPDATE_HMAC_SECRET"])
        .replace("__DL_SECRET__", env["UPDATE_DL_SECRET"])
        .replace("__SIG_PREFIX__", "lianyu-update-v1")
    )
    if "__" in out.replace("__", "", 0):
        leftovers = [t for t in ("__APP_KEY__", "__HMAC_SECRET__", "__DL_SECRET__", "__SIG_PREFIX__") if t in out]
        if leftovers:
            print(f"[render] unresolved placeholders: {leftovers}", file=sys.stderr)
            sys.exit(1)

    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    os.chmod(OUT, 0o600)
    print(f"[render] wrote {OUT}")


if __name__ == "__main__":
    main()
