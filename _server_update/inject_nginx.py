#!/usr/bin/env python3
"""
把更新通道片段注入 nginx 配置。
- 幂等：已注入则先移除旧块再重新注入
- 自动备份
- 注入位置：https server 的 location / 之前
"""
import io
import os
import re
import shutil
import subprocess
import sys
import time

CONF = "/etc/nginx/conf.d/lianyu.conf"
SNIPPET = "/root/lianyu-update/nginx_update_snippet.conf"
BEGIN = "    # ══════════════════════════════════════════════════════════════\n    # 客户端更新通道（仅对 LianYu 客户端开放，端到端加密）"
END = "    # ── APK 签名下载：secure_link 校验通过才可下载 ──"


def read(p):
    with io.open(p, "r", encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def main():
    conf = read(CONF)
    snippet = read(SNIPPET)

    # 移除旧注入块（从 BEGIN 到 /dl/ location 结束的 `}` 加空行）
    if BEGIN in conf:
        start = conf.index(BEGIN)
        marker = "limit_conn perip 4;\n    }\n"
        end = conf.index(marker, start) + len(marker)
        # 吞掉后继空行
        while end < len(conf) and conf[end] == "\n":
            end += 1
        conf = conf[:start] + conf[end:]
        print("[inject] removed previous block")

    # 注入到最外层 "    location / {" 之前
    anchor = "\n    # Main site - Nginx adds security headers since backend may not\n    location / {"
    if anchor not in conf:
        anchor = "\n    location / {"
    if anchor not in conf:
        print("[inject] ERROR: anchor 'location / {' not found", file=sys.stderr)
        sys.exit(1)

    conf = conf.replace(anchor, "\n" + snippet + anchor.lstrip("\n"), 1)

    shutil.copy2(CONF, CONF + ".bak-update-" + time.strftime("%Y%m%d%H%M%S"))
    write(CONF, conf)
    print("[inject] written")

    r = subprocess.run(["nginx", "-t"], capture_output=True, text=True)
    print(r.stdout.strip())
    print(r.stderr.strip())
    if r.returncode != 0:
        print("[inject] nginx -t FAILED", file=sys.stderr)
        sys.exit(1)

    subprocess.run(["systemctl", "reload", "nginx"], check=True)
    print("[inject] nginx reloaded")


if __name__ == "__main__":
    main()
