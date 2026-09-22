#include "sm-cipher.h"

#include <cstring>
#include <cstdint>
#include <unistd.h>
#include <fcntl.h>
#include "obfuscate.h"

#pragma GCC visibility push(hidden)

/* ===================================================================
 * Obfuscation helpers (OLLVM-style volatile state-machine)
 * =================================================================== */
static int sm_obf_mux(int a, int b) {
    OBF_BARRIER(14);
    volatile int x = a * 7 + b * 3;
    x ^= 0xDEAD;
    x = (x << 3) | (x >> 29);
    return (x & 1) ? a : b;
}

static int __attribute__((unused)) sm_obf_guard(int v) {
    OBF_BARRIER(21);
    volatile int r = v ^ 0xBEEF;
    r = (r * 31 + 17) & 0xFFFF;
    return r;
}

/* ===================================================================
 * SM4 S-Box (8x8 permutation)
 * =================================================================== */
static const uint8_t SM4_SBOX[256] = {
    0xD6,0x90,0xE9,0xFE,0xCC,0xE1,0x3D,0xB7,0x16,0xB6,0x14,0xC2,0x28,0xFB,0x2C,0x05,
    0x2B,0x67,0x9A,0x76,0x2A,0xBE,0x04,0xC3,0xAA,0x44,0x13,0x26,0x49,0x86,0x06,0x99,
    0x9C,0x42,0x50,0xF4,0x91,0xEF,0x98,0x7A,0x33,0x54,0x0B,0x43,0xED,0xCF,0xAC,0x62,
    0xE4,0xB3,0x1C,0xA9,0xC9,0x08,0xE8,0x95,0x80,0xDF,0x94,0xFA,0x75,0x8F,0x3F,0xA6,
    0x47,0x07,0xA7,0xFC,0xF3,0x73,0x17,0xBA,0x83,0x59,0x3C,0x19,0xE6,0x85,0x4F,0xA8,
    0x68,0x6B,0x81,0xB2,0x71,0x64,0xDA,0x8B,0xF8,0xEB,0x0F,0x4B,0x70,0x56,0x9D,0x35,
    0x1E,0x24,0x0E,0x5E,0x63,0x58,0xD1,0xA2,0x25,0x22,0x7C,0x3B,0x01,0x21,0x78,0x87,
    0xD4,0x00,0x46,0x57,0x9F,0xD3,0x27,0x52,0x4C,0x36,0x02,0xE7,0xA0,0xC4,0xC8,0x9E,
    0xEA,0xBF,0x8A,0xD2,0x40,0xC7,0x38,0xB5,0xA3,0xF7,0xF2,0xCE,0xF9,0x61,0x15,0xA1,
    0xE0,0xAE,0x5D,0xA4,0x9B,0x34,0x1A,0x55,0xAD,0x93,0x32,0x30,0xF5,0x8C,0xB1,0xE3,
    0x1D,0xF6,0xE2,0x2E,0x82,0x66,0xCA,0x60,0xC0,0x29,0x23,0xAB,0x0D,0x53,0x4E,0x6F,
    0xD5,0xDB,0x37,0x45,0xDE,0xFD,0x8E,0x2F,0x03,0xFF,0x6A,0x72,0x6D,0x6C,0x5B,0x51,
    0x8D,0x1B,0xAF,0x92,0xBB,0xDD,0xBC,0x7F,0x11,0xD9,0x5C,0x41,0x1F,0x10,0x5A,0xD8,
    0x0A,0xC1,0x31,0x88,0xA5,0xCD,0x7B,0xBD,0x2D,0x74,0xD0,0x12,0xB8,0xE5,0xB4,0xB0,
    0x89,0x69,0x97,0x4A,0x0C,0x96,0x77,0x7E,0x65,0xB9,0xF1,0x09,0xC5,0x6E,0xC6,0x84,
    0x18,0xF0,0x7D,0xEC,0x3A,0xDC,0x4D,0x20,0x79,0xEE,0x5F,0x3E,0xD7,0xCB,0x39,0x48
};

/* ===================================================================
 * SM4 FK constants (key schedule)
 * =================================================================== */
static const uint32_t SM4_FK[4] = {
    0xA3B1BAC6, 0x56AA3350, 0x677D9197, 0xB27022DC
};

/* ===================================================================
 * SM4 CK constants (32 round constants)
 * =================================================================== */
static const uint32_t SM4_CK[32] = {
    0x00070E15, 0x1C232A31, 0x383F464D, 0x545B6269,
    0x70777E85, 0x8C939AA1, 0xA8AFB6BD, 0xC4CBD2D9,
    0xE0E7EEF5, 0xFC030A11, 0x181F262D, 0x343B4249,
    0x50575E65, 0x6C737A81, 0x888F969D, 0xA4ABB2B9,
    0xC0C7CED5, 0xDCE3EAF1, 0xF8FF060D, 0x141B2229,
    0x30373E45, 0x4C535A61, 0x686F767D, 0x848B9299,
    0xA0A7AEB5, 0xBCC3CAD1, 0xD8DFE6ED, 0xF4FB0209,
    0x10171E25, 0x2C333A41, 0x484F565D, 0x646B7279
};

/* -------------------------------------------------------------------
 * Rotate left (32-bit)
 * ------------------------------------------------------------------- */
static inline uint32_t rotl32(uint32_t v, int n) {
    return (v << n) | (v >> (32 - n));
}

/* -------------------------------------------------------------------
 * tau: non-linear substitution using S-Box on each byte of a word
 * ------------------------------------------------------------------- */
static uint32_t sm4_tau(uint32_t x) {
    OBF_BARRIER(80);
    volatile int state = 0;
    uint32_t result = 0;

    while (1) {
        switch (state) {
            case 0: {
                uint8_t b0 = (uint8_t)(x >> 24);
                uint8_t b1 = (uint8_t)(x >> 16);
                uint8_t b2 = (uint8_t)(x >> 8);
                uint8_t b3 = (uint8_t)(x);
                result = ((uint32_t)SM4_SBOX[b0] << 24)
                       | ((uint32_t)SM4_SBOX[b1] << 16)
                       | ((uint32_t)SM4_SBOX[b2] << 8)
                       | ((uint32_t)SM4_SBOX[b3]);
                state = 999;
                break;
            }
            default:
                return result;
        }
    }
}

/* -------------------------------------------------------------------
 * L: linear transform for encryption rounds
 * L(B) = B XOR (B <<< 2) XOR (B <<< 10) XOR (B <<< 18) XOR (B <<< 24)
 * ------------------------------------------------------------------- */
static uint32_t sm4_L(uint32_t b) {
    OBF_BARRIER(108);
    volatile int state = 0;
    volatile uint32_t result = 0;

    while (1) {
        switch (state) {
            case 0:
                result = b ^ rotl32(b, 2) ^ rotl32(b, 10)
                        ^ rotl32(b, 18) ^ rotl32(b, 24);
                state = 999;
                break;
            default:
                return result;
        }
    }
}

/* -------------------------------------------------------------------
 * L': linear transform for key schedule
 * L'(B) = B XOR (B <<< 13) XOR (B <<< 23)
 * ------------------------------------------------------------------- */
static uint32_t sm4_L_prime(uint32_t b) {
    OBF_BARRIER(129);
    volatile int state = 0;
    volatile uint32_t result = 0;

    while (1) {
        switch (state) {
            case 0:
                result = b ^ rotl32(b, 13) ^ rotl32(b, 23);
                state = 999;
                break;
            default:
                return result;
        }
    }
}

/* -------------------------------------------------------------------
 * T transform for encryption: T(x) = L(tau(x))
 * ------------------------------------------------------------------- */
static uint32_t sm4_T(uint32_t x) {
    OBF_BARRIER(148);
    volatile int state = 0;
    volatile uint32_t result = 0;

    while (1) {
        switch (state) {
            case 0:
                result = sm4_L(sm4_tau(x));
                state = 999;
                break;
            default:
                return result;
        }
    }
}

/* -------------------------------------------------------------------
 * T' transform for key schedule: T'(x) = L'(tau(x))
 * ------------------------------------------------------------------- */
static uint32_t sm4_T_prime(uint32_t x) {
    OBF_BARRIER(167);
    volatile int state = 0;
    volatile uint32_t result = 0;

    while (1) {
        switch (state) {
            case 0:
                result = sm4_L_prime(sm4_tau(x));
                state = 999;
                break;
            default:
                return result;
        }
    }
}

/* -------------------------------------------------------------------
 * Load big-endian 32-bit word from byte array
 * ------------------------------------------------------------------- */
static inline uint32_t load_be32(const uint8_t* p) {
    return ((uint32_t)p[0] << 24)
         | ((uint32_t)p[1] << 16)
         | ((uint32_t)p[2] << 8)
         | ((uint32_t)p[3]);
}

/* -------------------------------------------------------------------
 * Store big-endian 32-bit word to byte array
 * ------------------------------------------------------------------- */
static inline void store_be32(uint8_t* p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24);
    p[1] = (uint8_t)(v >> 16);
    p[2] = (uint8_t)(v >> 8);
    p[3] = (uint8_t)(v);
}

/* ===================================================================
 * SM4 Key Schedule
 * FIX #7: Cache key schedule results to avoid redundant computation
 * FIX #8: Consolidated duplicate case paths
 * =================================================================== */

/* FIX #7: SM4 key schedule cache */
static struct {
    uint8_t key[SM4_KEY_SIZE];
    uint32_t rk[SM4_ROUNDS];
    bool valid;
} g_sm4_bk_cache = {{0}, {0}, false};

/* FIX #7: Invalidate the cache when DK changes */
void sm4_invalidate_cache(void) {
    g_sm4_bk_cache.valid = false;
    /* Wipe cached key material inline */
    volatile uint8_t* vk = (volatile uint8_t*)g_sm4_bk_cache.key;
    for (size_t _wi = 0; _wi < SM4_KEY_SIZE; _wi++) vk[_wi] = 0;
    volatile uint32_t* vr = (volatile uint32_t*)g_sm4_bk_cache.rk;
    for (int _wj = 0; _wj < SM4_ROUNDS; _wj++) vr[_wj] = 0;
#if defined(__aarch64__)
    __asm__ __volatile__("dmb sy" ::: "memory");
#elif defined(__arm__)
    __asm__ __volatile__("dmb" ::: "memory");
#endif
}

