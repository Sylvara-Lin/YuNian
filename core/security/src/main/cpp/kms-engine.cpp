/*
 * kms-engine.cpp — Key Management System Engine Implementation
 *
 * Five-tier hierarchical key system (v2.0):
 *   MK (WB tables) → DK_ephemeral (NEON, per-op) → SK (tiered)
 *                    → BK (SM4 deterministic) → AK (TEE only)
 *
 * Anti-analysis: OLLVM-style control flow flattening.
 * All cache operations use dc civac (clean+invalidate, per R2 risk fix).
 */

#include "kms-engine.h"
#include "whitebox-aes.h"
#include "sm-cipher.h"
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <sys/syscall.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/random.h>
#include <android/log.h>
#include "obfuscate.h"

#ifdef PRODUCTION_BUILD
#define KMS_LOGV(...) ((void)0)
#define KMS_LOGE(...) ((void)0)
#else
#define KMS_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "YuNian-KMS", __VA_ARGS__)
#define KMS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "YuNian-KMS", __VA_ARGS__)
#endif

#pragma GCC visibility push(hidden)

static void kbkdf_sm3_v2(uint8_t sk_out[32]);

/* KMS global state (v2.0 — g_dk removed, DK is ephemeral NEON-only) */
static volatile int g_kms_state = KMS_STATE_UNINIT;
static uint8_t g_sk_bulk[KMS_SK_SIZE] __attribute__((aligned(64)));  /* SK_bulk */
static uint8_t g_nonce[KMS_NONCE_SIZE] __attribute__((aligned(64))) = {0};
uint8_t g_kms_apk_bound_key[32] __attribute__((aligned(64))) = {0};  /* APK signature binding */
static volatile uint32_t g_session_counter = 0;
static volatile int g_dk_loaded = 0;   /* 1 = DK loaded in NEON regs */

/* v2.0 additions */
static uint8_t g_boot_salt[KMS_SALT_SIZE] __attribute__((aligned(64))); /* boot-time salt */
static uint8_t g_boot_entropy[16] __attribute__((aligned(64))); /* extra entropy from getrandom (FIX #3) */
static volatile uint64_t g_dk_lifetime_start = 0;    /* monotonic ms */
static volatile uint32_t g_bk_counter = 0;            /* BK metadata counter */
static volatile uint32_t g_sk_counters[4] = {0};      /* per-tier session counters */
static volatile uint64_t g_sk_bulk_rotation_time = 0; /* monotonic ms, for SK_bulk rotation (FIX #1) */
#define KMS_SK_BULK_ROTATION_INTERVAL_MS 3600000ULL  /* 1 hour rotation interval */

/* Secure memory wipe with memory barrier */
static void secure_wipe(volatile void *p, size_t n) {
    OBF_BARRIER(49);
    volatile unsigned char *v = (volatile unsigned char *) p;
    for (size_t i = 0; i < n; i++) v[i] = 0;
#if defined(__arm__) || defined(__aarch64__)
    __asm__ __volatile__("dmb sy" ::: "memory");
#else
    __sync_synchronize();
#endif
}

/*
 * Flush a cacheline-aligned buffer from L1/L2 cache to ensure
 * key material doesn't persist in cache hierarchy after use.
 *
 * ARM64: dc civac cleans + invalidates VA to point of coherency.
 *        (dc cvac only cleans — residue remains. R2 risk fix.)
 * ARM32: via MCR p15, 0, r0, c7, c14, 1 (clean+invalidate).
 *
 * @param addr  Cacheline-aligned address (or start of region)
 * @param size  Region size in bytes
 */
static void cacheline_invalidate(void *addr, size_t size) {
    OBF_BARRIER(70);
    uintptr_t start = (uintptr_t) addr & ~(uintptr_t) 63;
    uintptr_t end = (uintptr_t) addr + size;
#if defined(__aarch64__)
    for (uintptr_t a = start; a < end; a += 64) {
        __asm__ __volatile__("dc civac, %0" : : "r"(a) : "memory");
    }
    __asm__ __volatile__("dsb sy" ::: "memory");
#elif defined(__ARM_ARCH_7A__)
    for (uintptr_t a = start; a < end; a += 64) {
        __asm__ __volatile__("mcr p15, 0, %0, c7, c14, 1" : : "r"(a) : "memory");
    }
    __asm__ __volatile__("dsb" ::: "memory");
#else
    (void) addr;
    (void) size;
    (void) start;
    (void) end;
#endif
}

/* Backward-compat alias */
static void cacheline_flush(void *addr, size_t size) {
    OBF_BARRIER(89);
    cacheline_invalidate(addr, size);
}

/*
 * Wipe ALL NEON/FP registers (q0-q7) without writing back to memory.
 * Uses movi to directly overwrite registers — no cache ops needed
 * because NEON registers aren't cached in the traditional sense.
 *
 * This is the ONLY correct way to clear key material from NEON.
 *
 * After this call: q0-q3 = 0, q4-q7 = random noise (anti-analysis decoy).
 */
static void neon_wipe_all_keys(void) {
    OBF_BARRIER(102);
#if defined(__aarch64__)
    __asm__ __volatile__(
        "movi v0.16b, #0x00\n"    /* q0 = 0  — wipe DK */
        "movi v1.16b, #0x00\n"    /* q1 = 0  — wipe DK */
        "movi v2.16b, #0x00\n"    /* q2 = 0  — wipe BK */
        "movi v3.16b, #0x00\n"    /* q3 = 0  — wipe temp */
        "movi v4.16b, #0xAD\n"    /* decoys */
        "movi v5.16b, #0xBE\n"
        "movi v6.16b, #0xEF\n"
        "movi v7.16b, #0xCA\n"
        "dmb sy\n"
        ::: "v0","v1","v2","v3","v4","v5","v6","v7","memory"
    );
#elif defined(__ARM_ARCH_7A__) && defined(__ARM_NEON__)
    __asm__ __volatile__(
        "vmov.i32 q0, #0\n"
        "vmov.i32 q1, #0\n"
        "vmov.i32 q2, #0\n"
        "vmov.i32 q3, #0\n"
        "vmov.i32 q4, #0xAD\n"
        "vmov.i32 q5, #0xBE\n"
        "vmov.i32 q6, #0xEF\n"
        "vmov.i32 q7, #0xCA\n"
        "dsb\n"
        ::: "q0","q1","q2","q3","q4","q5","q6","q7","memory"
    );
#endif
}

/*
 * Store partial DK from NEON to buffer (first 16 bytes from q0).
 * Used for BK derivation where only DK[:16] is needed.
 * After use, immediately cacheline_invalidate the buffer.
 */
