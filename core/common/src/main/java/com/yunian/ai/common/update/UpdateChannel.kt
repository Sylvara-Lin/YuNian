package com.yunian.ai.common.update

import java.security.MessageDigest
import java.util.Base64

/**
 * 更新通道配置（仅对 LianYu 客户端开放）
 *
 * 服务端防线分五层，任一层不通过都无法取得版本清单：
 * 1. nginx 客户端密钥校验（[APP_KEY]）—— 拦掉普通扫描器与搜索引擎
 * 2. nginx 网关签名校验（由 [GATE_SECRET] 派生的滚动凭证）—— 拦掉只拿到域名就抓取的人
 * 3. 设备 ES256 签名校验 —— **核心防线**。服务器用请求附带的公钥验签，签名覆盖
 * ```
 *     「方法 / 路径 / 请求体摘要 / 时间戳 / 随机数 / 客户端密钥 / 设备号」，
 *     无法通过抓包重放或篡改内容绕过
 * ```
 * 4. 时间窗 ±5 分钟 + 随机数去重 —— 抗重放
 * 5. 每 IP 每小时配额 —— 抗刷量
 *
 * 响应体为 AES-256-GCM 密文 + HMAC-SHA256 签名，密钥由 [APP_KEY] 派生。 即使中间人截获响应也无法解密，更无法伪造清单（客户端验签失败即整体拒绝）。
 *
 * 安全边界：内嵌常量（[APP_KEY] / [GATE_SECRET]）可被逆向提取，其作用是 「区分客户端与通用抓取工具」而非密码学身份认证。真正不可绕过的部分需要 设备硬件证明（Play
 * Integrity / 强认证）或服务端设备白名单。当前实际最强的 防线是「设备签名 + 时间窗 + 频率配额 + 短时下载地址」。
 */
object UpdateChannel {

    /** 客户端标识密钥，与服务器 `/root/lianyu-update/secrets.env` 的 UPDATE_APP_KEY 一致 */
    const val APP_KEY = "lianyu-app-k1"

    /**
     * 网关签名密钥，对应服务器 `/root/lianyu-update/secrets.env` 的 UPDATE_HMAC_SECRET。 服务端轮换该值即可吊销所有旧版客户端的访问权。
     */
    const val GATE_SECRET = "a03ebd502507032354571806fedf53baee3dae603d343960689450199d9a8e0d"

    /** 更新检查接口（HTTPS，经 nginx 网关校验后转发本地服务） */
    const val ENDPOINT = "https://lianyu.chat/api/update/check"

    /** 更新通道域名。用于证书锁定（SPKI pinning），同时供 [signedDownloadUrl] 与降级直连使用。 */
    const val HOST = "lianyu.chat"

    /**
     * 叶子证书公钥锁定（SPKI SHA-256）。
     *
     * 服务器已执行 `reuse_key = True`（见 `/etc/letsencrypt/renewal/lianyu.chat.conf`）， 因此续签时复用同一把 P-256 私钥
     * → 本锁定值长期稳定，不会每 90 天失效。
     *
     * ⚠️ 运维前提（改动前必读） 本锁定值**依赖服务器侧 `reuse_key = True` 持续生效**：
     * - 若该配置被取消、证书 lineage 被删除重建、或整机重装导致重新签发，
     * ```
     *    叶子公钥会变更 → 所有在用客户端将无法检查更新（表现为网络错误）。
     * ```
     * - 该情况下**不通过放宽锁定来修复**，而是走**升级包兜底**：
     * ```
     *    在官网提供新版 APK 供用户手动安装，新版内置新的锁定值。
     * ```
     * - 因此每次变更证书密钥后，**必须同步更新本常量并发布新版本**。
     *
     * 之所以能接受这个前提：退路锁定 [PIN_BACKUP_ROOT] / [PIN_BACKUP_ROOT_X2] 已覆盖"仅换叶子、签发链不变"的常见情况（如取消 reuse_key
     * 后仅换叶子密钥）。 只有连签发链一起变（换 CA / 重新初始化 ACME 账户）才会真正打挂客户端。
     */
    const val PIN_LEAF = "sha256/EdNPiXsrS2YjphJkV8gtBcb0XbRoZNm+SsovNMZfyf0="

    /**
     * 退路锁定：Let's Encrypt 中间证书「CN = ISRG Root YE」的公钥。 万一叶子密钥被更换（reuse_key 失效 / 换 CA），只要仍由该中间证书签发，
     * 客户端不会因为唯一锁定值失配而彻底无法检查更新。
     */
    const val PIN_BACKUP_ROOT = "sha256/sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w="

    /**
     * 退路锁定：Let's Encrypt 长期根证书「ISRG Root X2」的公钥。 用于应对未来 LE 调整签发链（中间证书改名/换根）导致 [PIN_BACKUP_ROOT]
     * 失配的情况。
     */
    const val PIN_BACKUP_ROOT_X2 = "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="

    /** 签名载荷前缀，须与服务器 server.mjs 一致 */
    const val SIG_PREFIX = "lianyu-update-v1"

    /** 网关凭证时间槽长度（秒）：每 10 分钟滚动一次，服务端无需签发或续签 */
    private const val GATE_SLOT_SEC = 600L

    /**
     * 计算当前时间槽的网关凭证。
     *
     * 取「下一个时间槽起点」为过期时间，使凭证在整个槽内稳定有效， 且完全由客户端自行推进 —— 避免"凭证过期导致 App 无法检查更新"。
     */
    fun gateCredential(nowSec: Long = System.currentTimeMillis() / 1000): GateCredential {
        val expires = (nowSec / GATE_SLOT_SEC + 1) * GATE_SLOT_SEC
        val raw = "$SIG_PREFIX|$expires|/api/update/check|$GATE_SECRET"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        val md5 =
                Base64.getEncoder()
                        .encodeToString(digest)
                        .replace('+', '-')
                        .replace('/', '_')
                        .trimEnd('=')
        return GateCredential(md5, expires)
    }

    /** 带网关凭证的完整请求地址 */
    fun signedEndpoint(nowSec: Long = System.currentTimeMillis() / 1000): String {
        val c = gateCredential(nowSec)
        return "$ENDPOINT?md5=${c.md5}&expires=${c.expires}"
    }

    data class GateCredential(val md5: String, val expires: Long)
}