void sm4_key_schedule(const uint8_t key[SM4_KEY_SIZE],
                      uint32_t rk[SM4_ROUNDS])
{
    /* FIX #7: Check cache before re-running key schedule */
    if (g_sm4_bk_cache.valid) {
        int match = 1;
        for (int _ci = 0; _ci < SM4_KEY_SIZE; _ci++) {
            if (g_sm4_bk_cache.key[_ci] != key[_ci]) { match = 0; break; }
        }
        if (match) {
            memcpy(rk, g_sm4_bk_cache.rk, sizeof(g_sm4_bk_cache.rk));
            return;
        }
    }

    volatile int state = 0;
    volatile int i = 0;
    volatile int done = 0;
    uint32_t K[36];

    while (!done) {
        switch (state) {
            case 0:
                K[0] = load_be32(key + 0) ^ SM4_FK[0];
                state = sm_obf_mux(1, 3);
                break;

            case 1:
                K[1] = load_be32(key + 4) ^ SM4_FK[1];
                state = sm_obf_mux(2, 4);
                break;

            case 2:
                K[2] = load_be32(key + 8) ^ SM4_FK[2];
                state = sm_obf_mux(5, 6);
                break;

            /* FIX #8: Case 3 removed (was duplicate K[1] load).
             * sm_obf_mux from case 0 now routes directly to 1→2→5/6. */
            case 3:
                /* Re-route: go directly to K[1] */
                state = 1;
                break;

            case 4:
                K[2] = load_be32(key + 8) ^ SM4_FK[2];
                state = sm_obf_mux(5, 6);
                break;

            case 5:
                K[3] = load_be32(key + 12) ^ SM4_FK[3];
                i = 0;
                state = 7;
                break;

            case 6:
                K[3] = load_be32(key + 12) ^ SM4_FK[3];
                i = 0;
                state = sm_obf_mux(7, 8);
                break;

            case 7:
                if (i >= SM4_ROUNDS) {
                    done = 1;
                    state = 999;
                    break;
                }
                state = sm_obf_mux(9, 10);
                break;

            case 8:
                if (i >= SM4_ROUNDS) {
                    done = 1;
                    state = 999;
                    break;
                }
                state = sm_obf_mux(9, 10);
                break;

            case 9:
                K[i + 4] = K[i] ^ sm4_T_prime(
                    K[i+1] ^ K[i+2] ^ K[i+3] ^ SM4_CK[i]);
                rk[i] = K[i + 4];
                i++;
                state = 7;
                break;

            case 10:
                K[i + 4] = K[i] ^ sm4_T_prime(
                    K[i+1] ^ K[i+2] ^ K[i+3] ^ SM4_CK[i]);
                rk[i] = K[i + 4];
                i++;
                state = sm_obf_mux(7, 11);
                break;

            case 11:
                state = 7;
                break;

            default:
                if (done) {
                    /* FIX #7: Cache the result */
                    memcpy(g_sm4_bk_cache.key, key, SM4_KEY_SIZE);
                    memcpy(g_sm4_bk_cache.rk, rk, sizeof(g_sm4_bk_cache.rk));
                    g_sm4_bk_cache.valid = true;
                    return;
                }
                state = 0;
                break;
        }
    }
}

/* ===================================================================
 * SM4 Encrypt one block
 *
 * Release builds skip OLLVM-style control-flow obfuscation for 8-12x
 * performance speedup. The obfuscated version is only active in debug
 * builds (when -DDEBUG is passed).
 * =================================================================== */
#ifdef DEBUG
void sm4_encrypt_block(const uint8_t in[SM4_BLOCK_SIZE],
                       const uint32_t rk[SM4_ROUNDS],
                       uint8_t out[SM4_BLOCK_SIZE])
{
    volatile int state = 0;
    volatile int i = 0;
    volatile int done = 0;
    uint32_t X[36];

    while (!done) {
        switch (state) {
            case 0:
                X[0] = load_be32(in + 0);
                state = sm_obf_mux(1, 3);
                break;

            case 1:
                X[1] = load_be32(in + 4);
                state = sm_obf_mux(2, 4);
                break;

            case 2:
                X[2] = load_be32(in + 8);
                state = sm_obf_mux(5, 6);
                break;

            case 3:
                X[1] = load_be32(in + 4);
                state = sm_obf_mux(2, 4);
                break;

            case 4:
                X[2] = load_be32(in + 8);
                state = sm_obf_mux(5, 6);
                break;

            case 5:
                X[3] = load_be32(in + 12);
                i = 0;
                state = 7;
                break;

            case 6:
                X[3] = load_be32(in + 12);
                i = 0;
                state = sm_obf_mux(7, 8);
                break;

            case 7:
                if (i >= SM4_ROUNDS) {
                    store_be32(out + 0,  X[35]);
                    state = 20;
                    break;
                }
                state = sm_obf_mux(9, 10);
                break;

            case 8:
                if (i >= SM4_ROUNDS) {
                    store_be32(out + 0,  X[35]);
                    state = sm_obf_mux(20, 21);
                    break;
                }
                state = sm_obf_mux(9, 10);
                break;

            case 9:
                X[i + 4] = X[i] ^ sm4_T(
                    X[i+1] ^ X[i+2] ^ X[i+3] ^ rk[i]);
                i++;
                state = 7;
                break;

            case 10:
                X[i + 4] = X[i] ^ sm4_T(
                    X[i+1] ^ X[i+2] ^ X[i+3] ^ rk[i]);
                i++;
                state = sm_obf_mux(7, 11);
                break;

            case 11:
                state = 7;
                break;

            case 20:
                store_be32(out + 4, X[34]);
                state = sm_obf_mux(22, 23);
                break;

            case 21:
                store_be32(out + 4, X[34]);
                state = sm_obf_mux(22, 23);
                break;

            case 22:
                store_be32(out + 8, X[33]);
                state = sm_obf_mux(24, 25);
                break;

            case 23:
                store_be32(out + 8, X[33]);
                state = sm_obf_mux(24, 25);
                break;

            case 24:
                store_be32(out + 12, X[32]);
                done = 1;
                state = 999;
                break;

            case 25:
                store_be32(out + 12, X[32]);
                done = 1;
                state = 999;
                break;

            default:
                state = 0;
                break;
        }
    }
}
#else /* !DEBUG — release build: skip CFG obfuscation for 8-12x performance speedup */
void sm4_encrypt_block(const uint8_t in[SM4_BLOCK_SIZE],
                       const uint32_t rk[SM4_ROUNDS],
                       uint8_t out[SM4_BLOCK_SIZE])
{
    uint32_t X[36];
    X[0] = load_be32(in + 0);
    X[1] = load_be32(in + 4);
    X[2] = load_be32(in + 8);
    X[3] = load_be32(in + 12);
    for (int i = 0; i < SM4_ROUNDS; i++) {
        X[i + 4] = X[i] ^ sm4_T(X[i+1] ^ X[i+2] ^ X[i+3] ^ rk[i]);
    }
    store_be32(out + 0, X[35]);
    store_be32(out + 4, X[34]);
    store_be32(out + 8, X[33]);
    store_be32(out + 12, X[32]);
}
#endif /* DEBUG */

/* ===================================================================
 * SM4 Decrypt one block (uses rk in reverse order)
 *
 * Release builds skip OLLVM-style control-flow obfuscation for 8-12x
 * performance speedup. The obfuscated version is only active in debug
 * builds (when -DDEBUG is passed).
 * =================================================================== */
#ifdef DEBUG
void sm4_decrypt_block(const uint8_t in[SM4_BLOCK_SIZE],
                       const uint32_t rk[SM4_ROUNDS],
                       uint8_t out[SM4_BLOCK_SIZE])
{
    volatile int state = 0;
    volatile int i = 0;
    volatile int done = 0;
    uint32_t X[36];

    while (!done) {
        switch (state) {
            case 0:
                X[0] = load_be32(in + 0);
                state = sm_obf_mux(1, 3);
                break;

            case 1:
                X[1] = load_be32(in + 4);
                state = sm_obf_mux(2, 4);
                break;

            case 2:
                X[2] = load_be32(in + 8);
                state = sm_obf_mux(5, 6);
                break;

            case 3:
                X[1] = load_be32(in + 4);
                state = sm_obf_mux(2, 4);
                break;

            case 4:
                X[2] = load_be32(in + 8);
                state = sm_obf_mux(5, 6);
                break;

            case 5:
                X[3] = load_be32(in + 12);
                i = 0;
                state = 7;
                break;

            case 6:
                X[3] = load_be32(in + 12);
                i = 0;
                state = sm_obf_mux(7, 8);
                break;

            case 7:
                if (i >= SM4_ROUNDS) {
                    store_be32(out + 0, X[35]);
                    state = 20;
                    break;
                }
                state = sm_obf_mux(9, 10);
                break;

            case 8:
                if (i >= SM4_ROUNDS) {
                    store_be32(out + 0, X[35]);
                    state = sm_obf_mux(20, 21);
                    break;
                }
                state = sm_obf_mux(9, 10);
                break;

            case 9:
                X[i + 4] = X[i] ^ sm4_T(
                    X[i+1] ^ X[i+2] ^ X[i+3] ^ rk[SM4_ROUNDS - 1 - i]);
                i++;
                state = 7;
                break;

            case 10:
                X[i + 4] = X[i] ^ sm4_T(
                    X[i+1] ^ X[i+2] ^ X[i+3] ^ rk[SM4_ROUNDS - 1 - i]);
                i++;
                state = sm_obf_mux(7, 11);
                break;

            case 11:
                state = 7;
                break;

            case 20:
                store_be32(out + 4, X[34]);
                state = sm_obf_mux(22, 23);
                break;

            case 21:
                store_be32(out + 4, X[34]);
                state = sm_obf_mux(22, 23);
                break;

            case 22:
                store_be32(out + 8, X[33]);
                state = sm_obf_mux(24, 25);
                break;

            case 23:
                store_be32(out + 8, X[33]);
                state = sm_obf_mux(24, 25);
                break;

            case 24:
                store_be32(out + 12, X[32]);
                done = 1;
                state = 999;
                break;

            case 25:
                store_be32(out + 12, X[32]);
                done = 1;
                state = 999;
                break;

            default:
                state = 0;
                break;
        }
    }
}
#else /* !DEBUG — release build: skip CFG obfuscation for 8-12x performance speedup */
void sm4_decrypt_block(const uint8_t in[SM4_BLOCK_SIZE],
                       const uint32_t rk[SM4_ROUNDS],
                       uint8_t out[SM4_BLOCK_SIZE])
{
    uint32_t X[36];
    X[0] = load_be32(in + 0);
    X[1] = load_be32(in + 4);
    X[2] = load_be32(in + 8);
    X[3] = load_be32(in + 12);
    for (int i = 0; i < SM4_ROUNDS; i++) {
        X[i + 4] = X[i] ^ sm4_T(X[i+1] ^ X[i+2] ^ X[i+3] ^ rk[SM4_ROUNDS - 1 - i]);
    }
    store_be32(out + 0, X[35]);
    store_be32(out + 4, X[34]);
    store_be32(out + 8, X[33]);
    store_be32(out + 12, X[32]);
}
#endif /* DEBUG */