static void neon_store_half_key(uint8_t key[16]) {
    OBF_BARRIER(137);
#if defined(__aarch64__)
    __asm__ __volatile__(
        "str q0, [%0]"
        :
        : "r"(key)
        : "v0", "memory"
    );
#elif defined(__ARM_ARCH_7A__) && defined(__ARM_NEON__)
    __asm__ __volatile__(
        "vst1.32 {q0}, [%0]"
        :
        : "r"(key)
        : "q0", "memory"
    );
#else
    (void) key;
#endif
}

/*
 * Load 32 bytes of key material into ARM64 NEON registers q0/q1.
 * After this call, the source buffer can (and should) be wiped.
 *
 * The DK lives in registers until explicitly destroyed — it is
 * NEVER spilled to the stack in properly optimized builds.
 *
 * ARM64: ldp (load pair) loads 16 bytes per instruction into q0, q1.
 */
static void neon_load_key(const uint8_t key[32]) {
    OBF_BARRIER(166);
#if defined(__aarch64__)
    __asm__ __volatile__(
        "ldp q0, q1, [%0]"
        :
        : "r"(key)
        : "v0", "v1", "memory"
    );
#elif defined(__ARM_ARCH_7A__) && defined(__ARM_NEON__)
    __asm__ __volatile__(
        "vld1.32 {q0, q1}, [%0]"
        :
        : "r"(key)
        : "q0", "q1", "memory"
    );
#else
    (void) key;
#endif
}

/*
 * Store NEON registers q0/q1 back to a 32-byte memory buffer.
 * Used only when the key must be re-homed to memory (key rotation, etc.).
 * After use, immediately wipe the buffer and/or re-load to NEON.
 */
static void neon_store_key(uint8_t key[32]) {
    OBF_BARRIER(191);
#if defined(__aarch64__)
    __asm__ __volatile__(
        "stp q0, q1, [%0]"
        :
        : "r"(key)
        : "v0", "v1", "memory"
    );
#elif defined(__ARM_ARCH_7A__) && defined(__ARM_NEON__)
    __asm__ __volatile__(
        "vst1.32 {q0, q1}, [%0]"
        :
        : "r"(key)
        : "q0", "q1", "memory"
    );
#else
    (void) key;
#endif
}

/* Public: load DK from NEON registers into a 32-byte buffer.
 * Used for backward compat and DK derivation.
 * After use, caller must cacheline_invalidate + secure_wipe the buffer. */
void kms_load_dk_to_neon(void) {
    OBF_BARRIER(214);
    /* v2.0: DK is now ephemeral — this function is a no-op for old callers.
     * New code should use kms_derive_dk_ephemeral() instead. */
}

/* SM3-based KBKDF: SK = SM3(DK || nonce || counter)
 * Uses SM3 from sm-cipher.h */
static void kbkdf_sm3(const uint32_t dk[8], const uint8_t nonce[16],
                      uint32_t counter, uint8_t sk_out[32]) {
    volatile int state = 0;
    volatile int done = 0;
    uint8_t input[64];  /* DK(32) + nonce(16) + counter(4) + padding */
    volatile int i = 0;

    while (!done) {
        switch (state) {
            case 0:
                /* Build SM3 input: DK || nonce || counter */
                i = 0;
                state = 1;
                break;
            case 1:
                if (i >= 8) {
                    state = 2;
                    break;
                }
                /* Copy DK as big-endian bytes */
                input[i * 4 + 0] = (dk[i] >> 24) & 0xFF;
                input[i * 4 + 1] = (dk[i] >> 16) & 0xFF;
                input[i * 4 + 2] = (dk[i] >> 8) & 0xFF;
                input[i * 4 + 3] = dk[i] & 0xFF;
                i++;
                state = 1;
                break;
            case 2:
                memcpy(input + 32, nonce, 16);
                input[48] = (counter >> 24) & 0xFF;
                input[49] = (counter >> 16) & 0xFF;
                input[50] = (counter >> 8) & 0xFF;
                input[51] = counter & 0xFF;
                memset(input + 52, 0, 12);  /* padding */
                state = 3;
                break;
            case 3: {
                /* Use SM3 to hash the input */
                extern void sm3_hash(const uint8_t *msg, size_t msglen, uint8_t digest[32]);
                uint8_t hash_input[52];
                for (int j = 0; j < 52; j++) hash_input[j] = input[j];
                sm3_hash(hash_input, (size_t) 52, sk_out);
                secure_wipe(hash_input, 52);
                done = 1;
                state = 4;
                break;
            }
            case 4:
                break;
            default:
                state = 0;
                break;
        }
    }
}

static int kms_getrandom(void *buf, size_t len) {
    OBF_BARRIER(269);
    ssize_t ret = syscall(SYS_getrandom, buf, len, GRND_NONBLOCK);
    if (ret == (ssize_t) len) return 0;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return -1;
    size_t n = 0;
    while (n < len) {
        ssize_t r = read(fd, (uint8_t *) buf + n, len - n);
        if (r <= 0) {
            close(fd);
            return -1;
        }
        n += (size_t) r;
    }
    close(fd);
    return 0;
}

int kms_init(void) {
    OBF_BARRIER(284);
    volatile int state = 0;
    volatile int result = KMS_OK;

    while (result == KMS_OK) {
        switch (state) {
            case 0:
                if (g_kms_state != KMS_STATE_UNINIT) {
                    result = KMS_ERR_STATE;
                    break;
                }
                state = 2;  /* skip wb_aes_init — called earlier in NativeBridge.initialize */
                break;
            case 2: {
                /* Generate boot salt for BK metadata (getrandom syscall) */
                kms_getrandom(g_boot_salt, KMS_SALT_SIZE);
                /* FIX #3: Extra entropy for DK derivation */
                kms_getrandom(g_boot_entropy, 16);
                /* Generate session nonce */
                kms_getrandom(g_nonce, KMS_NONCE_SIZE);
                state = 3;
                break;
            }
            case 3:
                g_session_counter = 0;
                g_bk_counter = 0;
                for (int t = 0; t < 4; t++) g_sk_counters[t] = 0;
                /* Derive initial DK (will be wiped after SK_bulk derivation) */
                if (kms_derive_dk_ephemeral() != KMS_OK) {
                    KMS_LOGV("KMS derive DK failed — WB-AES may be tainted");
                    result = KMS_ERR_CRYPTO;
                    break;
                }
                state = 4;
                break;
            case 4: {
                /* Derive SK_bulk at boot (for database/storage use all session) */
                uint8_t sk_bulk[KMS_SK_SIZE] __attribute__((aligned(64)));
                kbkdf_sm3_v2(sk_bulk);
                memcpy(g_sk_bulk, sk_bulk, KMS_SK_SIZE);
                secure_wipe(sk_bulk, KMS_SK_SIZE);
                /* Wipe initial DK */
                neon_wipe_all_keys();
                g_dk_loaded = 0;
                /* FIX #1: Initialize rotation timestamp */
                {
                    struct timespec _its;
                    clock_gettime(CLOCK_MONOTONIC, &_its);
                    g_sk_bulk_rotation_time = (uint64_t) _its.tv_sec * 1000ULL
                                              + (uint64_t) _its.tv_nsec / 1000000ULL;
                }
                g_kms_state = KMS_STATE_READY;
                KMS_LOGV("KMS v2.0 initialized, boot salt + SK_bulk derived");
                result = 1;  /* exit while loop */
                state = 5;
                break;
            }
            case 5:
                break;
            default:
                state = 0;
                break;
        }
    }
    return (result == 1) ? KMS_OK : result;
}

