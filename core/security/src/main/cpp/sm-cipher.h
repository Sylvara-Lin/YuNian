#ifndef YUNIAN_SM_CIPHER_H
#define YUNIAN_SM_CIPHER_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

#pragma GCC visibility push(hidden)

/* ================================================================
 * SM4 — 128-bit block cipher, 128-bit key (Chinese national cipher)
 * ================================================================
 * Block size: 16 bytes (128 bits)
 * Key size:   16 bytes (128 bits)
 * Rounds:     32 (Feistel network)
 * ================================================================ */

#define SM4_BLOCK_SIZE  16
#define SM4_KEY_SIZE    16
#define SM4_ROUNDS      32

/* SM4 key schedule: expand 128-bit key into 32 round keys.
 * key[16]: 128-bit input key.
 * rk[32]:  output 32 round keys (each uint32_t).
 */
void sm4_key_schedule(const uint8_t key[SM4_KEY_SIZE], uint32_t rk[SM4_ROUNDS]);

/* FIX #7: Invalidate SM4 key schedule cache (called when DK changes) */
void sm4_invalidate_cache(void);

/* SM4 encrypt one block (ECB).
 * in[16]:  plaintext block.
 * rk[32]:  round keys from sm4_key_schedule.
 * out[16]: ciphertext block (may alias in).
 */
void sm4_encrypt_block(const uint8_t in[SM4_BLOCK_SIZE],
                       const uint32_t rk[SM4_ROUNDS],
                       uint8_t out[SM4_BLOCK_SIZE]);

/* SM4 decrypt one block (ECB).
 * in[16]:  ciphertext block.
 * rk[32]:  round keys from sm4_key_schedule.
 * out[16]: plaintext block (may alias in).
 */
void sm4_decrypt_block(const uint8_t in[SM4_BLOCK_SIZE],
                       const uint32_t rk[SM4_ROUNDS],
                       uint8_t out[SM4_BLOCK_SIZE]);

/* SM4-CBC encrypt.
 * in:      plaintext (len must be multiple of 16).
 * out:     ciphertext (len bytes).
 * len:     byte length.
 * key[16]: 128-bit key.
 * iv[16]:  initialization vector (read, modified in-place).
 * Returns 0 on success.
 */
int sm4_cbc_encrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE], uint8_t iv[SM4_BLOCK_SIZE]);

/* SM4-CBC decrypt.
 * in:      ciphertext (len must be multiple of 16).
 * out:     plaintext (len bytes).
 * len:     byte length.
 * key[16]: 128-bit key.
 * iv[16]:  initialization vector (read, modified in-place).
 * Returns 0 on success.
 */
int sm4_cbc_decrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE], uint8_t iv[SM4_BLOCK_SIZE]);

/* ================================================================
 * SM9 — Identity-Based Cryptography (declaration only)
 * ================================================================
 * SM9 is a Chinese national standard for identity-based encryption
 * and signature. It uses bilinear pairings over BN curves.
 *
 * Full implementation requires:
 *   - BN curve (256-bit) with optimal ate pairing
 *   - Identity-based key derivation
 *   - Identity-based encryption (IBE) and signature (IBS)
 *
 * Status: DECLARED — implementation pending (下一阶段)
 * ================================================================ */

#define SM9_ID_MAX_LEN  128   /* Max identity length in bytes */

/* SM9 master public key structure */
typedef struct {
    uint8_t params[128];       /* System parameters (curve, generators) */
    uint8_t mpk[64];           /* Master public key (point) */
} sm9_mpk_t;

/* SM9 user private key (derived from identity + master secret) */
typedef struct {
    uint8_t dsa[64];           /* Signing private key */
    uint8_t dea[64];           /* Encryption private key */
} sm9_sk_t;

/**
 * SM9 identity-based sign (stub — always returns -1).
 */
int sm9_sign_stub(const uint8_t* identity, size_t id_len,
                  const uint8_t* msg, size_t msglen,
                  uint8_t signature[64]);

/**
 * SM9 identity-based verify (stub — always returns -1).
 */
int sm9_verify_stub(const uint8_t* identity, size_t id_len,
                    const uint8_t* msg, size_t msglen,
                    const uint8_t signature[64]);

/* ================================================================
 * SM3 — 256-bit cryptographic hash function (Chinese national hash)
 * ================================================================
 * Output: 32 bytes (256 bits)
 * Rounds: 64 (modified SHA-256-like structure)
 * ================================================================ */

#define SM3_DIGEST_SIZE  32
#define SM3_BLOCK_SIZE   64

/* SM3 hash of a message.
 * msg:    input message bytes.
 * msglen: message length in bytes.
 * digest[32]: output hash (256 bits).
 */
void sm3_hash(const uint8_t* msg, size_t msglen, uint8_t digest[SM3_DIGEST_SIZE]);

/* SM3 incremental (init / update / final).
 * For large messages, process in chunks.
 */
typedef struct {
    uint32_t state[8];
    uint64_t count;
    uint8_t  buf[SM3_BLOCK_SIZE];
    uint32_t buflen;
} sm3_ctx_t;

void sm3_init(sm3_ctx_t* ctx);
void sm3_update(sm3_ctx_t* ctx, const uint8_t* data, size_t len);
void sm3_final(sm3_ctx_t* ctx, uint8_t digest[SM3_DIGEST_SIZE]);

/* ================================================================
 * SM2 — Elliptic curve public-key cipher (v2.0 — real impl)
 * ================================================================
 * SM2 uses a 256-bit elliptic curve over F(p).
 * Curve equation: y^2 = x^3 + a*x + b
 *
 * SM2 keygen / sign / verify implemented using modular arithmetic.
 * NOT a stub — uses bint256 field ops from sm-cipher.cpp.
 * ================================================================ */