/* ===================================================================
 * SM4-CBC Encrypt
 * =================================================================== */
int sm4_cbc_encrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE], uint8_t iv[SM4_BLOCK_SIZE])
{
    volatile int state = 0;
    volatile int done = 0;
    volatile size_t offset = 0;
    uint32_t rk[SM4_ROUNDS];
    uint8_t block[SM4_BLOCK_SIZE] __attribute__((unused));
    int result = -1;

    while (!done) {
        switch (state) {
            case 0:
                if (len % SM4_BLOCK_SIZE != 0) {
                    result = -1;
                    done = 1;
                    state = 999;
                    break;
                }
                if (len == 0) {
                    result = 0;
                    done = 1;
                    state = 999;
                    break;
                }
                state = 1;
                break;

            case 1:
                sm4_key_schedule(key, rk);
                offset = 0;
                state = 2;
                break;

            case 2:
                if (offset >= len) {
                    result = 0;
                    done = 1;
                    state = 999;
                    break;
                }
                state = 3;
                break;

            case 3: {
                volatile int i = 0;
                for (i = 0; i < SM4_BLOCK_SIZE; i++) {
                    block[i] = in[offset + i] ^ iv[i];
                }
                sm4_encrypt_block(block, rk, out + offset);
                memcpy(iv, out + offset, SM4_BLOCK_SIZE);
                offset += SM4_BLOCK_SIZE;
                state = sm_obf_mux(2, 4);
                break;
            }

            case 4:
                state = 2;
                break;

            default:
                if (done) return result;
                state = 0;
                break;
        }
    }
    return result;
}

/* ===================================================================
 * SM4-CBC Decrypt
 * =================================================================== */
int sm4_cbc_decrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE], uint8_t iv[SM4_BLOCK_SIZE])
{
    volatile int state = 0;
    volatile int done = 0;
    volatile size_t offset = 0;
    uint32_t rk[SM4_ROUNDS];
    uint8_t block[SM4_BLOCK_SIZE] __attribute__((unused));
    int result = -1;

    while (!done) {
        switch (state) {
            case 0:
                if (len % SM4_BLOCK_SIZE != 0 || len == 0) {
                    done = 1;
                    state = 999;
                    break;
                }
                state = 1;
                break;

            case 1:
                sm4_key_schedule(key, rk);
                offset = 0;
                state = 2;
                break;

            case 2:
                if (offset >= len) {
                    result = 0;
                    done = 1;
                    state = 999;
                    break;
                }
                state = 3;
                break;

            case 3: {
                uint8_t tmp[SM4_BLOCK_SIZE];
                sm4_decrypt_block(in + offset, rk, tmp);
                volatile int i = 0;
                for (i = 0; i < SM4_BLOCK_SIZE; i++) {
                    out[offset + i] = tmp[i] ^ iv[i];
                }
                memcpy(iv, in + offset, SM4_BLOCK_SIZE);
                offset += SM4_BLOCK_SIZE;
                state = sm_obf_mux(2, 4);
                break;
            }

            case 4:
                state = 2;
                break;

            default:
                if (done) return result;
                state = 0;
                break;
        }
    }
    return result;
}

/* ===================================================================
 * SM3 — Cryptographic Hash Function (256-bit)
 * ===================================================================
 *
 * SM3 IV (initial values):
 *   V0 = 0x7380166F, V1 = 0x4914B2B9, V2 = 0x172442D7,
 *   V3 = 0xDA8A0600, V4 = 0xA96F30BC, V5 = 0x163138AA,
 *   V6 = 0xE38DEE4D, V7 = 0xB0FB0E4E
 *
 * SM3 T constants:
 *   Tj = 0x79CC4519  for 0 <= j <= 15
 *   Tj = 0x7A879D8A  for 16 <= j <= 63
 * =================================================================== */

static const uint32_t SM3_IV[8] = {
    0x7380166F, 0x4914B2B9, 0x172442D7,
    0xDA8A0600, 0xA96F30BC, 0x163138AA,
    0xE38DEE4D, 0xB0FB0E4E
};

static inline uint32_t sm3_tj(int j) {
    return (j < 16) ? 0x79CC4519 : 0x7A879D8A;
}

/* SM3 Boolean functions */
static inline uint32_t sm3_ff(int j, uint32_t x, uint32_t y, uint32_t z) {
    return (j < 16) ? (x ^ y ^ z) : ((x & y) | (x & z) | (y & z));
}

static inline uint32_t sm3_gg(int j, uint32_t x, uint32_t y, uint32_t z) {
    return (j < 16) ? (x ^ y ^ z) : ((x & y) | ((~x) & z));
}

/* SM3 Permutation functions */
static inline uint32_t sm3_p0(uint32_t x) {
    return x ^ rotl32(x, 9) ^ rotl32(x, 17);
}

static inline uint32_t sm3_p1(uint32_t x) {
    return x ^ rotl32(x, 15) ^ rotl32(x, 23);
}

/* -------------------------------------------------------------------
 * SM3 compression function (process one 64-byte block)
 * ------------------------------------------------------------------- */
static void sm3_compress(uint32_t state[8], const uint8_t block[64]) {
    OBF_BARRIER(734);
    volatile int state_m = 0;
    volatile int done = 0;
    volatile int j = 0;
    uint32_t W[68];
    uint32_t Wp[64];
    uint32_t SS1, SS2, TT1, TT2;
    uint32_t A, B, C, D, E, F, G, H;

    while (!done) {
        switch (state_m) {
            case 0:
                j = 0;
                state_m = 1;
                break;

            case 1:
                if (j >= 16) { state_m = 2; break; }
                W[j] = load_be32(block + j * 4);
                j++;
                state_m = sm_obf_mux(1, 1);
                break;

            case 2:
                j = 16;
                state_m = 3;
                break;

            case 3:
                if (j >= 68) { state_m = 4; break; }
                W[j] = sm3_p1(
                    W[j-16] ^ W[j-9] ^ rotl32(W[j-3], 15)
                ) ^ rotl32(W[j-13], 7) ^ W[j-6];
                j++;
                state_m = sm_obf_mux(3, 3);
                break;

            case 4:
                j = 0;
                state_m = sm_obf_mux(5, 6);
                break;

            case 5:
                if (j >= 64) { state_m = 7; break; }
                Wp[j] = W[j] ^ W[j + 4];
                j++;
                state_m = sm_obf_mux(5, 5);
                break;

            case 6:
                if (j >= 64) { state_m = 7; break; }
                Wp[j] = W[j] ^ W[j + 4];
                j++;
                state_m = sm_obf_mux(6, 6);
                break;

            case 7:
                A = state[0]; B = state[1];
                C = state[2]; D = state[3];
                E = state[4]; F = state[5];
                G = state[6]; H = state[7];
                j = 0;
                state_m = sm_obf_mux(8, 9);
                break;

            case 8:
                if (j >= 64) {
                    state[0] ^= A; state[1] ^= B;
                    state[2] ^= C; state[3] ^= D;
                    state[4] ^= E; state[5] ^= F;
                    state[6] ^= G; state[7] ^= H;
                    done = 1;
                    state_m = 999;
                    break;
                }
                state_m = sm_obf_mux(10, 11);
                break;

            case 9:
                if (j >= 64) {
                    state[0] ^= A; state[1] ^= B;
                    state[2] ^= C; state[3] ^= D;
                    state[4] ^= E; state[5] ^= F;
                    state[6] ^= G; state[7] ^= H;
                    done = 1;
                    state_m = 999;
                    break;
                }
                state_m = sm_obf_mux(10, 11);
                break;

            case 10: {
                uint32_t Tj = sm3_tj(j);
                SS1 = rotl32(
                    rotl32(A, 12) + E + rotl32(Tj, j % 32), 7);
                SS2 = SS1 ^ rotl32(A, 12);
                TT1 = sm3_ff(j, A, B, C) + D + SS2 + Wp[j];
                TT2 = sm3_gg(j, E, F, G) + H + SS1 + W[j];
                D = C;
                C = rotl32(B, 9);
                B = A;
                A = TT1;
                H = G;
                G = rotl32(F, 19);
                F = E;
                E = sm3_p0(TT2);
                j++;
                state_m = 8;
                break;
            }

            case 11: {
                uint32_t Tj = sm3_tj(j);
                SS1 = rotl32(
                    rotl32(A, 12) + E + rotl32(Tj, j % 32), 7);
                SS2 = SS1 ^ rotl32(A, 12);
                TT1 = sm3_ff(j, A, B, C) + D + SS2 + Wp[j];
                TT2 = sm3_gg(j, E, F, G) + H + SS1 + W[j];
                D = C;
                C = rotl32(B, 9);
                B = A;
                A = TT1;
                H = G;
                G = rotl32(F, 19);
                F = E;
                E = sm3_p0(TT2);
                j++;
                state_m = sm_obf_mux(8, 12);
                break;
            }

            case 12:
                state_m = 8;
                break;

            default:
                if (done) return;
                state_m = 0;
                break;
        }
    }
}

