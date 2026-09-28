#!/bin/bash
# 网关防护验证：确认接口仅对 LianYu 客户端开放
B=/root/lianyu-update
S=$(grep UPDATE_HMAC_SECRET "$B/secrets.env" | cut -d= -f2)
DS=$(grep UPDATE_DL_SECRET "$B/secrets.env" | cut -d= -f2)
KEY=$(grep UPDATE_APP_KEY "$B/secrets.env" | cut -d= -f2)
API="https://lianyu.chat/api/update/check"

mkgate() {
  python3 - "$1" "$2" <<'PY'
import base64, hashlib, sys
e, s = sys.argv[1], sys.argv[2]
raw = f"lianyu-update-v1|{e}|/api/update/check|{s}".encode()
print(base64.b64encode(hashlib.md5(raw).digest()).decode().replace('+', '-').replace('/', '_').rstrip('='))
PY
}

mkdl() {
  python3 - "$1" "$2" "$3" <<'PY'
import base64, hashlib, sys
e, path, s = sys.argv[1], sys.argv[2], sys.argv[3]
raw = f"{e}{path}{s}".encode()
print(base64.b64encode(hashlib.md5(raw).digest()).decode().replace('+', '-').replace('/', '_').rstrip('='))
PY
}

NOW=$(date +%s)
E=$((NOW + 600))
M=$(mkgate "$E" "$S")
EX=$((NOW - 10))
MX=$(mkgate "$EX" "$S")

code() { curl -s -o /tmp/_b -w '%{http_code}' "$@"; }

echo "=== 网关防护验证 ==="
printf '1. 无密钥            -> %s  %s\n' "$(code "$API?md5=$M&expires=$E")" "$(head -c 80 /tmp/_b)"
printf '2. 错误密钥          -> %s  %s\n' "$(code -H "X-LianYu-Key: wrong" "$API?md5=$M&expires=$E")" "$(head -c 80 /tmp/_b)"
printf '3. 无网关签名        -> %s  %s\n' "$(code -H "X-LianYu-Key: $KEY" "$API")" "$(head -c 80 /tmp/_b)"
printf '4. 伪造签名          -> %s  %s\n' "$(code -H "X-LianYu-Key: $KEY" "$API?md5=AAAAAAAAAAAAAAAAAAAAAA&expires=$E")" "$(head -c 80 /tmp/_b)"
printf '5. 签名已过期        -> %s  %s\n' "$(code -H "X-LianYu-Key: $KEY" "$API?md5=$MX&expires=$EX")" "$(head -c 80 /tmp/_b)"
printf '6. 网关通过/无设备签名 -> %s  %s\n' "$(code -H "X-LianYu-Key: $KEY" "$API?md5=$M&expires=$E")" "$(head -c 80 /tmp/_b)"
printf '7. 其他 /api/update 路径 -> %s  %s\n' "$(code -H "X-LianYu-Key: $KEY" 'https://lianyu.chat/api/update/manifest.json')" "$(head -c 80 /tmp/_b)"

DLP=/dl/app-v2.0.0.apk
DE=$((NOW + 600))
DM=$(mkdl "$DE" "$DLP" "$DS")
printf '8. 未签名 APK 下载    -> %s  %s\n' "$(code "https://lianyu.chat$DLP")" "$(head -c 80 /tmp/_b)"
printf '9. 已签名 APK 下载    -> %s  %s\n' "$(code -r 0-0 "https://lianyu.chat$DLP?md5=$DM&expires=$DE")" "$(head -c 80 /tmp/_b)"
printf '10. 过期 APK 下载     -> %s  %s\n' "$(code -H '' "https://lianyu.chat$DLP?md5=$(mkdl "$EX" "$DLP" "$DS")&expires=$EX")" "$(head -c 80 /tmp/_b)"
echo
echo "=== 服务日志 ==="
tail -15 /var/log/lianyu-update.log