int kms_derive_session_key(uint8_t sk_out[KMS_SK_SIZE]) {
    OBF_BARRIER(335);
    volatile int state = 0;
    volatile int result = KMS_OK;

    while (result == KMS_OK) {
        switch (state) {
            case 0:
                if (g_kms_state != KMS_STATE_READY) {
                    result = KMS_ERR_STATE;
                    break;
                }
                if (!g_dk_loaded) {
                    if (kms_derive_dk_ephemeral() != KMS_OK) {
                        result = KMS_ERR_STATE;
                        break;
                    }
                }
                state = 1;
                break;
            case 1: {
                uint32_t counter = g_session_counter;
                if (counter >= KMS_MAX_SESSIONS) {
                    result = KMS_ERR_EXHAUSTED;
                    break;
                }
                state = 2;
                break;
            }
            case 2: {
                uint32_t counter = g_session_counter;
                /* Load DK from NEON registers to temporary stack buffer */
                uint32_t dk_from_neon[8] __attribute__((aligned(64)));
                neon_store_key((uint8_t *) dk_from_neon);
                /* Derive SK using the NEON-fresh DK */
                kbkdf_sm3((const uint32_t *) dk_from_neon, g_nonce, counter, sk_out);
                /* Wipe the stack buffer immediately */
                secure_wipe(dk_from_neon, 32);
                cacheline_flush(dk_from_neon, 32);
                result = 1; /* exit loop */
                state = 3;
                break;
            }
            case 3:
                break;
            default:
                state = 0;
                break;
        }
    }
    return result;
}

void kms_destroy_session_key(uint8_t sk[KMS_SK_SIZE]) {
    OBF_BARRIER(373);
    if (!sk) return;
    secure_wipe(sk, KMS_SK_SIZE);
}

void kms_destroy_keychain(void) {
    OBF_BARRIER(378);
    volatile int state = 0;

    while (1) {
        switch (state) {
            case 0:
                secure_wipe((void *) g_nonce, sizeof(g_nonce));
                secure_wipe((void *) g_boot_salt, sizeof(g_boot_salt));
                secure_wipe((void *) g_boot_entropy, sizeof(g_boot_entropy));
                state = 1;
                break;
            case 1:
                g_dk_loaded = 0;
                g_session_counter = 0;
                /* FIX #7: Invalidate SM4 key schedule cache */
                extern void sm4_invalidate_cache(void);
                sm4_invalidate_cache();
                g_kms_state = KMS_STATE_DESTROYED;
                wb_aes_wipe_keys();
                KMS_LOGV("KMS keychain destroyed");
                state = 2;
                break;
            case 2:
                return;
            default:
                state = 0;
                break;
        }
    }
}

int kms_get_status(void) {
    OBF_BARRIER(402);
    volatile int state = 0;
    volatile int result = 0;

    while (!state) {
        switch (g_kms_state) {
            case KMS_STATE_UNINIT:
                result = 0;
                state = 1;
                break;
            case KMS_STATE_READY:
                result = 1;
                state = 1;
                break;
            case KMS_STATE_DESTROYED:
                result = -1;
                state = 1;
                break;
            default:
                result = -2;
                state = 1;
                break;
        }
    }
    return result;
}

/* ================================================================
 * V2.0 New Functions
 * ================================================================ */

/* V2 KBKDF: reads DK from NEON registers (not uint32_t array).
 * Used by both kms_init (for SK_bulk) and kms_derive_session_key_tiered. */
static void kbkdf_sm3_v2(uint8_t sk_out[32]) {
    OBF_BARRIER(423);
    uint8_t dk_bytes[32] __attribute__((aligned(64)));
    uint8_t input[52] __attribute__((aligned(64)));

    /* Extract DK from NEON q0-q1 to temp buffer */
    extern void neon_store_key(uint8_t key[32]);
    neon_store_key(dk_bytes);

    /* Build SM3 input: DK(32) || nonce(16) || counter(4) */
    memcpy(input, dk_bytes, 32);
    memcpy(input + 32, g_nonce, 16);
    uint32_t c = __sync_fetch_and_add(&g_session_counter, 1);
    input[48] = (c >> 24) & 0xFF;
    input[49] = (c >> 16) & 0xFF;
    input[50] = (c >> 8) & 0xFF;
    input[51] = c & 0xFF;

    sm3_hash(input, 52, sk_out);

    /* Wipe temp buffers */
    secure_wipe(dk_bytes, 32);
    cacheline_invalidate(dk_bytes, 32);
    secure_wipe(input, 52);
    cacheline_invalidate(input, 52);
}

/* ================================================================
 * DK_ephemeral — derive per-operation DK into NEON
 * ================================================================ */
