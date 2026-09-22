/*
 * integrity-guard.h — YuNian Secondary Integrity Verification
 *
 * Public API for the integrity verification layer.
 * Call ig_verify_all() once at startup (before DEX load).
 */

#ifndef YUNIAN_INTEGRITY_GUARD_H
#define YUNIAN_INTEGRITY_GUARD_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Run all integrity checks:
 *   1. SO .text section CRC32 self-check
 *   2. Shell DEX (classes.dex) CRC32 verification
 *
 * Any failure triggers SIGABRT — unrecoverable.
 *
 * @return 0 on success, -1 on abort (unreachable)
 */
int ig_verify_all(void);

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_INTEGRITY_GUARD_H */
