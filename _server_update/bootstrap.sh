#!/bin/bash
# 初始化/更新更新通道：密钥 + 目录 + manifest + systemd
set -e

DIR=/root/lianyu-update
mkdir -p "$DIR/apk" "$DIR/signed"
chmod 700 "$DIR"

if [ ! -f "$DIR/secrets.env" ]; then
  {
    echo "UPDATE_APP_KEY=lianyu-app-k1"
    echo "UPDATE_HMAC_SECRET=$(openssl rand -hex 32)"
    echo "UPDATE_AES_SECRET=$(openssl rand -hex 32)"
    echo "UPDATE_DL_SECRET=$(openssl rand -hex 32)"
  } > "$DIR/secrets.env"
  chmod 600 "$DIR/secrets.env"
  echo "secrets: generated"
else
  echo "secrets: exists"
fi

# nginx 签名白名单（仅此 URI 可被客户端签名访问）
printf '/api/update/check\n' > "$DIR/signable_uri.txt"
chmod 600 "$DIR/signable_uri.txt"

# manifest 模板（首次生成，后续由发布脚本覆盖）
if [ ! -f "$DIR/manifest.json" ]; then
  cat > "$DIR/manifest.json" <<'JSON'
{
  "versionCode": 23,
  "versionName": "1.10.8",
  "minVersionCode": 20,
  "forceUpdate": false,
  "updateLog": "1. 新增检查更新功能\n2. 支持后台静默下载\n3. 弱网环境自动断点续传",
  "publishDate": "2026-09-15",
  "mirrors": []
}
JSON
  chmod 600 "$DIR/manifest.json"
  echo "manifest: created"
else
  echo "manifest: exists"
fi

echo "=== secrets.env ==="
cat "$DIR/secrets.env"
echo "=== dir ==="
ls -la "$DIR"
