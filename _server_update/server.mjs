import {
    createHash,
    createHmac,
    createCipheriv,
    randomBytes,
    createPublicKey,
    verify as ecVerify,
} from 'node:crypto';
import { readFileSync, existsSync, statSync } from 'node:fs';

/**
 * LianYu 客户端更新接口（仅对 lianyu 客户端开放）
 *
 * 安全模型（四层）：
 *  1. 客户端密钥    —— 必须携带 X-LianYu-Key（由 nginx 与本地双重校验），拦截通用扫描器/脚本
 *  2. 设备签名      —— ES256（AndroidKeyStore 硬件密钥，私钥不可导出）绑定 方法/路径/时间戳/随机数/设备号
 *  3. 时间窗+随机数 —— ±300s 时间窗 + nonce 去重，杜绝重放
 *  4. 响应加密      —— 清单以 AES-256-GCM 加密 + HMAC 签名返回，密钥由客户端密钥派生
 *  5. 下载地址签名  —— APK 仅可通过 nginx secure_link 短时签名 URL 下载，杜绝盗链/刷量
 *
 * 说明：客户端密钥内嵌于 APK，理论上可被逆向提取，因此真正的滥用防线是
 * 「签名下载地址 + 时间窗 + 频率配额」。若要进一步提升，需接入设备证明（Play Integrity）或设备注册表。
 */

const DIR = process.env.UPDATE_DIR ?? '/root/lianyu-update';
const APP_KEY = process.env.UPDATE_APP_KEY ?? 'lianyu-app-k1';
const DL_SECRET = process.env.UPDATE_DL_SECRET ?? '';
const PORT = Number(process.env.UPDATE_PORT ?? 3010);
const HOSTNAME = process.env.UPDATE_HOST ?? '127.0.0.1';

const MAX_SKEW_SEC = 300;            // 允许的客户端时钟偏差
const URL_TTL_SEC = 6 * 3600;        // 下载地址有效期
const SIGN_PREFIX = 'lianyu-update-v1';
const NONCE_TTL_MS = 10 * 60 * 1000; // nonce 去重保留 10 分钟
const IP_WINDOW_MS = 60 * 60 * 1000; // 频率窗口 1 小时
const IP_MAX_PER_WINDOW = 120;       // 每 IP 每窗口最大检查次数
const EMPTY_SHA256 =
    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

const AES_KEY = createHash('sha256').update(`${APP_KEY}|lianyu-update-v1`).digest();
const SIG_KEY = createHash('sha256').update(`${APP_KEY}|lianyu-update-sig-v1`).digest();

// ── 内存态：nonce 去重 / IP 配额（进程内，重启即清空，符合"低成本、无需 DB"的定位） ──
const seenNonces = new Map();
const ipHits = new Map();

setInterval(() => {
    const now = Date.now();
    for (const [k, v] of seenNonces) if (v < now) seenNonces.delete(k);
    for (const [k, v] of ipHits) if (v.reset < now) ipHits.delete(k);
}, 60_000).unref?.();

function b64url(buf) {
    return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function sha256Hex(input) {
    return createHash('sha256').update(input).digest('hex');
}

function json(body, status = 200, extraHeaders = {}) {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Cache-Control': 'no-store, no-cache, must-revalidate',
            Pragma: 'no-cache',
            'X-Content-Type-Options': 'nosniff',
            'X-Robots-Tag': 'noindex, nofollow',
            ...extraHeaders,
        },
    });
}

/** 读取服务端清单（仅服务器可读，不对外暴露） */
function loadManifest() {
    const p = `${DIR}/manifest.json`;
    if (!existsSync(p)) return null;
    try {
        return JSON.parse(readFileSync(p, 'utf8'));
    } catch {
        return null;
    }
}

/** 计算 APK 的 size / sha256（按 mtime 缓存，避免每次全量哈希） */
let apkCache = { path: '', mtime: 0, size: 0, sha256: '' };
function apkMeta(versionName) {
    const apkPath = `${DIR}/apk/app-v${versionName}.apk`;
    if (!existsSync(apkPath)) return null;
    const st = statSync(apkPath);
    if (apkCache.path === apkPath && apkCache.mtime === st.mtimeMs) return apkCache;
    const sha256 = createHash('sha256').update(readFileSync(apkPath)).digest('hex');
    apkCache = { path: apkPath, mtime: st.mtimeMs, size: st.size, sha256 };
    return apkCache;
}

/** 生成 nginx secure_link 签名下载地址 */
function signedDownloadUrl(versionName) {
    const path = `/dl/app-v${versionName}.apk`;
    const expires = Math.floor(Date.now() / 1000) + URL_TTL_SEC;
    const md5 = createHash('md5').update(`${expires}${path}${DL_SECRET}`).digest();
    return {
        url: `https://lianyu.chat${path}?md5=${b64url(md5)}&expires=${expires}`,
        expiresAt: expires,
        path,
    };
}
/** 校验客户端 ES256 设备签名 */
function verifyDeviceSignature(pubKeyB64, keyId, payload, sigB64) {
    try {
        const der = Buffer.from(pubKeyB64, 'base64');
        if (sha256Hex(der).slice(0, 32) !== keyId) return false;
        const key = createPublicKey({ key: der, format: 'der', type: 'spki' });
        const sig = Buffer.from(sigB64, 'base64');
        return ecVerify('sha256', Buffer.from(payload, 'utf8'), { key, dsaEncoding: 'der' }, sig);
    } catch {
        return false;
    }
}