int kms_derive_dk_ephemeral(void) {
    OBF_BARRIER(452);
    volatile int state = 0;
    volatile int result = KMS_OK;

    while (result == KMS_OK) {
        switch (state) {
            case 0:
                if (g_kms_state != KMS_STATE_READY && g_kms_state != KMS_STATE_UNINIT) {
                    result = KMS_ERR_STATE;
                    break;
                }
                state = 1;
                break;
            case 1: {
                /* Derive DK via whitebox AES: wb_aes_encrypt(ts || boot_entropy[0:12]) */
                /* FIX #3: Use 20 bytes total (96+ bits of entropy from getrandom) */
                uint8_t seed[16] __attribute__((aligned(64)));
                struct timespec _ts;
                clock_gettime(CLOCK_MONOTONIC, &_ts);
                uint64_t ts = (uint64_t) _ts.tv_sec * 1000000000ULL + (uint64_t) _ts.tv_nsec;
                memcpy(seed, &ts, 8);
                memcpy(seed + 8, g_boot_entropy, 8);
                /* XOR extra 8 bytes of entropy into the first 8 */
                for (int _ei = 0; _ei < 8; _ei++) seed[_ei] ^= g_boot_entropy[_ei + 8];

                uint8_t dk_first[16] __attribute__((aligned(64)));
                if (wb_aes_256_encrypt(seed, dk_first) != 0) {
                    result = KMS_ERR_CRYPTO;
                    break;
                }
                uint8_t dk_second[16] __attribute__((aligned(64)));
                if (wb_aes_256_encrypt(dk_first, dk_second) != 0) {
                    secure_wipe(dk_first, 16);
                    result = KMS_ERR_CRYPTO;
                    break;
                }
                uint8_t dk_full[32] __attribute__((aligned(64)));
                memcpy(dk_full, dk_first, 16);
                memcpy(dk_full + 16, dk_second, 16);

                neon_load_key(dk_full);
                cacheline_invalidate(dk_full, 32);
                secure_wipe(dk_full, 32);
                secure_wipe(dk_first, 16);
                secure_wipe(dk_second, 16);
                secure_wipe(seed, 16);

#if defined(__aarch64__)
                __asm__ __volatile__(
                    "movi v4.16b, #0x13\n"
                    "movi v5.16b, #0x37\n"
                    "movi v6.16b, #0x59\n"
                    "movi v7.16b, #0x7B\n"
                    ::: "v4","v5","v6","v7","memory"
                );
#endif

                g_dk_loaded = 1;
                {
                    struct timespec ts2;
                    clock_gettime(CLOCK_MONOTONIC, &ts2);
                    g_dk_lifetime_start = (uint64_t) ts2.tv_sec * 1000ULL
                                          + (uint64_t) ts2.tv_nsec / 1000000ULL;
                }
                /* FIX #7: Invalidate SM4 BK key cache — DK has changed */
                extern void sm4_invalidate_cache(void);
                sm4_invalidate_cache();
                result = 1;  /* exit while loop */
                state = 2;
                break;
            }
            case 2:
                break;
            default:
                state = 0;
                break;
        }
    }
    return (result == 1) ? KMS_OK : result;
}

/* ================================================================
 * BK — Blinding Key (deterministic SM4 derivation, 256-bit via SM3 expansion)
 * FIX #5: BK expanded to 256 bits using SM3
 * FIX #6: Per-operation nonce for forward secrecy
 * ================================================================ */
int kms_derive_bk(const uint8_t metadata[KMS_METADATA_SIZE],
                  const uint8_t nonce[KMS_NONCE_EXT_SIZE],
                  uint8_t bk_out[KMS_BK_SIZE]) {
    if (!g_dk_loaded) return KMS_ERR_STATE;

    /* Extract DK[0:16] from NEON q0 */
    uint8_t dk_half[16] __attribute__((aligned(64)));
    neon_store_half_key(dk_half);

    /* FIX #6: Build SM4 input = metadata || nonce[0:8] (24 bytes) */
    /* SM4 encrypts 16-byte blocks. Use first 16 bytes: metadata[0:16] XOR'd with nonce */
    uint8_t sm4_input[16] __attribute__((aligned(64)));
    memcpy(sm4_input, metadata, 16);
    /* Blend nonce into the input for forward secrecy */
    for (int _ni = 0; _ni < KMS_NONCE_EXT_SIZE; _ni++) {
        sm4_input[_ni] ^= nonce[_ni];
    }

    /* BK_128 = SM4_enc(DK[0:16], sm4_input) */
    uint8_t bk_128[16] __attribute__((aligned(64)));
    uint32_t sm4_rk[SM4_ROUNDS];
    sm4_key_schedule(dk_half, sm4_rk);
    sm4_encrypt_block(sm4_input, sm4_rk, bk_128);

    /* FIX #5: BK_256 = SM3(BK_128 || metadata) truncated to 32 bytes */
    uint8_t sm3_input[32] __attribute__((aligned(64)));
    memcpy(sm3_input, bk_128, 16);
    memcpy(sm3_input + 16, metadata, 16);
    sm3_hash(sm3_input, 32, bk_out);

    /* Cleanup */
    secure_wipe(bk_128, 16);
    secure_wipe(dk_half, 16);
    cacheline_invalidate(dk_half, 16);
    secure_wipe(sm4_input, 16);
    secure_wipe(sm3_input, 32);
    secure_wipe(sm4_rk, sizeof(sm4_rk));

    return KMS_OK;
}

/* ================================================================
 * Blind/Unblind — XOR with BK and BK'
 * ================================================================ */
int kms_blind_input(uint8_t *buf, size_t len,
                    const uint8_t metadata[KMS_METADATA_SIZE],
                    const uint8_t nonce[KMS_NONCE_EXT_SIZE],
                    uint8_t bk_prime[KMS_BK_SIZE]) {
    if (!buf || !metadata) return KMS_ERR_STATE;

    /* Derive BK (256-bit) */
    uint8_t bk[KMS_BK_SIZE] __attribute__((aligned(64)));
    if (kms_derive_bk(metadata, nonce, bk) != KMS_OK) return KMS_ERR_CRYPTO;

    /* Blind input: buf XOR BK (rotate through 32-byte BK) */
    for (size_t i = 0; i < len; i++) {
        buf[i] ^= bk[i % KMS_BK_SIZE];
    }

    /* Derive BK' = SM4_enc(BK[0:16], DK[16:32]) for output blinding */
    uint8_t dk_half2[16] __attribute__((aligned(64)));
    /* Extract DK[16:32] from NEON q1 */
#if defined(__aarch64__)
    __asm__ __volatile__("str q1, [%0]" : : "r"(dk_half2) : "v1","memory");
#endif
    uint32_t sm4_rk2[SM4_ROUNDS];
    sm4_key_schedule(dk_half2, sm4_rk2);
    /* Use first 16 bytes of BK as the block to encrypt for BK' */
    uint8_t bk_block[16] __attribute__((aligned(64)));
    memcpy(bk_block, bk, 16);
    sm4_encrypt_block(bk_block, sm4_rk2, bk_prime);

    secure_wipe(dk_half2, 16);
    cacheline_invalidate(dk_half2, 16);
    secure_wipe(sm4_rk2, sizeof(sm4_rk2));
    secure_wipe(bk, KMS_BK_SIZE);
    secure_wipe(bk_block, 16);

    return KMS_OK;
}