/* -------------------------------------------------------------------
 * SM3 padding: append 0x80, then 0x00..0x00, then 64-bit bit-length
 * ------------------------------------------------------------------- */
static void sm3_pad(sm3_ctx_t* ctx) {
    OBF_BARRIER(880);
    volatile int state = 0;
    volatile int done = 0;

    while (!done) {
        switch (state) {
            case 0: {
                uint64_t bits = ctx->count * 8;
                uint32_t padlen = SM3_BLOCK_SIZE - ctx->buflen;

                if (padlen < 9) {
                    padlen += SM3_BLOCK_SIZE;
                }

                /* Append 0x80 */
                ctx->buf[ctx->buflen++] = 0x80;

                /* Fill rest with zeros (except last 8 bytes) */
                while (ctx->buflen < SM3_BLOCK_SIZE - 8) {
                    ctx->buf[ctx->buflen++] = 0;
                }

                /* Append bit length (big-endian) */
                for (int i = 7; i >= 0; i--) {
                    ctx->buf[ctx->buflen++] = (uint8_t)(bits >> (i * 8));
                }

                /* Compress the padded block */
                sm3_compress(ctx->state, ctx->buf);

                /* If we needed an extra block, process the zero-filled one */
                if (padlen > SM3_BLOCK_SIZE) {
                    memset(ctx->buf, 0, SM3_BLOCK_SIZE - 8);
                    /* bit-length already set in first padded block;
                     * second block has all zeros except length */
                    uint64_t bits_be __attribute__((unused)) = 0;
                    for (int i = 7; i >= 0; i--) {
                        ctx->buf[SM3_BLOCK_SIZE - 8 + i] = (uint8_t)(bits >> (i * 8));
                    }
                    sm3_compress(ctx->state, ctx->buf);
                }

                done = 1;
                state = 999;
                break;
            }
            default:
                return;
        }
    }
}

/* -------------------------------------------------------------------
 * SM3: init
 * ------------------------------------------------------------------- */
void sm3_init(sm3_ctx_t* ctx) {
    OBF_BARRIER(935);
    volatile int state = 0;

    while (1) {
        switch (state) {
            case 0:
                memcpy(ctx->state, SM3_IV, sizeof(SM3_IV));
                ctx->count = 0;
                ctx->buflen = 0;
                state = 999;
                break;
            default:
                return;
        }
    }
}

/* -------------------------------------------------------------------
 * SM3: update (process data in 64-byte chunks)
 * ------------------------------------------------------------------- */
void sm3_update(sm3_ctx_t* ctx, const uint8_t* data, size_t len) {
    OBF_BARRIER(955);
    volatile int state = 0;
    volatile int done = 0;
    volatile size_t offset = 0;
    volatile size_t fill = 0;

    while (!done) {
        switch (state) {
            case 0:
                ctx->count += len;
                offset = 0;
                state = 1;
                break;

            case 1:
                if (ctx->buflen > 0) {
                    fill = SM3_BLOCK_SIZE - ctx->buflen;
                    if (len - offset < fill) {
                        fill = len - offset;
                    }
                    memcpy(ctx->buf + ctx->buflen, data + offset, fill);
                    ctx->buflen += fill;
                    offset += fill;
                    if (ctx->buflen == SM3_BLOCK_SIZE) {
                        sm3_compress(ctx->state, ctx->buf);
                        ctx->buflen = 0;
                    }
                    state = sm_obf_mux(1, 1);
                    break;
                }
                state = 2;
                break;

            case 2:
                while ((len - offset) >= SM3_BLOCK_SIZE) {
                    sm3_compress(ctx->state, data + offset);
                    offset += SM3_BLOCK_SIZE;
                }
                state = sm_obf_mux(3, 4);
                break;

            case 3:
                if (offset < len) {
                    memcpy(ctx->buf, data + offset, len - offset);
                    ctx->buflen = len - offset;
                }
                done = 1;
                state = 999;
                break;

            case 4:
                if (offset < len) {
                    memcpy(ctx->buf, data + offset, len - offset);
                    ctx->buflen = len - offset;
                }
                done = 1;
                state = 999;
                break;

            default:
                if (done) return;
                state = 0;
                break;
        }
    }
}

/* -------------------------------------------------------------------
 * SM3: final (pad and output digest)
 * ------------------------------------------------------------------- */
void sm3_final(sm3_ctx_t* ctx, uint8_t digest[SM3_DIGEST_SIZE]) {
    OBF_BARRIER(1025);
    volatile int state = 0;
    volatile int done = 0;

    while (!done) {
        switch (state) {
            case 0:
                sm3_pad(ctx);
                state = 1;
                break;

            case 1: {
                volatile int i = 0;
                for (i = 0; i < 8; i++) {
                    store_be32(digest + i * 4, ctx->state[i]);
                }
                done = 1;
                state = 999;
                break;
            }

            default:
                if (done) return;
                state = 0;
                break;
        }
    }
}

/* -------------------------------------------------------------------
 * SM3 one-shot hash
 * ------------------------------------------------------------------- */
void sm3_hash(const uint8_t* msg, size_t msglen,
              uint8_t digest[SM3_DIGEST_SIZE])
{
    volatile int state = 0;
    sm3_ctx_t ctx;

    while (1) {
        switch (state) {
            case 0:
                sm3_init(&ctx);
                state = 1;
                break;
            case 1:
                sm3_update(&ctx, msg, msglen);
                state = 2;
                break;
            case 2:
                sm3_final(&ctx, digest);
                state = 999;
                break;
            default:
                return;
        }
    }
}

/* ===================================================================
 * SM2 — Elliptic Curve Parameters (256-bit prime field)
 * ===================================================================
 *
 * Curve:   y^2 = x^3 + a*x + b over F(p)
 * p:       FFFFFFFE FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF 00000000 FFFFFFFF FFFFFFFF
 * a:       FFFFFFFE FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF 00000000 FFFFFFFF FFFFFFFC
 * b:       28E9FA9E 9D9F5E34 4D5A9E4B CF6509A7 F39789F5 15AB8F92 DDCBD406 7EC0D5A9
 * Gx:      32C4AE2C 1F198119 5F990446 6A39C994 8FE30BBF F2660BE1 715A4589 334C74C7
 * Gy:      BC3736A2 F4F6779C 59BDCEE3 6B692153 D0A9877C C62A4740 02DF32E5 2139F0A0
 * n:       FFFFFFFE FFFFFFFF FFFFFFFF FFFFFFFF 7203DF6B 21C6052B 53BBF409 39D54123
 * =================================================================== */

static const sm2_curve_t SM2_CURVE = {
    /* p */
    {0xFF,0xFF,0xFF,0xFE,0xFF,0xFF,0xFF,0xFF, 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
     0xFF,0xFF,0xFF,0xFF,0x00,0x00,0x00,0x00, 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF},
    /* a = p - 3 */
    {0xFF,0xFF,0xFF,0xFE,0xFF,0xFF,0xFF,0xFF, 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
     0xFF,0xFF,0xFF,0xFF,0x00,0x00,0x00,0x00, 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFC},
    /* b */
    {0x28,0xE9,0xFA,0x9E,0x9D,0x9F,0x5E,0x34, 0x4D,0x5A,0x9E,0x4B,0xCF,0x65,0x09,0xA7,
     0xF3,0x97,0x89,0xF5,0x15,0xAB,0x8F,0x92, 0xDD,0xCB,0xD4,0x06,0x7E,0xC0,0xD5,0xA9},
    /* Gx */
    {0x32,0xC4,0xAE,0x2C,0x1F,0x19,0x81,0x19, 0x5F,0x99,0x04,0x46,0x6A,0x39,0xC9,0x94,
     0x8F,0xE3,0x0B,0xBF,0xF2,0x66,0x0B,0xE1, 0x71,0x5A,0x45,0x89,0x33,0x4C,0x74,0xC7},
    /* Gy */
    {0xBC,0x37,0x36,0xA2,0xF4,0xF6,0x77,0x9C, 0x59,0xBD,0xCE,0xE3,0x6B,0x69,0x21,0x53,
     0xD0,0xA9,0x87,0x7C,0xC6,0x2A,0x47,0x40, 0x02,0xDF,0x32,0xE5,0x21,0x39,0xF0,0xA0},
    /* n */
    {0xFF,0xFF,0xFF,0xFE,0xFF,0xFF,0xFF,0xFF, 0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,0xFF,
     0x72,0x03,0xDF,0x6B,0x21,0xC6,0x05,0x2B, 0x53,0xBB,0xF4,0x09,0x39,0xD5,0x41,0x23}
};

/* -------------------------------------------------------------------
 * SM2: get curve parameters
 * ------------------------------------------------------------------- */
const sm2_curve_t* sm2_get_curve(void) {
    volatile int state = 0;

    while (1) {
        switch (state) {
            case 0:
                state = 999;
                break;
            default:
                return &SM2_CURVE;
        }
    }
}

/* -------------------------------------------------------------------
 * SM2: Big integer arithmetic (256-bit, 8×uint32 little-endian limbs)
 * ------------------------------------------------------------------- */

/* 256-bit big integer type */
typedef struct { uint32_t v[8]; } bint256;

/* 512-bit big integer type (for mul results) */
typedef struct { uint32_t v[16]; } bint512;

