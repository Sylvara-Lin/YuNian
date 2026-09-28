#ifndef YUNIAN_WHITEBOX_AES_H
#define YUNIAN_WHITEBOX_AES_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

/* White-Box AES-256 (Chow-style, anti-DFA) — v2.0 Three-Tier Obfuscation
 *
 * L1 — Version Tables: CI regenerates wb_tables.inc each release.
 * L2 — Boot Obfuscation: XOR random masks on startup (non-deterministic).
 * L3 — Operation Randomization: per-round pos shuffle + fake accesses.
 *
 * AES key is embedded in pre-computed lookup tables.
 * The original key is NEVER present in the binary.
 * All operations are table lookups + XORs.
 * Anti-DFA: each round checksum is verified (with random salt).
 */

/* Size constants */
#define WB_AES_BLOCK_SIZE  16   /* 128-bit block */
#define WB_AES_256_ROUNDS  14

/* Init + lifecycle */
void wb_aes_init(void);
void wb_aes_wipe_keys(void);

/* Core encrypt/decrypt */
int wb_aes_256_encrypt(const uint8_t in[16], uint8_t out[16]);
int wb_aes_256_decrypt(const uint8_t in[16], uint8_t out[16]);

/* Persistent key derivation keeps integrity/lock checks but is not disabled by
 * a transient degraded risk score. */
int wb_aes_256_encrypt_persistent(const uint8_t in[16], uint8_t out[16]);

/* CBC mode */
int wb_aes_256_cbc_encrypt(const uint8_t* in, uint8_t* out, size_t len, uint8_t iv[16]);
int wb_aes_256_cbc_decrypt(const uint8_t* in, uint8_t* out, size_t len, uint8_t iv[16]);

/* Self-test: verify that E(D(x)) == x and D(E(x)) == x */
int wb_aes_256_selftest(void);

/* ================================================================
 * V2.0 Three-Tier Obfuscation
 * ================================================================ */

/**
 * L2 — Boot-time table obfuscation.
 * XORs a random mask onto each T-Box entry. Called once at app startup
 * (after wb_aes_init, before any encrypt/decrypt).
 *
 * Each L2 call produces different tables — BGE extraction must be
 * repeated for every process lifetime, while anti-debug is live.
 *
 * @param seed  32-byte random seed (e.g., from /dev/urandom)
 */
void wb_aes_obfuscate_tables(const uint8_t seed[32]);

/**
 * Side-channel defense: constant-time barriers + branch prediction disable.
 * Call once after wb_aes_obfuscate_tables() to enable timing-attack
 * defenses on all subsequent encrypt/decrypt operations.
 *
 * Implements:
 *   - Data memory barrier before/after each round
 *   - __builtin_unpredictable() on all table index lookups
 *   - Mandatory memory accesses (no early-return optimization)
 */
void wb_aes_side_channel_defense(void);

/**
 * L3 — Per-operation blinding.
 * Encrypts with randomized position order + fake T-Box accesses
 * + randomized checksum salt. Called internally by wb_aes_256_encrypt
 * when obfuscation is active.
 *
 * @return 0 on success, -1 on integrity failure.
 */
int wb_aes_256_encrypt_blinded(const uint8_t in[16], uint8_t out[16]);

/**
 * Check whether L2 obfuscation is active.
 * @return 1 if tables have been obfuscated, 0 otherwise.
 */
int wb_aes_is_obfuscated(void);

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_WHITEBOX_AES_H */