int kms_unblind_output(uint8_t *buf, size_t len,
                       const uint8_t metadata[KMS_METADATA_SIZE],
                       const uint8_t nonce[KMS_NONCE_EXT_SIZE],
                       const uint8_t bk_prime[KMS_BK_SIZE]) {
    if (!buf || !metadata || !bk_prime) return KMS_ERR_STATE;

    /* Re-derive BK (deterministic — same DK + same metadata + same nonce = same BK) */
    uint8_t bk[KMS_BK_SIZE] __attribute__((aligned(64)));
    if (kms_derive_bk(metadata, nonce, bk) != KMS_OK) return KMS_ERR_CRYPTO;

    /* Re-derive BK' and verify match */
    uint8_t dk_half2[16] __attribute__((aligned(64)));
#if defined(__aarch64__)
    __asm__ __volatile__("str q1, [%0]" : : "r"(dk_half2) : "v1","memory");
#endif
    uint32_t sm4_rk2[SM4_ROUNDS];
    sm4_key_schedule(dk_half2, sm4_rk2);
    uint8_t expected_bk_prime[KMS_BK_SIZE] __attribute__((aligned(64)));
    uint8_t bk_block[16] __attribute__((aligned(64)));
    memcpy(bk_block, bk, 16);
    sm4_encrypt_block(bk_block, sm4_rk2, expected_bk_prime);

    if (memcmp(expected_bk_prime, bk_prime, KMS_BK_SIZE) != 0) {
        /* BK' mismatch — data tampered or wrong session */
        secure_wipe(bk, KMS_BK_SIZE);
        secure_wipe(expected_bk_prime, KMS_BK_SIZE);
        secure_wipe(bk_block, 16);
        return KMS_ERR_CRYPTO;
    }

    /* Unblind: XOR with BK (same 32-byte BK used for input blinding) */
    for (size_t i = 0; i < len; i++) {
        buf[i] ^= bk[i % KMS_BK_SIZE];
    }

    secure_wipe(dk_half2, 16);
    cacheline_invalidate(dk_half2, 16);
    secure_wipe(sm4_rk2, sizeof(sm4_rk2));
    secure_wipe(bk, KMS_BK_SIZE);
    secure_wipe(expected_bk_prime, KMS_BK_SIZE);
    secure_wipe(bk_block, 16);

    return KMS_OK;
}

/* ================================================================
 * AK — Attestation (TEE-only, no software fallback)
 * ================================================================ */
int kms_attest_dk(const uint8_t dk_hash[SM3_DIGEST_SIZE],
                  uint8_t sig_out[64]) {
    (void) dk_hash;
    (void) sig_out;
    /* TEE attestation requires Android KeyStore interaction at Kotlin layer.
     * This native stub returns KMS_ERR_ATTEST — the Kotlin-side
     * HardwareKeyAttestor.kt will handle the actual KeyStore signing.
     *
     * NO software fallback. Without TEE, this always fails. */
    return KMS_ERR_ATTEST;
}

/* ================================================================
 * DK Lifetime — diagnostic
 * ================================================================ */
uint64_t kms_get_dk_lifetime_ms(void) {
    OBF_BARRIER(637);
    if (g_dk_lifetime_start == 0) return 0;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now = (uint64_t) ts.tv_sec * 1000ULL
                   + (uint64_t) ts.tv_nsec / 1000000ULL;
    return now - g_dk_lifetime_start;
}

/* ================================================================
 * NEON Wipe — public wrapper
 * ================================================================ */
void kms_wipe_neon_keys(void) {
    OBF_BARRIER(649);
    neon_wipe_all_keys();
    g_dk_loaded = 0;
    g_dk_lifetime_start = 0;
}

/* ================================================================
 * Salt Generation — getrandom() syscall fallback
 * ================================================================ */
void kms_generate_salt(uint8_t salt_out[KMS_SALT_SIZE]) {
    OBF_BARRIER(658);
    kms_getrandom(salt_out, KMS_SALT_SIZE);
}

/* ================================================================
 * Tiered Session Key Derivation
 * FIX #1: SK_bulk rotation — periodic re-derivation for forward secrecy
 * ================================================================ */
int kms_rotate_sk_bulk(void) {
    OBF_BARRIER(680);
    if (g_kms_state != KMS_STATE_READY) return KMS_ERR_STATE;

    /* Rotate SK_bulk: derive fresh DK, then fresh SK_bulk */
    if (kms_derive_dk_ephemeral() != KMS_OK) return KMS_ERR_CRYPTO;

    uint8_t new_sk_bulk[KMS_SK_SIZE] __attribute__((aligned(64)));
    kbkdf_sm3_v2(new_sk_bulk);
    memcpy(g_sk_bulk, new_sk_bulk, KMS_SK_SIZE);
    secure_wipe(new_sk_bulk, KMS_SK_SIZE);

    /* Wipe DK after rotation */
    neon_wipe_all_keys();
    g_dk_loaded = 0;

    /* Update rotation timestamp */
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    g_sk_bulk_rotation_time = (uint64_t) ts.tv_sec * 1000ULL
                              + (uint64_t) ts.tv_nsec / 1000000ULL;

    KMS_LOGV("SK_bulk rotated");
    return KMS_OK;
}

int kms_derive_session_key_tiered(int tier, uint8_t sk_out[KMS_SK_SIZE]) {
    OBF_BARRIER(665);
    if (tier < 0 || tier > KMS_SK_TIER_ROOT) return KMS_ERR_STATE;

    switch (tier) {
        case KMS_SK_TIER_BULK: {
            /* FIX #1: Check if rotation is needed */
            struct timespec _rts;
            clock_gettime(CLOCK_MONOTONIC, &_rts);
            uint64_t now_ms = (uint64_t) _rts.tv_sec * 1000ULL
                              + (uint64_t) _rts.tv_nsec / 1000000ULL;
            if (now_ms - g_sk_bulk_rotation_time > KMS_SK_BULK_ROTATION_INTERVAL_MS) {
                kms_rotate_sk_bulk();
            }
            /* Return pre-derived (possibly freshly rotated) SK_bulk */
            memcpy(sk_out, g_sk_bulk, KMS_SK_SIZE);
            return KMS_OK;
        }

        case KMS_SK_TIER_SESSION:
        case KMS_SK_TIER_MSG: {
            /* Re-use cached SK if within counter window */
            uint32_t local_counter = __sync_fetch_and_add(&g_sk_counters[tier], 1);
            /* Derive fresh DK and SK */
            if (kms_derive_dk_ephemeral() != KMS_OK) return KMS_ERR_STATE;
            kbkdf_sm3_v2(sk_out);
            /* Wipe DK after SK derivation */
            neon_wipe_all_keys();
            g_dk_loaded = 0;
            (void) local_counter;
            return KMS_OK;
        }

        case KMS_SK_TIER_ROOT:
            /* Per-operation — full derivation + immediate DK wipe */
            if (kms_derive_dk_ephemeral() != KMS_OK) return KMS_ERR_STATE;
            kbkdf_sm3_v2(sk_out);
            neon_wipe_all_keys();
            g_dk_loaded = 0;
            return KMS_OK;

        default:
            return KMS_ERR_STATE;
    }
}

/* JNI functions for KmsProvider.kt */
#include <jni.h>

extern "C" jint Java_com_yunian_ai_security_KmsProvider_nativeInit(JNIEnv *, jclass) {
    return kms_init();
}

/* Forward declarations — static functions defined later in this file */
static jbyteArray
native_encrypt_v2_impl(JNIEnv *, jbyteArray, const uint8_t[KMS_METADATA_SIZE], int);

