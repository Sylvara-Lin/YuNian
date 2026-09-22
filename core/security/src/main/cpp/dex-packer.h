#ifndef YUNIAN_DEX_PACKER_H
#define YUNIAN_DEX_PACKER_H

#include <cstdint>
#include <cstddef>
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#pragma GCC visibility push(hidden)

/* ================================================================
 * DEX Packer — Whole-DEX encryption + in-memory loading
 * ================================================================
 *
 * The business DEX is encrypted (SM4-ECB) at build time and
 * embedded as a C byte array (g_vmp_payload[]). At runtime:
 *   1. SM4 decrypt → plaintext DEX in heap
 *   2. CRC32 verify
 *   3. Load via InMemoryDexClassLoader (JNI)
 *
 * Security: the plaintext DEX NEVER touches disk.
 * ================================================================ */

/* Encrypted payload — embedded as C array.
 * Structure: [256-byte header with scattered key] + [SM4-ECB encrypted DEX].
 * VMP_HEADER_SIZE (256) must match gen_payload_cpp.py. */
extern const uint8_t g_vmp_payload[];
extern const uint32_t g_vmp_payload_size;  // total size: header + encrypted DEX

/* SM4 key: no longer a separate extern — key bytes are scattered in
 * g_vmp_payload[0..VMP_HEADER_SIZE-1] at positions derived from XOR_KEY_SEED.
 * See scatter_key_positions() in dex-packer.cpp. */

/* CRC32 of the decrypted DEX — set at build time, verified at runtime */
extern const uint32_t g_vmp_payload_crc32;

/* ----------------------------------------------------------------
 * Public API
 * ---------------------------------------------------------------- */

/**
 * Decrypt the embedded DEX payload into a caller-provided buffer.
 * @param out     Output buffer (must be g_vmp_payload_size bytes)
 * @param out_cap Capacity of output buffer
 * @return  0 on success, -1 on decrypt failure, -2 on CRC mismatch
 *
 * Caller is responsible for freeing `out` after use.
 */
int dex_packer_decrypt(uint8_t* out, size_t out_cap);

/**
 * Load the encrypted business DEX via InMemoryDexClassLoader.
 * Called from Java (YuNianShellApplication.attachBaseContext).
 *
 * @param env      JNI environment
 * @param ctx      Android Context (for getClassLoader / getCacheDir)
 * @param appClass Real Application class name (e.g. "com.yunian.ai.YuNianApplication")
 * @return 0 on success, negative on failure
 */
int dex_packer_load(JNIEnv* env, jobject ctx, const char* appClass);

/**
 * JNI entry point for the Java method NativeBridge.nativeLoadPayload
 * (name randomized per build via VMP_SHELL_LOAD_PAYLOAD in g_vmp_config.h).
 * Called via RegisterNatives in JNI_OnLoad or direct JNI naming convention.
 */
jint native_load_payload_entry(JNIEnv* env, jclass clazz, jobject context, jstring appClassName);

#pragma GCC visibility pop

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_DEX_PACKER_H */
