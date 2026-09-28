/**
 * hmac_sha256.h — Compact HMAC-SHA256 for integrity verification.
 *
 * No external dependencies. ~500 bytes of code.
 * Uses PBKDF2-derived key material (caller provides).
 *
 * Usage:
 *   uint8_t key[32] = { PBKDF2-derived };
 *   uint8_t mac[32];
 *   hmac_sha256(key, 32, data, data_len, mac);
 *   // Verify mac matches expected
 */

#ifndef YUNIAN_HMAC_SHA256_H
#define YUNIAN_HMAC_SHA256_H

#include <stdint.h>
#include <stddef.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

/* SHA-256 context */
typedef struct {
    uint8_t  buf[64];
    uint32_t state[8];
    uint64_t count;
} sha256_ctx;

static const uint32_t sha256_k[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
};

#define SHA256_ROTR(x,n) (((x) >> (n)) | ((x) << (32-(n))))
#define SHA256_CH(x,y,z)  (((x) & (y)) ^ (~(x) & (z)))
#define SHA256_MAJ(x,y,z) (((x) & (y)) ^ ((x) & (z)) ^ ((y) & (z)))
#define SHA256_S0(x) (SHA256_ROTR(x,2) ^ SHA256_ROTR(x,13) ^ SHA256_ROTR(x,22))
#define SHA256_S1(x) (SHA256_ROTR(x,6) ^ SHA256_ROTR(x,11) ^ SHA256_ROTR(x,25))
#define SHA256_s0(x) (SHA256_ROTR(x,7) ^ SHA256_ROTR(x,18) ^ ((x) >> 3))
#define SHA256_s1(x) (SHA256_ROTR(x,17) ^ SHA256_ROTR(x,19) ^ ((x) >> 10))

static inline uint32_t sha256_bswap32(uint32_t x) {
    return ((x & 0xFF) << 24) | ((x & 0xFF00) << 8) |
           ((x >> 8) & 0xFF00) | ((x >> 24) & 0xFF);
}

static void sha256_transform(uint32_t state[8], const uint8_t block[64]) {
    uint32_t w[64], a, b, c, d, e, f, g, h, t1, t2;
    for (int i = 0; i < 16; i++)
        w[i] = sha256_bswap32(((const uint32_t*)block)[i]);
    for (int i = 16; i < 64; i++)
        w[i] = SHA256_s1(w[i-2]) + w[i-7] + SHA256_s0(w[i-15]) + w[i-16];
    a = state[0]; b = state[1]; c = state[2]; d = state[3];
    e = state[4]; f = state[5]; g = state[6]; h = state[7];
    for (int i = 0; i < 64; i++) {
        t1 = h + SHA256_S1(e) + SHA256_CH(e,f,g) + sha256_k[i] + w[i];
        t2 = SHA256_S0(a) + SHA256_MAJ(a,b,c);
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d;
    state[4] += e; state[5] += f; state[6] += g; state[7] += h;
}

static void sha256_init(sha256_ctx* ctx) {
    ctx->state[0] = 0x6a09e667; ctx->state[1] = 0xbb67ae85;
    ctx->state[2] = 0x3c6ef372; ctx->state[3] = 0xa54ff53a;
    ctx->state[4] = 0x510e527f; ctx->state[5] = 0x9b05688c;
    ctx->state[6] = 0x1f83d9ab; ctx->state[7] = 0x5be0cd19;
    ctx->count = 0;
}

static void sha256_update(sha256_ctx* ctx, const uint8_t* data, size_t len) {
    size_t i = (size_t)(ctx->count & 63);
    ctx->count += len;
    while (len--) {
        ctx->buf[i++] = *data++;
        if (i == 64) { sha256_transform(ctx->state, ctx->buf); i = 0; }
    }
}

static void sha256_final(sha256_ctx* ctx, uint8_t digest[32]) {
    size_t i = (size_t)(ctx->count & 63);
    ctx->buf[i++] = 0x80;
    if (i > 56) { while (i < 64) ctx->buf[i++] = 0; sha256_transform(ctx->state, ctx->buf); i = 0; }
    while (i < 56) ctx->buf[i++] = 0;
    uint64_t bits = ctx->count * 8;
    ((uint32_t*)ctx->buf)[14] = sha256_bswap32((uint32_t)(bits >> 32));
    ((uint32_t*)ctx->buf)[15] = sha256_bswap32((uint32_t)bits);
    sha256_transform(ctx->state, ctx->buf);
    for (int j = 0; j < 8; j++) ((uint32_t*)digest)[j] = sha256_bswap32(ctx->state[j]);
}

/* ── HMAC-SHA256 ── */
#define HMAC_IPAD 0x36
#define HMAC_OPAD 0x5C

static void hmac_sha256(const uint8_t* key, size_t key_len,
                        const uint8_t* data, size_t data_len,
                        uint8_t mac[32]) {
    sha256_ctx ctx;
    uint8_t k_ipad[64], k_opad[64], inner[32];

    if (key_len > 64) {
        sha256_init(&ctx); sha256_update(&ctx, key, key_len); sha256_final(&ctx, k_ipad);
        memset(k_ipad + 32, 0, 32);
    } else {
        memcpy(k_ipad, key, key_len);
        memset(k_ipad + key_len, 0, 64 - key_len);
    }
    memcpy(k_opad, k_ipad, 64);

    for (int i = 0; i < 64; i++) { k_ipad[i] ^= HMAC_IPAD; k_opad[i] ^= HMAC_OPAD; }

    sha256_init(&ctx); sha256_update(&ctx, k_ipad, 64);
    sha256_update(&ctx, data, data_len); sha256_final(&ctx, inner);

    sha256_init(&ctx); sha256_update(&ctx, k_opad, 64);
    sha256_update(&ctx, inner, 32); sha256_final(&ctx, mac);
}

#ifdef __cplusplus
}
#endif
#endif /* YUNIAN_HMAC_SHA256_H */