static jbyteArray
native_decrypt_v2_impl(JNIEnv *, jbyteArray, const uint8_t[KMS_METADATA_SIZE], int);

extern "C" jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeEncrypt(JNIEnv *env, jclass, jbyteArray input) {
    /* Legacy wrapper — generates fresh metadata internally.
     * Prefer nativeEncryptV2 for full pipeline with external metadata sync. */
    if (!input || !g_dk_loaded) return nullptr;
    jsize len = env->GetArrayLength(input);
    if (len % 16 != 0) return nullptr;

    /* Generate metadata: fresh salt + monotonic counter */
    uint8_t metadata[KMS_METADATA_SIZE];
    kms_generate_salt(metadata);  /* salt in metadata[0..7] */
    uint64_t ctr = __atomic_fetch_add(&g_bk_counter, 1, __ATOMIC_RELAXED);
    metadata[8] = (ctr >> 56) & 0xFF;
    metadata[9] = (ctr >> 48) & 0xFF;
    metadata[10] = (ctr >> 40) & 0xFF;
    metadata[11] = (ctr >> 32) & 0xFF;
    metadata[12] = (ctr >> 24) & 0xFF;
    metadata[13] = (ctr >> 16) & 0xFF;
    metadata[14] = (ctr >> 8) & 0xFF;
    metadata[15] = ctr & 0xFF;

    /* Encrypt with pipeline — metadata prepended to result */
    return native_encrypt_v2_impl(env, input, metadata, /*prepend_metadata=*/1);
}

extern "C" jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeDecrypt(JNIEnv *env, jclass, jbyteArray input) {
    /* Legacy wrapper — extracts metadata from ciphertext header.
     * Prefer nativeDecryptV2 for full pipeline with external metadata. */
    if (!input || !g_dk_loaded) return nullptr;
    jsize len = env->GetArrayLength(input);
    /* Header: metadata[16] + nonce[8] + IV[16] + at least one block of ciphertext */
    if (len < (jsize) (KMS_METADATA_SIZE + KMS_NONCE_EXT_SIZE + 16 + 16)) return nullptr;

    /* Extract metadata from first 16 bytes */
    jbyte *input_bytes = env->GetByteArrayElements(input, nullptr);
    if (!input_bytes) return nullptr;
    uint8_t metadata[KMS_METADATA_SIZE];
    memcpy(metadata, input_bytes, KMS_METADATA_SIZE);
    env->ReleaseByteArrayElements(input, input_bytes, JNI_ABORT);

    return native_decrypt_v2_impl(env, input, metadata, /*strip_metadata=*/1);
}

/* ===================================================================
 * V2 Pipeline: BK blind → WB-AES-CBC → BK unblind
 * =================================================================== */

/**
 * Full encryption pipeline with BK blinding + metadata sync.
 *
 * Java: nativeEncryptV2(byte[] plaintext, byte[] metadata)
 *   plaintext: PKCS7-padded, 16-byte-aligned
 *   metadata:  16 bytes (salt[8] || counter[8])
 *   returns:   ciphertext (metadata NOT prepended — caller handles)
 */
extern "C" jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeEncryptV2(
        JNIEnv *env, jclass, jbyteArray input, jbyteArray meta) {

    if (!input || !meta || !g_dk_loaded) return nullptr;
    jsize len = env->GetArrayLength(input);
    if (len % 16 != 0) return nullptr;
    if (env->GetArrayLength(meta) != KMS_METADATA_SIZE) return nullptr;

    /* Read metadata */
    jbyte *meta_bytes = env->GetByteArrayElements(meta, nullptr);
    if (!meta_bytes) return nullptr;
    uint8_t metadata[KMS_METADATA_SIZE];
    memcpy(metadata, meta_bytes, KMS_METADATA_SIZE);
    env->ReleaseByteArrayElements(meta, meta_bytes, JNI_ABORT);

    return native_encrypt_v2_impl(env, input, metadata, /*prepend_metadata=*/0);
}

/**
 * Full decryption pipeline (reverse of encryptV2).
 *
 * Java: nativeDecryptV2(byte[] ciphertext, byte[] metadata)
 *   ciphertext: encrypted payload (no metadata prefix)
 *   metadata:   16 bytes — must match the encrypt-side metadata
 *   returns:    plaintext (still PKCS7-padded — caller unpads)
 */
extern "C" jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeDecryptV2(
        JNIEnv *env, jclass, jbyteArray input, jbyteArray meta) {

    if (!input || !meta || !g_dk_loaded) return nullptr;
    jsize len = env->GetArrayLength(input);
    /* Header: nonce[8] + IV[16] + at least one ciphertext block */
    if (len < (jsize) (KMS_NONCE_EXT_SIZE + 16 + 16) ||
        (len - KMS_NONCE_EXT_SIZE - 16) % 16 != 0)
        return nullptr;
    if (env->GetArrayLength(meta) != KMS_METADATA_SIZE) return nullptr;

    /* Read metadata */
    jbyte *meta_bytes = env->GetByteArrayElements(meta, nullptr);
    if (!meta_bytes) return nullptr;
    uint8_t metadata[KMS_METADATA_SIZE];
    memcpy(metadata, meta_bytes, KMS_METADATA_SIZE);
    env->ReleaseByteArrayElements(meta, meta_bytes, JNI_ABORT);

    return native_decrypt_v2_impl(env, input, metadata, /*strip_metadata=*/0);
}

/* ===================================================================
 * Core implementation — shared by legacy + V2 JNI wrappers
 * =================================================================== */

/**
 * Encrypt implementation: derive DK → BK blind → WB-AES-CBC → BK unblind → wipe.
 *
 * @param prepend_metadata  If 1, prepend metadata to output (legacy mode).
 */
