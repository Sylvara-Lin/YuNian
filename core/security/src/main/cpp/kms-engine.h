/*
 * kms-engine.h — Key Management System Engine
 *
 * FIVE-TIER Hierarchical Key Structure (v2.0):
 *
 *   MK (Master Key) — NEVER in RAM
 *     └─→ Embedded in whitebox AES lookup tables (wb_tables.inc)
 *     └─→ Three-tier obfuscation (L1:版本表 L2:启动mask L3:操作随机化)
 *     └─→ Lifetime: static, compile-time only
 *     └─→ Destroyed only via wb_aes_wipe_keys() on zero-trust breach
 *
 *   DK_ephemeral (Data Key) — per-operation, NEON-resident
 *     └─→ Derived from MK via wb_aes_encrypt(seed || monotonic_nonce)
 *     └─→ 32 bytes, loaded into NEON q0-q1 via ldp
 *     └─→ RAM copy immediately dc civac flushed (clean+invalidate)
 *     └─→ Lifetime: single cryptographic operation (~200μs)
 *     └─→ Destroyed via neon_wipe_all_keys() (movi v0..v7,#0)
 *
 *   SK (Session Key) — 4-tier performance policy
 *     ├─ SK_bulk:   once/boot  → database rows
 *     ├─ SK_session: once/conn  → TLS sessions
 *     ├─ SK_msg:     per 100msgs → chat messages
 *     └─ SK_root:    per op     → key wrap (即用即焚)
 *     └─→ Derived: SM3-KBKDF(DK || nonce || counter)
 *
 *   BK (Blinding Key) — deterministic, metadata-synced
 *     └─→ BK  = SM4_enc(DK[0:16], metadata)  ← deterministic
 *     └─→ BK' = SM4_enc(BK, DK[16:32])       ← output blinding
 *     └─→ metadata = salt(8B)||counter(8B) carried with ciphertext
 *     └─→ Lifetime: one blind/unblind cycle
 *     └─→ Does NOT depend on ARM RNG — getrandom() syscall fallback
 *
 *   AK (Attestation Key) — hardware root of trust
 *     └─→ Android KeyStore / StrongBox (TEE-backed)
 *     └─→ Signs DK hash to prove local key generation
 *     └─→ NO fallback to whitebox signature (crypto-nonsensical)
 *     └─→ Without TEE: AK unavailable, local crypto still works
 *
 * Security Properties:
 *   1. MK never enters RAM — permanently embedded in obfuscated WB tables
 *   2. DK derived per-operation, NEON-resident, RAM wiped with dc civac
 *   3. SK tiered by performance needs — only root ops use single-use keys
 *   4. BK deterministic via SM4 — same DK+metadata → same BK (encrypt/decrypt sync)
 *   5. AK TEE-only — no meaningless software fallback
 *   6. All key destruction uses volatile + dmb sy barriers + movi neon wipe
 */

#ifndef YUNIAN_KMS_ENGINE_H
#define YUNIAN_KMS_ENGINE_H

#include <cstdint>
#include <cstddef>
#include "sm-cipher.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ===================================================================
 * Key Hierarchy Constants
 * =================================================================== */

#define KMS_DK_SIZE         32   /* Data Key: 256 bits */
#define KMS_SK_SIZE         32   /* Session Key: 256 bits */
#define KMS_NONCE_SIZE      16   /* Session nonce: 128 bits */
#define KMS_MAX_SESSIONS    256  /* Max session keys before re-derivation */
#define KMS_SEED_CONST      0x4C  /* 'L' — constant encrypted by WB for DK derivation */

/* SK tier constants */
#define KMS_SK_TIER_BULK    0    /* once/boot — database, storage */
#define KMS_SK_TIER_SESSION 1    /* once/connection — TLS, network */
#define KMS_SK_TIER_MSG     2    /* per N messages — chat encryption */
#define KMS_SK_TIER_ROOT    3    /* per operation — key wrap, 即用即焚 */