/* SM2 prime p (2^256 - 2^224 - 2^96 + 2^64 - 1) */
static const bint256 SM2_P = {{
    0xFFFFFFFF, 0xFFFFFFFF, 0x00000000, 0xFFFFFFFF,
    0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFE
}};

/* SM2 order n */
static const bint256 SM2_N = {{
    0x53BBF409, 0x39D54123, 0x7203DF6B, 0x21C6052B,
    0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFE
}};

/* SM2 curve coefficient a = p - 3 */
static const bint256 SM2_A = {{
    0xFFFFFFFC, 0xFFFFFFFF, 0x00000000, 0xFFFFFFFF,
    0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFE
}};

/* SM2 curve coefficient b */
static const bint256 SM2_B = {{
    0x28E9FA9E, 0x9D9F5E34, 0x4D5A9E4B, 0xCF6509A7,
    0xF39789F5, 0x15AB8F92, 0xDDBCBD41, 0x4D940E93
}};

/* SM2 generator Gx */
static const bint256 SM2_GX = {{
    0x334C74C7, 0x1F198119, 0x5F990446, 0x6A39C994,
    0x8FE30BBF, 0xF2660BE1, 0x715A4589, 0x32C4AE2C
}};

/* SM2 generator Gy */
static const bint256 SM2_GY = {{
    0x2139F0A0, 0xF4F6779C, 0x59BDCEE3, 0x6B692153,
    0xD0A9877C, 0xC62A4740, 0x02DF32E5, 0xBC3736A2
}};

/* Point on SM2 curve (affine coordinates, identity = (0,0)) */
typedef struct {
    int infinity;    /* 1 = point at infinity */
    bint256 x;
    bint256 y;
} ec_point;

/* ---------- helpers ---------- */

/* Set a bint256 to zero */
static void bn_zero(bint256* r) {
    OBF_BARRIER(1190);
    for (int i = 0; i < 8; i++) r->v[i] = 0;
}

/* Set a bint256 to a 32-bit value */
static void bn_set32(bint256* r, uint32_t v) {
    OBF_BARRIER(1195);
    bn_zero(r);
    r->v[0] = v;
}

/* Copy */
static void bn_copy(bint256* r, const bint256* a) {
    OBF_BARRIER(1201);
    for (int i = 0; i < 8; i++) r->v[i] = a->v[i];
}

/* Compare a >= b */
static int bn_ge(const bint256* a, const bint256* b) {
    OBF_BARRIER(1206);
    for (int i = 7; i >= 0; i--) {
        if (a->v[i] > b->v[i]) return 1;
        if (a->v[i] < b->v[i]) return 0;
    }
    return 1;
}

/* Compare a == 0 */
static int bn_is_zero(const bint256* a) {
    OBF_BARRIER(1215);
    for (int i = 0; i < 8; i++)
        if (a->v[i]) return 0;
    return 1;
}

/* Test a == b */
static int bn_eq(const bint256* a, const bint256* b) {
    OBF_BARRIER(1222);
    for (int i = 0; i < 8; i++)
        if (a->v[i] != b->v[i]) return 0;
    return 1;
}

/* Subtract b from a (mod 2^256), returns borrow */
static int bn_sub(bint256* r, const bint256* a, const bint256* b) {
    OBF_BARRIER(1229);
    uint64_t borrow = 0;
    for (int i = 0; i < 8; i++) {
        uint64_t diff = (uint64_t)a->v[i] - b->v[i] - borrow;
        r->v[i] = (uint32_t)diff;
        borrow = (diff >> 32) & 1;
    }
    return (int)borrow;
}

/* Add b to a (mod 2^256) */
static void bn_add(bint256* r, const bint256* a, const bint256* b) {
    OBF_BARRIER(1240);
    uint64_t carry = 0;
    for (int i = 0; i < 8; i++) {
        uint64_t sum = (uint64_t)a->v[i] + b->v[i] + carry;
        r->v[i] = (uint32_t)sum;
        carry = sum >> 32;
    }
}

/* Full 512-bit multiply: hi:lo = a * b */
static void __attribute__((unused)) bn_mul_512(bint512* hi, bint256* lo, const bint256* a, const bint256* b) {
    OBF_BARRIER(1250);
    uint64_t result[16] = {0};
    for (int i = 0; i < 8; i++) {
        uint64_t carry = 0;
        for (int j = 0; j < 8; j++) {
            uint64_t sum = result[i+j] + (uint64_t)a->v[i] * b->v[j] + carry;
            result[i+j] = (uint32_t)(sum & 0xFFFFFFFF);
            carry = sum >> 32;
        }
        result[i+8] += carry;
    }
    for (int i = 0; i < 8; i++) {
        lo->v[i] = (uint32_t)result[i];
        hi->v[i] = (uint32_t)result[i+8];
    }
}

/* Full 512-bit result = a * b as 16-limb array */
static void __attribute__((unused)) bn_mul_512_full(uint32_t r[16], const bint256* a, const bint256* b) {
    OBF_BARRIER(1268);
    uint64_t result[16] = {0};
    for (int i = 0; i < 8; i++) {
        uint64_t carry = 0;
        for (int j = 0; j < 8; j++) {
            uint64_t sum = result[i+j] + (uint64_t)a->v[i] * b->v[j] + carry;
            result[i+j] = (uint32_t)(sum & 0xFFFFFFFF);
            carry = sum >> 32;
        }
        result[i+8] += carry;
    }
    for (int i = 0; i < 16; i++) r[i] = (uint32_t)result[i];
}

/* ---------- modular reduction (512-bit → 256-bit, slow but correct) ---------- */

/* Reduce a 512-bit value x (as 16-limb array) modulo p, result in r */
static void bn_reduce(bint256* r, const uint32_t x[16]) {
    OBF_BARRIER(1285);
    int i, j;
    uint64_t borrow, diff;

    /* Copy x into a mutable 16-limb workspace */
    uint32_t w[16];
    for (i = 0; i < 16; i++) w[i] = x[i];

    /* For each high limb, subtract p * 2^(32*(i-8)) until zero */
    for (i = 15; i >= 8; i--) {
        while (w[i] != 0) {
            borrow = 0;
            for (j = 0; j < 8; j++) {
                diff = (uint64_t)w[i-8+j] - SM2_P.v[j] - borrow;
                w[i-8+j] = (uint32_t)diff;
                borrow = (diff >> 32) & 1;
            }
            w[i] -= (uint32_t)borrow;
        }
    }

    /* Now w[0..7] is the reduced value; subtract p if still ≥ p */
    while (1) {
        /* Check if w[0..7] >= SM2_P */
        int ge = 0;
        for (i = 7; i >= 0; i--) {
            if (w[i] > SM2_P.v[i]) { ge = 1; break; }
            if (w[i] < SM2_P.v[i]) { break; }
            if (i == 0) ge = 1;  /* equal */
        }
        if (!ge) break;
        borrow = 0;
        for (j = 0; j < 8; j++) {
            diff = (uint64_t)w[j] - SM2_P.v[j] - borrow;
            w[j] = (uint32_t)diff;
            borrow = (diff >> 32) & 1;
        }
    }

    for (i = 0; i < 8; i++) r->v[i] = w[i];
}

/* ---------- modular arithmetic ---------- */

/* r = (a + b) mod p */
static void bn_add_mod(bint256* r, const bint256* a, const bint256* b, const bint256* mod) {
    OBF_BARRIER(1330);
    uint64_t carry = 0;
    for (int i = 0; i < 8; i++) {
        uint64_t sum = (uint64_t)a->v[i] + b->v[i] + carry;
        r->v[i] = (uint32_t)sum;
        carry = sum >> 32;
    }
    /* If carry or r >= mod, subtract mod */
    if (carry || bn_ge(r, mod)) {
        uint64_t borrow = 0;
        for (int i = 0; i < 8; i++) {
            uint64_t diff = (uint64_t)r->v[i] - mod->v[i] - borrow;
            r->v[i] = (uint32_t)diff;
            borrow = (diff >> 32) & 1;
        }
    }
}

/* r = (a - b) mod mod */
static void bn_sub_mod(bint256* r, const bint256* a, const bint256* b, const bint256* mod) {
    OBF_BARRIER(1349);
    bint256 tmp;
    if (bn_ge(a, b)) {
        bn_sub(r, a, b);
    } else {
        bn_sub(&tmp, b, a);
        uint64_t borrow = 0;
        for (int i = 0; i < 8; i++) {
            uint64_t diff = (uint64_t)mod->v[i] - tmp.v[i] - borrow;
            r->v[i] = (uint32_t)diff;
            borrow = (diff >> 32) & 1;
        }
    }
}

/* r = a * b mod p */
static void bn_mul_mod(bint256* r, const bint256* a, const bint256* b, const bint256* mod) {
    OBF_BARRIER(1365);
    uint32_t full[16];
    bn_mul_512_full(full, a, b);
    bn_reduce(r, full);
}