static jbyteArray native_encrypt_v2_impl(
        JNIEnv *env, jbyteArray input, const uint8_t metadata[KMS_METADATA_SIZE],
        int prepend_metadata) {

    jsize len = env->GetArrayLength(input);

    /* 1. Derive ephemeral DK into NEON registers (RAM copy flushed) */
    if (kms_derive_dk_ephemeral() != KMS_OK) return nullptr;

    /* 2. FIX #6: Generate per-operation nonce for BK forward secrecy */
    uint8_t nonce[KMS_NONCE_EXT_SIZE] __attribute__((aligned(64)));
    kms_getrandom(nonce, KMS_NONCE_EXT_SIZE);

    /* 3. FIX #4: Generate random IV for CBC */
    uint8_t iv[16] __attribute__((aligned(64)));
    kms_getrandom(iv, 16);

    /* 4. Get input bytes */
    jbyte *in_bytes = env->GetByteArrayElements(input, nullptr);
    if (!in_bytes) {
        kms_wipe_neon_keys();
        return nullptr;
    }

    /* 5. Allocate output buffer with header (nonce+IV prefix) and ciphertext */
    jsize header_size = (prepend_metadata ? KMS_METADATA_SIZE : 0) + KMS_NONCE_EXT_SIZE + 16;
    jbyteArray result = env->NewByteArray(header_size + len);
    jbyte *out_bytes = env->GetByteArrayElements(result, nullptr);
    if (!out_bytes) {
        env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);
        kms_wipe_neon_keys();
        return nullptr;
    }
    /* Write header: prepend metadata (if legacy), then nonce, then IV */
    jsize off = 0;
    if (prepend_metadata) {
        memcpy(out_bytes + off, metadata, KMS_METADATA_SIZE);
        off += KMS_METADATA_SIZE;
    }
    memcpy(out_bytes + off, nonce, KMS_NONCE_EXT_SIZE);
    off += KMS_NONCE_EXT_SIZE;
    memcpy(out_bytes + off, iv, 16);
    off += 16;

    /* Copy input to work area after header */
    uint8_t *work = (uint8_t *) (out_bytes + header_size);
    memcpy(work, in_bytes, len);
    env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);

    /* 6. FIX #5/#6: Derive BK (256-bit) with forward-secrecy nonce + blind input */
    uint8_t bk[KMS_BK_SIZE] __attribute__((aligned(64)));
    uint8_t bk_prime[KMS_BK_SIZE] __attribute__((aligned(64)));
    if (kms_blind_input(work, len, metadata, nonce, bk_prime) != KMS_OK) {
        env->ReleaseByteArrayElements(result, out_bytes, 0);
        kms_wipe_neon_keys();
        return nullptr;
    }
    /* Extract BK from blind_input side-effect — re-derive for bk buffer */
    if (kms_derive_bk(metadata, nonce, bk) != KMS_OK) {
        env->ReleaseByteArrayElements(result, out_bytes, 0);
        kms_wipe_neon_keys();
        secure_wipe(bk_prime, KMS_BK_SIZE);
        return nullptr;
    }

    /* 7. FIX #4: WB-AES-CBC encrypt with random IV */
    if (wb_aes_256_cbc_encrypt(work, work, len, iv) != 0) {
        env->ReleaseByteArrayElements(result, out_bytes, 0);
        kms_wipe_neon_keys();
        secure_wipe(bk, KMS_BK_SIZE);
        secure_wipe(bk_prime, KMS_BK_SIZE);
        return nullptr;
    }

    /* 8. BK' unblind: XOR ciphertext with BK' → final output */
    if (kms_unblind_output(work, len, metadata, nonce, bk_prime) != KMS_OK) {
        env->ReleaseByteArrayElements(result, out_bytes, 0);
        kms_wipe_neon_keys();
        secure_wipe(bk, KMS_BK_SIZE);
        secure_wipe(bk_prime, KMS_BK_SIZE);
        return nullptr;
    }

    /* 9. Wipe all key material */
    secure_wipe(bk, KMS_BK_SIZE);
    secure_wipe(bk_prime, KMS_BK_SIZE);
    kms_wipe_neon_keys();  /* movi v0..v7,#0 */

    env->ReleaseByteArrayElements(result, out_bytes, 0);
    return result;
}

/**
 * Decrypt implementation: derive DK → derive BK+BK' → BK' blind → WB-AES-CBC decrypt → BK unblind → wipe.
 *
 * FIX #4: Parse random IV from ciphertext header (nonce[8] + IV[16] prefix)
 * FIX #5: 256-bit BK
 * FIX #6: Forward-secrecy nonce carried in ciphertext
 *
 * Decrypt must reverse the encrypt blind/unblind order:
 *   encrypt: BK blind in → WB-AES → BK' unblind out
 *   decrypt: BK' blind in → WB-AES⁻¹ → BK unblind out
 *
 * Ciphertext format: [metadata?][nonce:8][iv:16][ciphertext...]
 *
 * @param strip_metadata  If 1, input has metadata prefix to skip (legacy mode).
 */
static jbyteArray native_decrypt_v2_impl(
        JNIEnv *env, jbyteArray input, const uint8_t metadata[KMS_METADATA_SIZE],
        int strip_metadata) {

    jsize len = env->GetArrayLength(input);
    jsize header_size = (strip_metadata ? KMS_METADATA_SIZE : 0) + KMS_NONCE_EXT_SIZE + 16;
    jsize payload_len = len - header_size;

    if (payload_len <= 0 || payload_len % 16 != 0) return nullptr;

    /* 1. Derive ephemeral DK */
    if (kms_derive_dk_ephemeral() != KMS_OK) return nullptr;

    /* 2. Get input bytes and parse header */
    jbyte *in_bytes = env->GetByteArrayElements(input, nullptr);
    if (!in_bytes) {
        kms_wipe_neon_keys();
        return nullptr;
    }

    /* FIX #6: Extract nonce from header */
    uint8_t nonce[KMS_NONCE_EXT_SIZE] __attribute__((aligned(64)));
    jsize off = (strip_metadata ? KMS_METADATA_SIZE : 0);
    memcpy(nonce, in_bytes + off, KMS_NONCE_EXT_SIZE);
    off += KMS_NONCE_EXT_SIZE;

    /* FIX #4: Extract random IV from header */
    uint8_t iv[16] __attribute__((aligned(64)));
    memcpy(iv, in_bytes + off, 16);
    off += 16;

    const uint8_t *payload = (const uint8_t *) (in_bytes + off);

    /* 3. FIX #5/#6: Derive BK and BK' using nonce for forward secrecy */
    uint8_t bk[KMS_BK_SIZE] __attribute__((aligned(64)));
    uint8_t bk_prime[KMS_BK_SIZE] __attribute__((aligned(64)));
    if (kms_derive_bk(metadata, nonce, bk) != KMS_OK) {
        env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);
        kms_wipe_neon_keys();
        return nullptr;
    }
    /* Derive BK' = SM4_enc(BK[0:16], DK[16:32]) via blind_input on scratch */
    {
        uint8_t scratch[16] __attribute__((aligned(64))) = {0};
        uint8_t bk_prime_tmp[KMS_BK_SIZE] __attribute__((aligned(64)));
        if (kms_blind_input(scratch, 16, metadata, nonce, bk_prime_tmp) != KMS_OK) {
            env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);
            kms_wipe_neon_keys();
            secure_wipe(bk, KMS_BK_SIZE);
            return nullptr;
        }
        memcpy(bk_prime, bk_prime_tmp, KMS_BK_SIZE);
        secure_wipe(scratch, 16);
        secure_wipe(bk_prime_tmp, KMS_BK_SIZE);
    }

    /* 4. Allocate output */
    jbyteArray result = env->NewByteArray(payload_len);
    if (!result) {
        env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);
        kms_wipe_neon_keys();
        secure_wipe(bk, KMS_BK_SIZE);
        secure_wipe(bk_prime, KMS_BK_SIZE);
        return nullptr;
    }
    jbyte *out_bytes = env->GetByteArrayElements(result, nullptr);
    if (!out_bytes) {
        env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);
        kms_wipe_neon_keys();
        secure_wipe(bk, KMS_BK_SIZE);
        secure_wipe(bk_prime, KMS_BK_SIZE);
        return nullptr;
    }

    /* Copy ciphertext payload */
    memcpy(out_bytes, payload, payload_len);
    env->ReleaseByteArrayElements(input, in_bytes, JNI_ABORT);

    /* 5. BK' blind: XOR ciphertext with BK' (reverse of encrypt's unblind_output) */
    for (int i = 0; i < payload_len; i++) {
        out_bytes[i] ^= bk_prime[i % KMS_BK_SIZE];
    }

    /* 6. FIX #4: WB-AES-CBC decrypt with IV from ciphertext header */
    if (wb_aes_256_cbc_decrypt((const uint8_t *) out_bytes, (uint8_t *) out_bytes, payload_len,
                               iv) != 0) {
        env->ReleaseByteArrayElements(result, out_bytes, 0);
        kms_wipe_neon_keys();
        secure_wipe(bk, KMS_BK_SIZE);
        secure_wipe(bk_prime, KMS_BK_SIZE);
        return nullptr;
    }

    /* 7. BK unblind: XOR decrypted plaintext with BK (reverse of encrypt's blind_input) */
    for (int i = 0; i < payload_len; i++) {
        out_bytes[i] ^= bk[i % KMS_BK_SIZE];
    }

    /* 8. Wipe */
    secure_wipe(bk, KMS_BK_SIZE);
    secure_wipe(bk_prime, KMS_BK_SIZE);
    kms_wipe_neon_keys();

    env->ReleaseByteArrayElements(result, out_bytes, 0);
    return result;
}