/* BK (Blinding Key) constants */
#define KMS_BK_SIZE         32   /* Blinding Key: 256 bits (expanded from 128 via SM3) */
#define KMS_BK_ORIG_SIZE    16   /* Original BK before SM3 expansion */
#define KMS_METADATA_SIZE   16   /* metadata = salt(8B) || counter(8B) */
#define KMS_SALT_SIZE       8
#define KMS_NONCE_EXT_SIZE  8    /* Per-operation nonce for BK forward secrecy */

/* Key operational states */
#define KMS_STATE_UNINIT    0
#define KMS_STATE_READY     1
#define KMS_STATE_EXHAUSTED 2
#define KMS_STATE_DESTROYED 3

/* Return codes */
#define KMS_OK             0
#define KMS_ERR_STATE     -1
#define KMS_ERR_CRYPTO    -2
#define KMS_ERR_EXHAUSTED -3
#define KMS_ERR_ATTEST    -4  /* TEE attestation failed or unavailable */

/* ===================================================================
 * Key Derivation — KBKDF using SM3 / SHA-256
 * =================================================================== */

/**
 * Derive a Session Key (SK) from the KMS internal DK and session nonce.
 *
 * SK = SM3-KBKDF(DK || nonce || counter)
 * Counter is auto-incremented each call (up to KMS_MAX_SESSIONS).
 *
 * @param sk_out  Output buffer for the 32-byte Session Key (caller must wipe!)
 * @return        KMS_OK on success, KMS_ERR_STATE if not initialized,
 *                KMS_ERR_EXHAUSTED if max sessions exceeded
 */
int kms_derive_session_key(uint8_t sk_out[KMS_SK_SIZE]);

/**
 * Securely destroy a session key from memory.
 * Uses volatile pointer + data barrier to prevent dead-store elimination.
 *
 * @param sk   Pointer to the 32-byte session key to destroy (may be NULL)
 */
void kms_destroy_session_key(uint8_t sk[KMS_SK_SIZE]);

/* ===================================================================
 * Key Lifecycle Management
 * =================================================================== */

/**
 * Initialize the KMS at boot.
 * 1. Calls wb_aes_init() to initialize whitebox AES tables
 * 2. Calls kms_derive_dk() and loads DK into NEON registers
 * 3. Sets state to KMS_STATE_READY
 *
 * Must be called before any other KMS function.
 *
 * @return 0 on success, -1 on failure
 */
int kms_init(void);

/**
 * Get current KMS operational state.
 *
 * @return One of: KMS_STATE_UNINIT, KMS_STATE_READY,
 *                 KMS_STATE_EXHAUSTED, KMS_STATE_DESTROYED
 */
int kms_get_status(void);

/**
 * Securely destroy the entire keychain.
 * Wipes DK, SK, nonce, session counter, and calls wb_aes_wipe_keys().
 * After this, kms_init() must be called again.
 */
void kms_destroy_keychain(void);

/**
 * Load the Data Key (DK) from memory into NEON registers using inline
 * assembly. After this call, the RAM copy of DK should be cacheline-flushed.
 *
 * NEON registers q0 and q1 store the 32-byte DK.
 * Called automatically by kms_init().
 */
void kms_load_dk_to_neon(void);

/* ===================================================================
 * V2.0 New Interfaces — Five-Tier Key System
 * =================================================================== */

/**
 * Derive DK_ephemeral: wb_aes_encrypt(seed || monotonic_nonce).
 * Loads result into NEON q0-q1, immediately dc civac the RAM copy.
 *
 * MUST be called at the start of every crypto operation.
 * After this call, DK exists ONLY in NEON registers.
 *
 * @return KMS_OK on success.
 */
int kms_derive_dk_ephemeral(void);

/**
 * Derive a Blinding Key (BK) from the current DK_ephemeral.
 *
 * BK_128 = SM4_enc(DK[0:16], metadata || nonce[0:8])
 * BK_256 = SM3(BK_128 || metadata) truncated to 32 bytes  (FIX #5: 256-bit BK)
 * Per-operation nonce ensures forward secrecy (FIX #6)
 *
 * @param metadata  16-byte metadata (salt || counter)
 * @param nonce     8-byte per-operation nonce for forward secrecy
 * @param bk_out    32-byte Blinding Key output (BK_256)
 * @return          KMS_OK on success
 */
