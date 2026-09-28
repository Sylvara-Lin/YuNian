/** APK 签名绑定密钥派生 — 360 级别反重打包
 *
 * 编译: ndk-build (加入 core/security/src/main/cpp/Android.mk)
 */
#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <stdint.h>
#include <string.h>
#include "sm-cipher.h"
#include "hmac_sha256.h"

// ============================================================
// SHA-256 wrapper (HMAC-SHA256 with empty key)
// ============================================================
static void sha256_hash(const uint8_t* input, size_t len, uint8_t output[32]) {
    static const uint8_t empty_key[32] = {0};
    hmac_sha256(empty_key, 0, input, len, output);
}

// ============================================================
// 获取 APK 签名证书 DER 字节
// ============================================================
static jboolean get_apk_signature_bytes(JNIEnv* env, jobject ctx,
                                         uint8_t** out_bytes, jsize* out_len) {
    jclass ctx_cls = env->GetObjectClass(ctx);
    jmethodID get_pm = env->GetMethodID(ctx_cls, "getPackageManager",
        "()Landroid/content/pm/PackageManager;");
    jobject pm = env->CallObjectMethod(ctx, get_pm);
    env->DeleteLocalRef(ctx_cls);
    if (!pm) return JNI_FALSE;

    jclass pm_cls = env->GetObjectClass(pm);
    jmethodID get_pkg_info = env->GetMethodID(pm_cls, "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    jstring pkg = env->NewStringUTF("com.yunian.ai");
    jobject pkg_info = env->CallObjectMethod(pm, get_pkg_info,
        pkg, (jint)0x00000040);
    env->DeleteLocalRef(pm_cls);
    env->DeleteLocalRef(pkg);
    if (!pkg_info) return JNI_FALSE;

    jclass pi_cls = env->GetObjectClass(pkg_info);
    jfieldID sigs_field = env->GetFieldID(pi_cls, "signatures",
        "[Landroid/content/pm/Signature;");
    jobjectArray sigs = (jobjectArray)env->GetObjectField(pkg_info, sigs_field);
    env->DeleteLocalRef(pi_cls);
    if (!sigs) return JNI_FALSE;

    jobject sig0 = env->GetObjectArrayElement(sigs, 0);
    env->DeleteLocalRef(sigs);
    if (!sig0) return JNI_FALSE;

    jclass sig_cls = env->GetObjectClass(sig0);
    jmethodID to_byte_array = env->GetMethodID(sig_cls, "toByteArray", "()[B");
    jbyteArray cert_bytes = (jbyteArray)env->CallObjectMethod(sig0, to_byte_array);
    env->DeleteLocalRef(sig_cls);
    if (!cert_bytes) return JNI_FALSE;

    jsize cert_len = env->GetArrayLength(cert_bytes);
    *out_bytes = (uint8_t*)malloc(cert_len);
    if (!*out_bytes) {
        env->DeleteLocalRef(cert_bytes);
        return JNI_FALSE;
    }
    env->GetByteArrayRegion(cert_bytes, 0, cert_len, (jbyte*)*out_bytes);
    *out_len = cert_len;
    env->DeleteLocalRef(cert_bytes);
    return JNI_TRUE;
}

// ============================================================
// 标准 SHA-256（证书指纹 / 反重打包校验）
// ============================================================
static void sha256_raw_digest(const uint8_t* input, size_t len, uint8_t output[32]) {
    sha256_ctx ctx;
    sha256_init(&ctx);
    sha256_update(&ctx, input, len);
    sha256_final(&ctx, output);
}

// ============================================================
// 密钥派生核心: SM3(证书) → 解密密钥
// ============================================================
extern "C" {
jboolean get_apk_cert_sha256(JNIEnv* env, jobject ctx, uint8_t out_sha256[32]) {
    if (!out_sha256) return JNI_FALSE;

    uint8_t* cert_bytes = NULL;
    jsize cert_len = 0;
    if (!get_apk_signature_bytes(env, ctx, &cert_bytes, &cert_len) || !cert_bytes || cert_len <= 0) {
        return JNI_FALSE;
    }

    sha256_raw_digest(cert_bytes, (size_t)cert_len, out_sha256);
    free(cert_bytes);
    return JNI_TRUE;
}

jboolean derive_key_from_apk_sig(JNIEnv* env, jobject ctx,
                                  uint8_t* out_key, size_t key_len) {
    uint8_t* cert_bytes = NULL;
    jsize cert_len = 0;

    if (!get_apk_signature_bytes(env, ctx, &cert_bytes, &cert_len)) {
        return JNI_FALSE;
    }

    uint8_t seed[32];
    sha256_hash(cert_bytes, (size_t)cert_len, seed);
    free(cert_bytes);

    static const char SALT[] = "lianyu_apk_sig_v2_kiwi";
    size_t salt_len = sizeof(SALT) - 1;

    uint8_t buf[128];
    uint8_t counter = 0;
    size_t generated = 0;
    while (generated < key_len) {
        memcpy(buf, seed, 32);
        memcpy(buf + 32, SALT, salt_len);
        buf[32 + salt_len] = counter++;

        uint8_t hash[32];
        sha256_hash(buf, 32 + salt_len + 1, hash);

        size_t copy = key_len - generated;
        if (copy > 32) copy = 32;
        memcpy(out_key + generated, hash, copy);
        generated += copy;
    }

    memset(seed, 0, sizeof(seed));
    memset(buf, 0, sizeof(buf));
    return JNI_TRUE;
}
} // extern "C"