extern "C" void Java_com_yunian_ai_security_KmsProvider_nativeDestroyKeychain(JNIEnv *, jclass) {
    kms_destroy_keychain();
}

extern "C" jint Java_com_yunian_ai_security_KmsProvider_nativeGetStatus(JNIEnv *, jclass) {
    return kms_get_status();
}

// ═══════════════════════════════════════════════════════════════
// nativeGetDevAesKey — Dev-only AES-256 key for debug builds
// ═══════════════════════════════════════════════════════════════
//
// 🔒 TOP_SECRET: The dev AES-256 key is XOR-obfuscated in .rodata.
//    In release builds (PRODUCTION_BUILD defined), returns nullptr.
//    In debug builds, returns the deobfuscated 32-byte key.
//    Caller MUST zero the returned ByteArray after use.
//
// The key is generated by gen_payload_cpp.py and stored in
// g_dev_key_config.h (auto-generated, not committed).

#ifdef YUNIAN_DEV_KEY_CONFIG_H
#include "g_dev_key_config.h"
#else
// Runtime-derived dev key from address entropy — no hardcoded key in binary.
// Each boot gets a different key due to ASLR.
#define DEV_AES_KEY_XOR 0x00u
__attribute__((used)) static void _kms_dev_key_init(void) __attribute__((constructor(65535)));
static uint8_t g_dev_aes_key_obf[32];  // mutable: filled at init
static uint8_t g_dev_aes_key_xor = 0;

static void _kms_dev_key_init(void) {
    if (g_dev_aes_key_obf[0] != 0) return;  // already inited
    uintptr_t seed = (uintptr_t)&_kms_dev_key_init;
    for (int i = 0; i < 32; i++) {
        g_dev_aes_key_obf[i] = (uint8_t)((seed >> ((i % 8) * 8)) ^ (i * 0x6B + 0x13) ^ 0xA5);
    }
    g_dev_aes_key_xor = (uint8_t)((seed >> 16) ^ 0x9A);
}
#define DEV_AES_KEY_XOR g_dev_aes_key_xor
#endif

extern "C" jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeGetDevAesKey(
    JNIEnv* env, jclass) {
#ifdef PRODUCTION_BUILD
    // 🔒 Release: never expose the dev key
    (void)env;
    return nullptr;
#else
    OBF_BARRIER(72);
    // Deobfuscate: XOR each byte with DEV_AES_KEY_XOR
    uint8_t plain[32];
    for (int i = 0; i < 32; i++) {
        plain[i] = g_dev_aes_key_obf[i] ^ DEV_AES_KEY_XOR;
    }
    // Check if it's the all-zero placeholder
    int is_zero = 1;
    for (int i = 0; i < 32; i++) {
        if (plain[i] != 0) { is_zero = 0; break; }
    }
    if (is_zero) {
        memset(plain, 0, 32);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(32);
    if (out) {
        env->SetByteArrayRegion(out, 0, 32, (const jbyte*)plain);
    }
    // Zero the stack copy
    memset(plain, 0, 32);
    return out;
#endif
}


/* ── KmsProvider RegisterNatives (called from JNI_OnLoad) ── */
extern "C" int kms_provider_register_natives(JNIEnv* env) {
    jclass cls = env->FindClass("com/yunian/ai/security/KmsProvider");
    if (!cls || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return -1;
    }
    // Must match KmsProvider.kt external fun signatures exactly.
    JNINativeMethod methods[] = {
        {(char*)"nativeInit",             (char*)"()I",     (void*)Java_com_yunian_ai_security_KmsProvider_nativeInit},
        {(char*)"nativeEncrypt",          (char*)"([B)[B",  (void*)Java_com_yunian_ai_security_KmsProvider_nativeEncrypt},
        {(char*)"nativeDecrypt",          (char*)"([B)[B",  (void*)Java_com_yunian_ai_security_KmsProvider_nativeDecrypt},
        {(char*)"nativeEncryptV2",        (char*)"([B[B)[B",(void*)Java_com_yunian_ai_security_KmsProvider_nativeEncryptV2},
        {(char*)"nativeDecryptV2",        (char*)"([B[B)[B",(void*)Java_com_yunian_ai_security_KmsProvider_nativeDecryptV2},
        {(char*)"nativeDestroyKeychain",  (char*)"()V",     (void*)Java_com_yunian_ai_security_KmsProvider_nativeDestroyKeychain},
        {(char*)"nativeGetStatus",        (char*)"()I",     (void*)Java_com_yunian_ai_security_KmsProvider_nativeGetStatus},
        {(char*)"nativeGetDevAesKey",     (char*)"()[B",    (void*)Java_com_yunian_ai_security_KmsProvider_nativeGetDevAesKey},
    };
    int rc = env->RegisterNatives(cls, methods, 8);
    env->DeleteLocalRef(cls);
    return (rc == JNI_OK) ? 0 : -2;
}

#pragma GCC visibility pop
