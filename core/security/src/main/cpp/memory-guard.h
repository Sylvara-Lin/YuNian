/*
 * memory-guard.h — YuNian Memory Protection Layer
 *
 * Protects sensitive memory regions from reading/dumping.
 * Designed for Android NDK with Linux kernel APIs.
 *
 * Features:
 *   - Guard pages (PROT_NONE surrounding sensitive allocations)
 *   - Stack canary verification
 *   - mprotect-based anti-read
 *   - /proc/self/maps polling for suspicious segments
 *   - ptrace self-attach (occupies the trace slot)
 *
 * Thread-safety: Not reentrant. Call from main thread only.
 */

#ifndef YUNIAN_MEMORY_GUARD_H
#define YUNIAN_MEMORY_GUARD_H

#include <cstdint>
#include <cstddef>

#ifdef __cplusplus
extern "C" {
#endif

/* ================================================================
 * Guard Pages
 * ================================================================ */

/**
 * Allocate a buffer with guard pages on BOTH sides.
 * On overflow/underflow, SIGSEGV is triggered immediately.
 *
 * size: requested usable buffer size (aligned to page boundary)
 * Returns: pointer to usable buffer (guard pages are before & after)
 *          NULL on failure
 */
void* mg_guarded_alloc(size_t size);

/**
 * Free a guarded allocation and unmap all pages.
 */
void mg_guarded_free(void* ptr, size_t size);

/* ================================================================
 * Stack Canary
 * ================================================================ */

/** Initialize the stack canary value. Call once at startup. */
void mg_stack_canary_init(void);

/**
 * Set a canary on the current stack frame.
 * Returns canary value. Must match at mg_stack_canary_check().
 */
uint64_t mg_stack_canary_set(void);

/**
 * Verify the stack canary hasn't been overwritten.
 * If mismatch, forces SIGABRT (unrecoverable).
 */
void mg_stack_canary_check(uint64_t expected);

/* ================================================================
 * Anti-Read / mprotect
 * ================================================================ */

/**
 * Protect a memory region from all access (PROT_NONE).
 * Use mg_unprotect() to temporarily re-enable access.
 *
 * @param ptr   Page-aligned pointer
 * @param size  Region size (rounded up to page boundary)
 * @return 0 on success, -1 on failure
 */
int mg_protect_read(void* ptr, size_t size);

/**
 * Restore read-write access to a protected region.
 * @return 0 on success, -1 on failure
 */
int mg_unprotect(void* ptr, size_t size);

/* ================================================================
 * /proc/self/maps Polling
 * ================================================================ */

/**
 * Check /proc/self/maps for suspicious memory segments.
 * Suspicious = executable+writable (W^X violation), anonymous
 * segments with odd permissions, or known bad library names.
 *
 * @return 0 if clean, 1 if suspicious segment found, -1 on error
 */
int mg_check_maps(void);

/* ================================================================
 * ptrace Self-Attach
 * ================================================================ */

/**
 * Self-attach via ptrace to occupy the debug slot.
 * Prevents external debuggers from attaching.
 *
 * On Android 14+, ptrace may be restricted — falls back to
 * prctl(PR_SET_DUMPABLE, 0).
 *
 * @return 0 on success, -1 if already traced or ptrace denied
 */
int mg_ptrace_self_attach(void);

/**
 * Detach self-ptrace (restore normal state).
 * Normally not called — stays attached for process lifetime.
 */
void mg_ptrace_self_detach(void);

#ifdef __cplusplus
}
#endif

#endif /* YUNIAN_MEMORY_GUARD_H */