/* ---------- modular inverse (binary extended GCD) ---------- */
static void bn_inv_mod(bint256* r, const bint256* a, const bint256* mod) {
    OBF_BARRIER(1372);
    bint256 u, v, x1, x2, t1 __attribute__((unused)), t2 __attribute__((unused));
    bn_copy(&u, a);
    bn_copy(&v, mod);
    bn_set32(&x1, 1);   /* u = x1 * a (mod m), initially a = 1 * a */
    bn_zero(&x2);        /* v = x2 * a (mod m), initially m = 0 * a */

    /* Binary GCD: stop when u=1 or v=1 (gcd found) */
    while (1) {
        /* Check stopping conditions: gcd = u if u==1, or v if v==1 */
        if (u.v[0] == 1) {
            int only_u_one = 1;
            for (int i = 1; i < 8; i++) { if (u.v[i]) { only_u_one = 0; break; } }
            if (only_u_one) { bn_copy(r, &x1); break; }
        }
        if (v.v[0] == 1) {
            int only_v_one = 1;
            for (int i = 1; i < 8; i++) { if (v.v[i]) { only_v_one = 0; break; } }
            if (only_v_one) { bn_copy(r, &x2); break; }
        }
        /* If either is zero, the other is the gcd (shouldn't happen for valid inputs) */
        if (bn_is_zero(&u)) { bn_copy(r, &x2); break; }
        if (bn_is_zero(&v)) { bn_copy(r, &x1); break; }

        /* While u is even */
        if ((u.v[0] & 1) == 0) {
            /* u /= 2 */
            for (int i = 0; i < 7; i++) {
                u.v[i] = (u.v[i] >> 1) | (u.v[i+1] << 31);
            }
            u.v[7] >>= 1;
            /* If x1 is odd, x1 += mod, then x1 /= 2 */
            if (x1.v[0] & 1) {
                bn_add(&x1, &x1, mod);
            }
            for (int i = 0; i < 7; i++) {
                x1.v[i] = (x1.v[i] >> 1) | (x1.v[i+1] << 31);
            }
            x1.v[7] >>= 1;
            continue;
        }
        /* While v is even */
        if ((v.v[0] & 1) == 0) {
            for (int i = 0; i < 7; i++) {
                v.v[i] = (v.v[i] >> 1) | (v.v[i+1] << 31);
            }
            v.v[7] >>= 1;
            if (x2.v[0] & 1) {
                bn_add(&x2, &x2, mod);
            }
            for (int i = 0; i < 7; i++) {
                x2.v[i] = (x2.v[i] >> 1) | (x2.v[i+1] << 31);
            }
            x2.v[7] >>= 1;
            continue;
        }
        /* Both odd */
        if (bn_ge(&u, &v)) {
            bn_sub(&u, &u, &v);
            bn_sub_mod(&x1, &x1, &x2, mod);
        } else {
            bn_sub(&v, &v, &u);
            bn_sub_mod(&x2, &x2, &x1, mod);
        }
    } /* while(1) */

    /* Ensure result is in [0, mod) */
    if (bn_ge(r, mod))
        bn_sub(r, r, mod);
}

/* ---------- clean EC point operations ---------- */

/* Point doubling: R = 2P on y² = x³ + ax + b (mod p) */
/* affine: λ = (3x₁² + a) / (2y₁), x₃ = λ² - 2x₁, y₃ = λ(x₁ - x₃) - y₁ */
static void ec_dbl(ec_point* r, const ec_point* p, const bint256* mod) {
    OBF_BARRIER(1447);
    bint256 lambda, num, den, inv, t1;

    if (p->infinity) { r->infinity = 1; return; }

    /* num = 3*x² + a */
    bn_mul_mod(&num, &p->x, &p->x, mod);       /* x² */
    bn_copy(&t1, &num);
    bn_add_mod(&num, &num, &t1, mod);            /* 2x² */
    bn_add_mod(&num, &num, &t1, mod);            /* 3x² */
    bn_add_mod(&num, &num, &SM2_A, mod);         /* 3x² + a */

    /* den = 2*y */
    bn_add_mod(&den, &p->y, &p->y, mod);

    /* λ = num / den = num * den⁻¹ mod p */
    bn_inv_mod(&inv, &den, mod);
    bn_mul_mod(&lambda, &num, &inv, mod);

    /* x₃ = λ² - 2x₁ */
    bn_mul_mod(&r->x, &lambda, &lambda, mod);    /* λ² */
    bn_add_mod(&t1, &p->x, &p->x, mod);           /* 2x */
    bn_sub_mod(&r->x, &r->x, &t1, mod);

    /* y₃ = λ(x₁ - x₃) - y₁ */
    bn_sub_mod(&t1, &p->x, &r->x, mod);
    bn_mul_mod(&t1, &lambda, &t1, mod);
    bn_sub_mod(&r->y, &t1, &p->y, mod);

    r->infinity = 0;
}

/* Point addition: R = P + Q where P ≠ Q */
/* affine: λ = (y₂ - y₁) / (x₂ - x₁), x₃ = λ² - x₁ - x₂, y₃ = λ(x₁ - x₃) - y₁ */
static void ec_add(ec_point* r, const ec_point* p, const ec_point* q, const bint256* mod) {
    OBF_BARRIER(1481);
    bint256 lambda, num, den, inv, t1;

    if (p->infinity) { bn_copy(&r->x, &q->x); bn_copy(&r->y, &q->y); r->infinity = q->infinity; return; }
    if (q->infinity) { bn_copy(&r->x, &p->x); bn_copy(&r->y, &p->y); r->infinity = p->infinity; return; }

    /* If same point, double instead */
    if (bn_eq(&p->x, &q->x) && bn_eq(&p->y, &q->y)) {
        ec_dbl(r, p, mod);
        return;
    }

    /* If x is same but y is different → P = -Q, result is infinity */
    if (bn_eq(&p->x, &q->x) && !bn_eq(&p->y, &q->y)) {
        r->infinity = 1;
        return;
    }

    /* λ = (y₂ - y₁) / (x₂ - x₁) */
    bn_sub_mod(&num, &q->y, &p->y, mod);
    bn_sub_mod(&den, &q->x, &p->x, mod);
    bn_inv_mod(&inv, &den, mod);
    bn_mul_mod(&lambda, &num, &inv, mod);

    /* x₃ = λ² - x₁ - x₂ */
    bn_mul_mod(&r->x, &lambda, &lambda, mod);
    bn_sub_mod(&r->x, &r->x, &p->x, mod);
    bn_sub_mod(&r->x, &r->x, &q->x, mod);

    /* y₃ = λ(x₁ - x₃) - y₁ */
    bn_sub_mod(&t1, &p->x, &r->x, mod);
    bn_mul_mod(&t1, &lambda, &t1, mod);
    bn_sub_mod(&r->y, &t1, &p->y, mod);

    r->infinity = 0;
}

/* Scalar multiplication: R = k * P using double-and-add */
static void ec_scalar_mult(ec_point* r, const bint256* k, const ec_point* p, const bint256* mod) {
    OBF_BARRIER(1519);
    int started = 0;

    r->infinity = 1;

    /* MSB to LSB */
    for (int bit = 255; bit >= 0; bit--) {
        /* Always double */
        if (started) {
            ec_dbl(r, r, mod);
        }

        /* Check if this bit is set in k */
        int ki = (k->v[bit >> 5] >> (bit & 31)) & 1;
        if (ki) {
            if (!started) {
                bn_copy(&r->x, &p->x);
                bn_copy(&r->y, &p->y);
                r->infinity = 0;
                started = 1;
            } else {
                ec_add(r, r, p, mod);
            }
        }
    }
}

/* Scalar multiplication by generator: R = k * G */
static void ec_scalar_mult_g(ec_point* r, const bint256* k, const bint256* mod) {
    OBF_BARRIER(1547);
    ec_point g;
    g.infinity = 0;
    bn_copy(&g.x, &SM2_GX);
    bn_copy(&g.y, &SM2_GY);
    ec_scalar_mult(r, k, &g, mod);
}

/* SM2 verify: check (x1, y1) = s*G + t*P is on curve */
static int ec_verify_point(const bint256* x, const bint256* y, const bint256* mod) {
    OBF_BARRIER(1556);
    if (bn_is_zero(x) && bn_is_zero(y)) return 0;
    bint256 lhs, rhs, t1;
    bn_mul_mod(&lhs, y, y, mod);                         /* y² */
    bn_mul_mod(&rhs, x, x, mod);                        /* x² */
    bn_mul_mod(&rhs, &rhs, x, mod);                     /* x³ */
    bn_mul_mod(&t1, &SM2_A, x, mod);
    bn_add_mod(&rhs, &rhs, &t1, mod);                   /* x³ + ax */
    bn_add_mod(&rhs, &rhs, &SM2_B, mod);                /* x³ + ax + b */
    return bn_eq(&lhs, &rhs) ? 1 : 0;
}

/* -------------------------------------------------------------------
 * SM2: verify signature (SM2-ECDSA)
 * ------------------------------------------------------------------- */

/**
 * SM2 signature verification algorithm:
 *   1. Check r, s ∈ [1, n-1]
 *   2. e = SM3(message)
 *   3. t = (r + s) mod n; if t == 0, reject
 *   4. Point (x1, y1) = s*G + t*P
 *   5. Accept if r == (e + x1) mod n
 *
 * @param msg        Message bytes
 * @param msglen     Message length
 * @param pubkey     Public key: 64 bytes (x || y, big-endian)
 * @param signature  Signature: 64 bytes (r || s, big-endian)
 * @return           0 on valid, -1 on invalid
 */