/* SM2 public key: 64 bytes (x || y, uncompressed) */
#define SM2_PUBKEY_SIZE   64
/* SM2 private key: 32 bytes */
#define SM2_PRIVKEY_SIZE  32
/* SM2 signature: 64 bytes (r || s) */
#define SM2_SIG_SIZE      64
/* SM2 identity hash: 32 bytes (SM3 digest of ID) */
#define SM2_ID_DIGEST_SIZE SM3_DIGEST_SIZE

/* SM2 curve parameters (256-bit), stored as big-endian byte arrays. */
typedef struct {
    uint8_t p[32];   /* prime field modulus */
    uint8_t a[32];   /* coefficient a */
    uint8_t b[32];   /* coefficient b */
    uint8_t gx[32];  /* generator point x-coordinate */
    uint8_t gy[32];  /* generator point y-coordinate */
    uint8_t n[32];   /* order of generator point */
} sm2_curve_t;

/* Get the SM2 recommended curve parameters. */
const sm2_curve_t* sm2_get_curve(void);

/**
 * SM2 key generation.
 * @param privkey_out  32-byte private key (random)
 * @param pubkey_out   64-byte public key (x || y)
 * @return 0 on success, -1 on failure.
 */
int sm2_keygen(uint8_t privkey_out[SM2_PRIVKEY_SIZE],
               uint8_t pubkey_out[SM2_PUBKEY_SIZE]);

/**
 * SM2 sign with SM3 hash + identity.
 * Uses the standard SM2 signing algorithm: ZA = SM3(ENTLA||ID||a||b||Gx||Gy||Px||Py)
 * e = SM3(ZA || message), then (r, s) signature.
 *
 * @param msg         Message to sign
 * @param msglen      Message length
 * @param id          User identity string (e.g., "1234567812345678")
 * @param idlen       Identity length
 * @param privkey     32-byte private key
 * @param pubkey      64-byte public key (for ZA computation)
 * @param signature   64-byte output signature (r || s)
 * @return 0 on success, -1 on failure.
 */
int sm2_sign(const uint8_t* msg, size_t msglen,
             const uint8_t* id, size_t idlen,
             const uint8_t privkey[SM2_PRIVKEY_SIZE],
             const uint8_t pubkey[SM2_PUBKEY_SIZE],
             uint8_t signature[SM2_SIG_SIZE]);

/**
 * SM2 verify signature.
 *
 * @param msg         Original message
 * @param msglen      Message length
 * @param id          User identity (same as signing side)
 * @param idlen       Identity length
 * @param pubkey      64-byte public key
 * @param signature   64-byte signature (r || s)
 * @return 0 if valid, -1 if invalid.
 */
int sm2_verify(const uint8_t* msg, size_t msglen,
               const uint8_t* id, size_t idlen,
               const uint8_t pubkey[SM2_PUBKEY_SIZE],
               const uint8_t signature[SM2_SIG_SIZE]);

/* Legacy stub (kept for backward compat — always returns -1) */
int sm2_verify_stub(const uint8_t* msg, size_t msglen,
                    const uint8_t pubkey[64],
                    const uint8_t signature[64]);

/* ================================================================
 * SM4-GCM — Authenticated Encryption with SM4
 * ================================================================
 * GCM mode: CTR encryption + GHASH authentication.
 * Tag size: 16 bytes (128 bits, GCM standard).
 * ================================================================ */

#define SM4_GCM_TAG_SIZE    16
#define SM4_GCM_IV_SIZE     12   /* Recommended GCM IV size */

/**
 * SM4-GCM encrypt.
 * @param in       Plaintext
 * @param out      Ciphertext (same length as plaintext)
 * @param len      Byte length
 * @param key      16-byte SM4 key
 * @param iv       12-byte initialization vector (recommended)
 * @param aad      Additional Authenticated Data (can be NULL)
 * @param aadlen   AAD length
 * @param tag      Output: 16-byte authentication tag
 * @return 0 on success.
 */
int sm4_gcm_encrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE],
                    const uint8_t iv[SM4_GCM_IV_SIZE],
                    const uint8_t* aad, size_t aadlen,
                    uint8_t tag[SM4_GCM_TAG_SIZE]);

/**
 * SM4-GCM decrypt + verify.
 * @param in       Ciphertext
 * @param out      Plaintext
 * @param len      Byte length
 * @param key      16-byte SM4 key
 * @param iv       12-byte IV (same as encrypt side)
 * @param aad      AAD (same as encrypt side)
 * @param aadlen   AAD length
 * @param tag      16-byte tag to verify
 * @return 0 on success, -1 on authentication failure.
 */
int sm4_gcm_decrypt(const uint8_t* in, uint8_t* out, size_t len,
                    const uint8_t key[SM4_KEY_SIZE],
                    const uint8_t iv[SM4_GCM_IV_SIZE],
                    const uint8_t* aad, size_t aadlen,
                    const uint8_t tag[SM4_GCM_TAG_SIZE]);

/* ================================================================
 * SM3-HMAC — Keyed-Hash Message Authentication Code
 * ================================================================ */

#define SM3_HMAC_SIZE SM3_DIGEST_SIZE

/**
 * SM3-HMAC.
 * HMAC(key, msg) = SM3( (key ⊕ opad) || SM3( (key ⊕ ipad) || msg ) )
 *
 * @param key      HMAC key (arbitrary length)
 * @param keylen   Key length in bytes
 * @param msg      Message
 * @param msglen   Message length
 * @param mac      Output: 32-byte HMAC
 */
void sm3_hmac(const uint8_t* key, size_t keylen,
              const uint8_t* msg, size_t msglen,
              uint8_t mac[SM3_HMAC_SIZE]);

#pragma GCC visibility pop

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_SM_CIPHER_H */