int kms_derive_bk(const uint8_t metadata[KMS_METADATA_SIZE],
                  const uint8_t nonce[KMS_NONCE_EXT_SIZE],
                  uint8_t bk_out[KMS_BK_SIZE]);

/**
 * Blind input plaintext with BK (XOR).
 * Also derives BK' for output blinding.
 *
 * @param buf       Data buffer to blind (modified in place)
 * @param len       Buffer length in bytes
 * @param metadata  Metadata for BK derivation
 * @param nonce     Per-operation nonce
 * @param bk_prime  Output: BK' for later unblinding (caller must store with ciphertext)
 * @return          KMS_OK on success
 */
int kms_blind_input(uint8_t* buf, size_t len,
                    const uint8_t metadata[KMS_METADATA_SIZE],
                    const uint8_t nonce[KMS_NONCE_EXT_SIZE],
                    uint8_t bk_prime[KMS_BK_SIZE]);

/**
 * Unblind output ciphertext (reverse of kms_blind_input).
 *
 * BK' must match the one from the encryption side.
 * BK is re-derived from DK + metadata (deterministic with same nonce).
 *
 * @param buf       Ciphertext buffer to unblind (modified in place)
 * @param len       Buffer length
 * @param metadata  Same metadata used for encryption-side BK
 * @param nonce     Same per-operation nonce used for encryption-side BK
 * @param bk_prime  BK' from encryption output
 * @return          KMS_OK on success
 */
int kms_unblind_output(uint8_t* buf, size_t len,
                       const uint8_t metadata[KMS_METADATA_SIZE],
                       const uint8_t nonce[KMS_NONCE_EXT_SIZE],
                       const uint8_t bk_prime[KMS_BK_SIZE]);

/*
 * Rotate SK_bulk key (re-derive fresh DK + SK_bulk).
 * Called periodically or when get_state is invoked.
 * Uses g_sk_bulk_rotation_counter to trigger rotation.
 *
 * @return KMS_OK on success
 */
int kms_rotate_sk_bulk(void);

/**
 * Attest DK hash using Android KeyStore/StrongBox (TEE-backed).
 * Signs SHA-256(DK) to prove DK was generated on this legitimate device.
 *
 * NO software fallback — returns KMS_ERR_ATTEST without TEE.
 * AK is optional: local crypto works without it, remote attestation doesn't.
 *
 * @param dk_hash   32-byte SM3 hash of DK
 * @param sig_out   64-byte signature output (if available)
 * @return          KMS_OK on success, KMS_ERR_ATTEST if no TEE
 */
int kms_attest_dk(const uint8_t dk_hash[SM3_DIGEST_SIZE],
                  uint8_t sig_out[64]);

/**
 * Get DK lifetime in milliseconds since last derive.
 * For diagnostic / security monitoring.
 *
 * @return Milliseconds since last kms_derive_dk_ephemeral() call.
 */
uint64_t kms_get_dk_lifetime_ms(void);

/**
 * Wipe all NEON registers (q0-q7) with movi.
 * Does NOT write back to memory — pure register overwrite.
 * Called after every crypto operation completes.
 */
void kms_wipe_neon_keys(void);

/**
 * Generate a fresh salt for BK metadata.
 * Uses getrandom() syscall (Linux kernel entropy pool).
 *
 * @param salt_out  8-byte salt output
 */
void kms_generate_salt(uint8_t salt_out[KMS_SALT_SIZE]);

/**
 * Derive a Session Key at the given performance tier.
 *
 * @param tier   KMS_SK_TIER_BULK / SESSION / MSG / ROOT
 * @param sk_out 32-byte session key output
 * @return       KMS_OK on success
 */
int kms_derive_session_key_tiered(int tier, uint8_t sk_out[KMS_SK_SIZE]);

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_KMS_ENGINE_H */