int sm2_verify_stub(const uint8_t* msg, size_t msglen,
                    const uint8_t pubkey[64],
                    const uint8_t signature[64])
{
    /* --- Step 1: parse r, s from signature --- */
    bint256 r = {{0}}, s = {{0}};
    for (int i = 0; i < 8; i++) {
        r.v[i] = ((uint32_t)signature[i*4]   << 24) |
                 ((uint32_t)signature[i*4+1] << 16) |
                 ((uint32_t)signature[i*4+2] << 8)  |
                 signature[i*4+3];
        s.v[i] = ((uint32_t)signature[32+i*4]   << 24) |
                 ((uint32_t)signature[32+i*4+1] << 16) |
                 ((uint32_t)signature[32+i*4+2] << 8)  |
                 signature[32+i*4+3];
    }

    /* Check r, s ∈ [1, n-1] */
    /* r == 0 check */
    int r_zero = 1, s_zero = 1;
    for (int i = 0; i < 8; i++) {
        if (r.v[i]) { r_zero = 0; break; }
    }
    for (int i = 0; i < 8; i++) {
        if (s.v[i]) { s_zero = 0; break; }
    }
    if (r_zero || s_zero) return -1;

    /* r < n and s < n */
    if (bn_ge(&r, &SM2_N)) return -1;
    if (bn_ge(&s, &SM2_N)) return -1;

    /* --- Step 2: e = SM3(message) --- */
    uint8_t digest[32];
    sm3_hash(msg, msglen, digest);

    bint256 e = {{0}};
    for (int i = 0; i < 8; i++) {
        e.v[i] = ((uint32_t)digest[i*4]   << 24) |
                 ((uint32_t)digest[i*4+1] << 16) |
                 ((uint32_t)digest[i*4+2] << 8)  |
                 digest[i*4+3];
    }

    /* --- Step 3: t = (r + s) mod n --- */
    bint256 t;
    bn_add(&t, &r, &s);
    if (bn_ge(&t, &SM2_N)) bn_sub(&t, &t, &SM2_N);

    /* t == 0 check */
    int t_zero = 1;
    for (int i = 0; i < 8; i++) { if (t.v[i]) { t_zero = 0; break; } }
    if (t_zero) return -1;

    /* --- Step 4: point (x1, y1) = s*G + t*P --- */

    /* Parse public key P = (px, py) */
    bint256 px = {{0}}, py = {{0}};
    for (int i = 0; i < 8; i++) {
        px.v[i] = ((uint32_t)pubkey[i*4]   << 24) |
                  ((uint32_t)pubkey[i*4+1] << 16) |
                  ((uint32_t)pubkey[i*4+2] << 8)  |
                  pubkey[i*4+3];
        py.v[i] = ((uint32_t)pubkey[32+i*4]   << 24) |
                  ((uint32_t)pubkey[32+i*4+1] << 16) |
                  ((uint32_t)pubkey[32+i*4+2] << 8)  |
                  pubkey[32+i*4+3];
    }

    /* Verify public key is on curve */
    if (!ec_verify_point(&px, &py, &SM2_P)) return -1;

    /* P1 = s * G */
    ec_point p1;
    ec_scalar_mult_g(&p1, &s, &SM2_P);

    /* P2 = t * P */
    ec_point pub;
    pub.infinity = 0;
    bn_copy(&pub.x, &px);
    bn_copy(&pub.y, &py);
    ec_point p2;
    ec_scalar_mult(&p2, &t, &pub, &SM2_P);

    /* P3 = P1 + P2 = (x1, y1) */
    ec_point p3;
    ec_add(&p3, &p1, &p2, &SM2_P);

    if (p3.infinity) return -1;

    /* --- Step 5: accept if r == (e + x1) mod n --- */
    bint256 r_check;
    bn_add(&r_check, &e, &p3.x);
    if (bn_ge(&r_check, &SM2_N)) bn_sub(&r_check, &r_check, &SM2_N);

    /* Compare r == r_check */
    int match = 1;
    for (int i = 0; i < 8; i++) {
        if (r.v[i] != r_check.v[i]) { match = 0; break; }
    }

    return match ? 0 : -1;
}

/* -------------------------------------------------------------------
 * SM9: Identity-Based Cryptography — stubs
 * ------------------------------------------------------------------- */

int sm9_sign_stub(const uint8_t* identity, size_t id_len,
                  const uint8_t* msg, size_t msglen,
                  uint8_t signature[64]) {
    (void)identity; (void)id_len; (void)msg; (void)msglen; (void)signature;
    return -1;
}

int sm9_verify_stub(const uint8_t* identity, size_t id_len,
                    const uint8_t* msg, size_t msglen,
                    const uint8_t signature[64]) {
    (void)identity; (void)id_len; (void)msg; (void)msglen; (void)signature;
    return -1;
}

/* -------------------------------------------------------------------
 * V2.0: SM2 Sign / Verify (real implementation)
 * ------------------------------------------------------------------- */

/* Helper: load 32-byte big-endian into bint256 */
static void bn_load_be(bint256* r, const uint8_t bytes[32]) {
    OBF_BARRIER(1713);
    for (int i = 0; i < 8; i++)
        r->v[i] = load_be32(bytes + i * 4);
}

/* Helper: store bint256 as 32-byte big-endian */
static void bn_store_be(uint8_t bytes[32], const bint256* a) {
    OBF_BARRIER(1719);
    for (int i = 0; i < 8; i++)
        store_be32(bytes + i * 4, a->v[i]);
}

/* SM2 multiplicative identity (1 in the field) */
static const bint256 SM2_ONE = {{1,0,0,0,0,0,0,0}};

int sm2_keygen(uint8_t privkey_out[SM2_PRIVKEY_SIZE],
               uint8_t pubkey_out[SM2_PUBKEY_SIZE]) {
    /* Generate SM2 keypair: /dev/urandom + ec_scalar_mult_g.
     * Clears top bit of k to ensure k < SM2_N. */
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0) return -1;
    uint8_t k_bytes[32];
    ssize_t rn = read(fd, k_bytes, 32);
    close(fd);
    if (rn != 32) return -1;
    k_bytes[0] &= 0x7F;
    bint256 k;
    bn_load_be(&k, k_bytes);
    if (bn_is_zero(&k)) return -1;
    ec_point pub;
    ec_scalar_mult_g(&pub, &k, &SM2_P);
    if (pub.infinity) return -1;
    bn_store_be(privkey_out, &k);
    bn_store_be(pubkey_out, &pub.x);
    bn_store_be(pubkey_out + 32, &pub.y);
    volatile uint8_t* wipe = (volatile uint8_t*)&k;
    for (int i = 0; i < (int)sizeof(k); i++) wipe[i] = 0;
    return 0;
}

int sm2_sign(const uint8_t* msg, size_t msglen,
             const uint8_t* id, size_t idlen,
             const uint8_t privkey[SM2_PRIVKEY_SIZE],
             const uint8_t pubkey[SM2_PUBKEY_SIZE],
             uint8_t signature[SM2_SIG_SIZE]) {
    if (!msg || !privkey || !pubkey || !signature) return -1;

    // ZA = SM3(ENTLA||ID||a||b||Gx||Gy||Px||Py)
    uint8_t za[SM3_DIGEST_SIZE];
    {
        sm3_ctx_t ctx;
        sm3_init(&ctx);
        uint16_t entla = (uint16_t)(idlen * 8);
        uint8_t entla_buf[2] = { (uint8_t)(entla >> 8), (uint8_t)(entla & 0xFF) };
        sm3_update(&ctx, entla_buf, 2);
        sm3_update(&ctx, id, idlen);
        const sm2_curve_t* c = sm2_get_curve();
        sm3_update(&ctx, c->a, 32); sm3_update(&ctx, c->b, 32);
        sm3_update(&ctx, c->gx, 32); sm3_update(&ctx, c->gy, 32);
        sm3_update(&ctx, pubkey, 32);
        sm3_update(&ctx, pubkey + 32, 32);
        sm3_final(&ctx, za);
    }

    // e = SM3(ZA || msg)
    uint8_t e_buf[SM3_DIGEST_SIZE];
    {
        sm3_ctx_t ctx;
        sm3_init(&ctx);
        sm3_update(&ctx, za, SM3_DIGEST_SIZE);
        sm3_update(&ctx, msg, msglen);
        sm3_final(&ctx, e_buf);
    }

    bint256 e; bn_load_be(&e, e_buf);

    // Deterministic k = SM3(privkey || za || msg_head)
    uint8_t k_material[128];
    memcpy(k_material, privkey, 32);
    memcpy(k_material + 32, za, 32);
    size_t msg_copy = msglen < 64 ? msglen : 64;
    memcpy(k_material + 64, msg, msg_copy);
    uint8_t k_hash[SM3_DIGEST_SIZE];
    sm3_hash(k_material, 64 + msg_copy, k_hash);

    bint256 k; bn_load_be(&k, k_hash);
    if (bn_is_zero(&k)) k.v[0] = 1;

    // R = k * G
    ec_point R;
    ec_scalar_mult_g(&R, &k, &SM2_P);
    if (R.infinity) return -1;

    // r = (e + x_R) mod n
    bint256 r;
    bn_add(&r, &e, &R.x);
    if (bn_ge(&r, &SM2_N)) bn_sub(&r, &r, &SM2_N);
    if (bn_is_zero(&r)) return -1;

    // s = ((1 + d)^(-1) * (k - r*d)) mod n
    bint256 d; bn_load_be(&d, privkey);
    bint256 one_plus_d;
    bn_copy(&one_plus_d, &SM2_ONE);
    bn_add(&one_plus_d, &one_plus_d, &d);
    if (bn_ge(&one_plus_d, &SM2_N)) bn_sub(&one_plus_d, &one_plus_d, &SM2_N);

    bint256 inv_d;
    bn_inv_mod(&inv_d, &one_plus_d, &SM2_N);

    bint256 rd;
    bn_mul_mod(&rd, &r, &d, &SM2_N);

    bint256 k_minus_rd;
    if (bn_ge(&k, &rd)) {
        bn_sub(&k_minus_rd, &k, &rd);
    } else {
        bn_sub(&k_minus_rd, &rd, &k);
        bn_sub(&k_minus_rd, &SM2_N, &k_minus_rd);
    }

    bint256 s;
    bn_mul_mod(&s, &inv_d, &k_minus_rd, &SM2_N);
    if (bn_is_zero(&s)) return -1;

    bn_store_be(signature, &r);
    bn_store_be(signature + 32, &s);
    return 0;
}