function encryptManifest(plaintextJson, nonce) {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', AES_KEY, iv);
    const ct = Buffer.concat([cipher.update(plaintextJson, 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();

    const ts = Math.floor(Date.now() / 1000);
    const dataB64 = ct.toString('base64');
    const ivB64 = iv.toString('base64');
    const tagB64 = tag.toString('base64');

    const sigData = ['1', ts, nonce, ivB64, tagB64, dataB64].join('|');
    const sig = createHmac('sha256', SIG_KEY).update(sigData, 'utf8').digest('base64');

    return { v: 1, ts, nonce, iv: ivB64, tag: tagB64, data: dataB64, sig };
}

function checkIpQuota(ip) {
    const now = Date.now();
    let e = ipHits.get(ip);
    if (!e || e.reset < now) {
        e = { count: 0, reset: now + IP_WINDOW_MS };
        ipHits.set(ip, e);
    }
    e.count += 1;
    return e.count <= IP_MAX_PER_WINDOW;
}

const server = Bun.serve({
    port: PORT,
    hostname: HOSTNAME,
    async fetch(req) {
        const url = new URL(req.url);
        if (url.pathname !== '/check' && url.pathname !== '/api/update/check') {
            return json({ ok: false, code: 'not_found' }, 404);
        }
        if (req.method !== 'GET' && req.method !== 'HEAD') {
            return json({ ok: false, code: 'method_not_allowed' }, 405);
        }

        // ① 客户端密钥（nginx 已校验，这里做纵深防御）
        if (req.headers.get('x-lianyu-key') !== APP_KEY) {
            return json({ ok: false, code: 'client_not_allowed' }, 403);
        }

        // 频率配额（取 nginx 透传的真实 IP）
        const ip =
            (req.headers.get('x-forwarded-for') ?? '').split(',')[0].trim() ||
            req.headers.get('x-real-ip') ||
            'unknown';
        if (!checkIpQuota(ip)) {
            return json({ ok: false, code: 'rate_limited', retryAfter: 3600 }, 429);
        }

        // ② 时间窗
        const tsRaw = req.headers.get('x-lianyu-ts');
        const ts = Number(tsRaw);
        const nowSec = Math.floor(Date.now() / 1000);
        if (!tsRaw || !Number.isFinite(ts) || Math.abs(nowSec - ts) > MAX_SKEW_SEC) {
            return json({ ok: false, code: 'clock_skew' }, 401);
        }

        // ③ nonce 去重
        const nonce = req.headers.get('x-lianyu-nonce') ?? '';
        if (!/^[0-9a-f]{16,64}$/i.test(nonce)) {
            return json({ ok: false, code: 'bad_nonce' }, 400);
        }
        if (seenNonces.has(nonce)) {
            return json({ ok: false, code: 'replay' }, 409);
        }

        // ④ 设备签名
        const deviceId = req.headers.get('x-lianyu-device-id') ?? '';
        const keyId = req.headers.get('x-lianyu-key-id') ?? '';
        const pubKey = req.headers.get('x-lianyu-pub') ?? '';
        const sig = req.headers.get('x-lianyu-sig') ?? '';
        if (!deviceId || !keyId || !pubKey || !sig) {
            return json({ ok: false, code: 'unsigned' }, 401);
        }
        const bodyHash = req.headers.get('x-lianyu-body-sha256') || EMPTY_SHA256;
        const payload = [
            SIGN_PREFIX,
            'GET',
            '/api/update/check',
            bodyHash,
            String(ts),
            nonce,
            APP_KEY,
            deviceId,
        ].join('\n');
        if (!verifyDeviceSignature(pubKey, keyId, payload, sig)) {
            return json({ ok: false, code: 'bad_signature' }, 403);
        }

        // 通过校验后才登记 nonce，避免失败请求污染
        seenNonces.set(nonce, Date.now() + NONCE_TTL_MS);

        // ⑤ 组装清单 + 加密返回
        const manifest = loadManifest();
        if (!manifest) {
            return json({ ok: false, code: 'manifest_unavailable' }, 503);
        }
        const meta = apkMeta(manifest.versionName);
        if (!meta) {
            return json({ ok: false, code: 'apk_unavailable' }, 503);
        }
        const dl = signedDownloadUrl(manifest.versionName);

        const payloadObj = {
            versionCode: Number(manifest.versionCode ?? 0),
            versionName: String(manifest.versionName ?? ''),
            minVersionCode: Number(manifest.minVersionCode ?? 0),
            forceUpdate: Boolean(manifest.forceUpdate ?? false),
            updateLog: String(manifest.updateLog ?? ''),
            publishDate: String(manifest.publishDate ?? ''),
            apkUrl: dl.url,
            apkUrlExpiresAt: dl.expiresAt,
            apkSize: meta.size,
            apkSha256: meta.sha256,
            mirrors: Array.isArray(manifest.mirrors) ? manifest.mirrors : [],
        };

        const body = encryptManifest(JSON.stringify(payloadObj), nonce);
        return json(body, 200, { 'X-LianYu-Encrypted': 'aes-256-gcm' });
    },
});

console.log(
    `[lianyu-update] listening on http://${HOSTNAME}:${server.port} dir=${DIR} dlSecret=${DL_SECRET ? 'ok' : 'MISSING'}`
);
