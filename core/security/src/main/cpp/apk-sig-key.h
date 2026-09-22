/**
 * APK 签名密钥派生 — 头文件
 */
#ifndef APK_SIG_KEY_H
#define APK_SIG_KEY_H

#include <jni.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 从 APK 签名证书派生解密密钥（360 方式）。
 *
 * @param env      JNI 环境
 * @param ctx      Android Context (Application 或 Activity)
 * @param out_key  输出密钥缓冲区
 * @param key_len  密钥长度 (推荐 16 或 32)
 * @return         JNI_TRUE 成功, JNI_FALSE 签名获取失败
 */
jboolean derive_key_from_apk_sig(JNIEnv* env, jobject ctx,
                                  uint8_t* out_key, size_t key_len);

/**
 * 读取 APK 签名证书 DER 并计算标准 SHA-256。
 * 用于反重打包校验（与 g_apk_digests_obs 中嵌入的证书哈希对比）。
 */
jboolean get_apk_cert_sha256(JNIEnv* env, jobject ctx, uint8_t out_sha256[32]);

#ifdef __cplusplus
}
#endif

#endif