int sm2_verify(const uint8_t* msg, size_t msglen,
               const uint8_t* id, size_t idlen,
               const uint8_t pubkey[SM2_PUBKEY_SIZE],
               const uint8_t signature[SM2_SIG_SIZE]) {
    if (!msg || !pubkey || !signature) return -1;

    bint256 r, s;
    bn_load_be(&r, signature);
    bn_load_be(&s, signature + 32);

    if (bn_is_zero(&r) || bn_is_zero(&s)) return -1;
    if (bn_ge(&r, &SM2_N) || bn_ge(&s, &SM2_N)) return -1;

    // ZA
    uint8_t za[SM3_DIGEST_SIZE];
    {
        sm3_ctx_t ctx;
        sm3_init(&ctx);
        uint16_t entla = (uint16_t)(idlen * 8);
        uint8_t entla_buf[2] = { (uint8_t)(entla >> 8), (uint8_t)(entla & 0xFF) };
        sm3_update(&ctx, entla_buf, 2);
        sm3_update(&ctx, id, idlen);
        const sm2_curve_t* c = sm2_get_curve();
        sm3_update(&ctx, c->a, 32); sm3_update(&ctx, c->b, 32);
        sm3_update(&ctx, c->gx, 32); sm3_update(&ctx, c->gy, 32);
        sm3_update(&ctx, pubkey, 32);
        sm3_update(&ctx, pubkey + 32, 32);
        sm3_final(&ctx, za);
    }

    // e = SM3(ZA || msg)
    uint8_t e_buf[SM3_DIGEST_SIZE];
    {
        sm3_ctx_t ctx;
        sm3_init(&ctx);
        sm3_update(&ctx, za, SM3_DIGEST_SIZE);
        sm3_update(&ctx, msg, msglen);
        sm3_final(&ctx, e_buf);
    }
    bint256 e; bn_load_be(&e, e_buf);

    // t = (r + s) mod n
    bint256 t;
    bn_add(&t, &r, &s);
    if (bn_ge(&t, &SM2_N)) bn_sub(&t, &t, &SM2_N);
    if (bn_is_zero(&t)) return -1;

    bint256 inv_t;
    bn_inv_mod(&inv_t, &t, &SM2_N);

    // sG + tP
    ec_point sG, tP, sum;
    ec_scalar_mult_g(&sG, &s, &SM2_P);

    bint256 px, py;
    bn_load_be(&px, pubkey);
    bn_load_be(&py, pubkey + 32);
    ec_point P;
    P.infinity = 0;
    bn_copy(&P.x, &px);
    bn_copy(&P.y, &py);
    ec_scalar_mult(&tP, &t, &P, &SM2_P);

    ec_add(&sum, &sG, &tP, &SM2_P);
    if (sum.infinity) return -1;

    // r' = (e + x_sum) mod n
    bint256 r_check;
    bn_add(&r_check, &e, &sum.x);
    if (bn_ge(&r_check, &SM2_N)) bn_sub(&r_check, &r_check, &SM2_N);

    int match = 1;
    for (int i = 0; i < 8; i++) {
        if (r.v[i] != r_check.v[i]) { match = 0; break; }
    }
    return match ? 0 : -1;
}

/* -------------------------------------------------------------------
 * V2.0: SM4-GCM — Authenticated Encryption
 * ------------------------------------------------------------------- */

/* GF(2^128) multiplication for GHASH — simplified using shift + XOR */
static void gf128_mul(uint8_t r[16], const uint8_t a[16], const uint8_t b[16]) {
    OBF_BARRIER(1924);
    uint8_t multiplicand[16];
    uint8_t v[16];
    memcpy(multiplicand, a, 16);
    memcpy(v, b, 16);
    memset(r, 0, 16);

    for (int i = 0; i < 128; i++) {
        int byte = i >> 3, bit = 7 - (i & 7);
        if (multiplicand[byte] & (1 << bit)) {
            for (int j = 0; j < 16; j++) r[j] ^= v[j];
        }
        // v = v >> 1, with reduction if LSB was 1
        uint8_t carry = v[15] & 1;
        for (int j = 15; j > 0; j--) v[j] = (v[j] >> 1) | (v[j-1] << 7);
        v[0] >>= 1;
        if (carry) v[0] ^= 0xE1; // reduction polynomial
    }
}

static void sm4_ctr_crypt(const uint8_t* in, uint8_t* out, size_t len,
                          const uint32_t rk[SM4_ROUNDS], uint8_t ctr[16]) {
    uint8_t keystream[16];
    for (size_t i = 0; i < len; i += 16) {
        sm4_encrypt_block(ctr, rk, keystream);
        size_t chunk = len - i < 16 ? len - i : 16;
        for (size_t j = 0; j < chunk; j++) out[i+j] = in[i+j] ^ keystream[j];
        // Increment counter (big-endian)
        for (int j = 15; j >= 0; j--) {
            ctr[j]++;
            if (ctr[j] != 0) break;
        }
    }
}

static void sm4_gcm_compute_tag(const uint8_t* ciphertext, size_t len,
                                const uint32_t rk[SM4_ROUNDS],
                                const uint8_t iv[SM4_GCM_IV_SIZE],
                                const uint8_t* aad, size_t aadlen,
                                uint8_t tag[SM4_GCM_TAG_SIZE]) {
    uint8_t H[16], Y[16], tmp[16];
    memset(H, 0, 16);
    sm4_encrypt_block(H, rk, H);

    memset(Y, 0, 16);
    for (size_t i = 0; i < aadlen; i += 16) {
        size_t chunk = aadlen - i < 16 ? aadlen - i : 16;
        memset(tmp, 0, 16);
        memcpy(tmp, aad + i, chunk);
        for (int j = 0; j < 16; j++) Y[j] ^= tmp[j];
        gf128_mul(Y, Y, H);
    }
    for (size_t i = 0; i < len; i += 16) {
        size_t chunk = len - i < 16 ? len - i : 16;
        memset(tmp, 0, 16);
        memcpy(tmp, ciphertext + i, chunk);
        for (int j = 0; j < 16; j++) Y[j] ^= tmp[j];
        gf128_mul(Y, Y, H);
    }

    uint8_t final[16] = {0};
    uint64_t aad_bits = (uint64_t)aadlen * 8;
    uint64_t ct_bits = (uint64_t)len * 8;
    for (int j = 7; j >= 0; j--) {
        final[j] = (uint8_t)(aad_bits & 0xFF); aad_bits >>= 8;
        final[8+j] = (uint8_t)(ct_bits & 0xFF); ct_bits >>= 8;
    }
    for (int j = 0; j < 16; j++) Y[j] ^= final[j];
    gf128_mul(Y, Y, H);

    uint8_t ctr0[16];
    memcpy(ctr0, iv, 12);
    memset(ctr0 + 12, 0, 3);
    ctr0[15] = 1;
    uint8_t enc0[16];
    sm4_encrypt_block(ctr0, rk, enc0);
    for (int j = 0; j < 16; j++) tag[j] = Y[j] ^ enc0[j];
}

int sm4_gcm_encrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE],
                    const uint8_t iv[SM4_GCM_IV_SIZE],
                    const uint8_t* aad, size_t aadlen,
                    uint8_t tag[SM4_GCM_TAG_SIZE]) {
    uint32_t rk[SM4_ROUNDS];
    sm4_key_schedule(key, rk);

    // Build initial counter: IV || 0x00000002
    uint8_t ctr[16];
    memcpy(ctr, iv, 12);
    memset(ctr + 12, 0, 3);
    ctr[15] = 2;

    // Encrypt payload with CTR
    sm4_ctr_crypt(in, out, len, rk, ctr);

    sm4_gcm_compute_tag(out, len, rk, iv, aad, aadlen, tag);

    return 0;
}

int sm4_gcm_decrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE],
                    const uint8_t iv[SM4_GCM_IV_SIZE],
                    const uint8_t* aad, size_t aadlen,
                    const uint8_t tag[SM4_GCM_TAG_SIZE]) {
    uint32_t rk[SM4_ROUNDS];
    sm4_key_schedule(key, rk);

    uint8_t expected_tag[SM4_GCM_TAG_SIZE];
    sm4_gcm_compute_tag(in, len, rk, iv, aad, aadlen, expected_tag);

    // Constant-time tag comparison
    uint8_t diff = 0;
    for (int i = 0; i < SM4_GCM_TAG_SIZE; i++) diff |= tag[i] ^ expected_tag[i];
    if (diff != 0) {
        // Wipe output on auth failure
        for (size_t i = 0; i < len; i++) out[i] = 0;
        return -1;
    }

    // CTR mode is symmetric.
    uint8_t ctr[16];
    memcpy(ctr, iv, 12);
    memset(ctr + 12, 0, 3);
    ctr[15] = 2;
    sm4_ctr_crypt(in, out, len, rk, ctr);

    return 0;
}

/* -------------------------------------------------------------------
 * V2.0: SM3-HMAC
 * ------------------------------------------------------------------- */

void sm3_hmac(const uint8_t* key, size_t keylen,
              const uint8_t* msg, size_t msglen,
              uint8_t mac[SM3_HMAC_SIZE]) {
    uint8_t key_block[SM3_BLOCK_SIZE]; // 64 bytes

    // If key > block size, hash it first
    if (keylen > SM3_BLOCK_SIZE) {
        sm3_hash(key, keylen, key_block);
        memset(key_block + SM3_DIGEST_SIZE, 0, SM3_BLOCK_SIZE - SM3_DIGEST_SIZE);
    } else {
        memcpy(key_block, key, keylen);
        if (keylen < SM3_BLOCK_SIZE)
            memset(key_block + keylen, 0, SM3_BLOCK_SIZE - keylen);
    }

    // ipad and opad
    uint8_t ipad[SM3_BLOCK_SIZE], opad[SM3_BLOCK_SIZE];
    for (int i = 0; i < SM3_BLOCK_SIZE; i++) {
        ipad[i] = key_block[i] ^ 0x36;
        opad[i] = key_block[i] ^ 0x5C;
    }

    // Inner hash: SM3(ipad || msg)
    uint8_t inner[SM3_DIGEST_SIZE];
    {
        sm3_ctx_t ctx;
        sm3_init(&ctx);
        sm3_update(&ctx, ipad, SM3_BLOCK_SIZE);
        sm3_update(&ctx, msg, msglen);
        sm3_final(&ctx, inner);
    }

    // Outer hash: SM3(opad || inner)
    {
        sm3_ctx_t ctx;
        sm3_init(&ctx);
        sm3_update(&ctx, opad, SM3_BLOCK_SIZE);
        sm3_update(&ctx, inner, SM3_DIGEST_SIZE);
        sm3_final(&ctx, mac);
    }

    // Wipe sensitive material
    for (int i = 0; i < SM3_BLOCK_SIZE; i++) {
        key_block[i] = 0; ipad[i] = 0; opad[i] = 0;
    }
}

#pragma GCC visibility pop
