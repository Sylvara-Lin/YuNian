#!/usr/bin/env python3
"""
一键部署更新通道到 lianyu.chat。
步骤：
  1. 上传服务端文件到 /root/lianyu-update/
  2. 生成密钥 + manifest
  3. 渲染并注入 nginx 片段
  4. 启动 lianyu-update.service
  5. 端到端自检

用法: python3 deploy_update.py [--skip-inject] [--skip-e2e]
"""
import io
import os
import paramiko
import sys
import time

HOST = "lianyu.chat"
USER = "root"
KEY = os.path.expanduser(r"C:\Users\27194\.ssh\id_ed25519_lianyu")
LOCAL = os.path.dirname(os.path.abspath(__file__))
REMOTE = "/root/lianyu-update"

FILES = [
    ("server.mjs", f"{REMOTE}/server.mjs"),
    ("bootstrap.sh", f"{REMOTE}/bootstrap.sh"),
    ("render_snippet.py", f"{REMOTE}/render_snippet.py"),
    ("inject_nginx.py", f"{REMOTE}/inject_nginx.py"),
    ("sign_uri.py", f"{REMOTE}/sign_uri.py"),
    ("e2e_update_check.py", f"{REMOTE}/e2e_update_check.py"),
    ("nginx_update_snippet.conf", f"{REMOTE}/nginx_update_snippet.template"),
]


def run(ssh, cmd, check=True, quiet=False):
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=180)
    out = stdout.read().decode(errors="replace")
    err = stderr.read().decode(errors="replace")
    code = stdout.channel.recv_exit_status()
    if not quiet:
        if out.strip():
            print(out.rstrip())
        if err.strip():
            print(err.rstrip(), file=sys.stderr)
    if check and code != 0:
        raise RuntimeError(f"remote command failed ({code}): {cmd}")
    return code, out, err


def main():
    skip_inject = "--skip-inject" in sys.argv
    skip_e2e = "--skip-e2e" in sys.argv

    key = paramiko.Ed25519Key.from_private_key_file(KEY)
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(HOST, username=USER, pkey=key, timeout=30)
    print(f"[deploy] connected to {HOST}")

    run(ssh, f"mkdir -p {REMOTE}/apk && chmod 700 {REMOTE}")

    sftp = ssh.open_sftp()
    for name, dest in FILES:
        src = os.path.join(LOCAL, name)
        sftp.put(src, dest)
        print(f"[deploy] uploaded {name}")
    sftp.close()

    # 统一去掉 CRLF 并赋权
    run(ssh, f"cd {REMOTE} && sed -i 's/\\r$//' *.sh *.py *.mjs *.template 2>/dev/null; chmod +x *.sh *.py")

    print("\n[deploy] bootstrap (keys + manifest)")
    run(ssh, f"bash {REMOTE}/bootstrap.sh")

    if not skip_inject:
        print("\n[deploy] render nginx snippet")
        run(ssh, f"python3 {REMOTE}/render_snippet.py")
        print("\n[deploy] inject nginx snippet")
        run(ssh, f"python3 {REMOTE}/inject_nginx.py")

    print("\n[deploy] install systemd service")
    svc = io.open(os.path.join(LOCAL, "lianyu-update.service"), "r", encoding="utf-8").read().replace("\r\n", "\n")
    sftp = ssh.open_sftp()
    with sftp.open("/etc/systemd/system/lianyu-update.service", "w") as f:
        f.write(svc)
    sftp.close()
    run(ssh, "systemctl daemon-reload && systemctl enable lianyu-update >/dev/null 2>&1; systemctl restart lianyu-update; sleep 2; systemctl is-active lianyu-update")
    run(ssh, "tail -5 /var/log/lianyu-update.log")

    if not skip_e2e:
        print("\n[deploy] end-to-end self check")
        time.sleep(1)
        run(ssh, f"python3 {REMOTE}/e2e_update_check.py https://lianyu.chat", check=False)

    ssh.close()
    print("\n[deploy] done")


if __name__ == "__main__":
    main()
