/* obfuscate.h — YuNian native code obfuscation macros
 *
 * Provides compiler-independent obfuscation primitives that work with
 * any standard clang/LLVM (no OLLVM plugin required).
 *
 * Techniques:
 *   OBF_BOGUS_BRANCH   — inject never-taken branch (confuses CFG analysis)
 *   OBF_CONST_BLIND    — XOR constants at rest, de-XOR at runtime
 *   OBF_INDIRECT_CALL  — call via volatile function pointer (hides call target)
 *   OBF_JUNK_ASM       — inline junk instructions that disassemblers choke on
 *   OBF_DEAD_STORE     — write to dead variable (confuses data-flow analysis)
 *   OBF_NOINLINE       — force noinline for security-critical functions
 *
 * When OLLVM is available, add -mllvm -fla -mllvm -bcf -mllvm -sub in Android.mk.
 */

#ifndef YUNIAN_OBFUSCATE_H
#define YUNIAN_OBFUSCATE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ── Opaque predicates (always-true / always-false) ─────────────── */

static inline int obf_true(uint32_t x) {
    // (x | 1) * (x + 1) is always odd → bit 0 is always 1
    return ((x | 1u) * (x + 1u)) & 1u;
}

static inline int obf_false(uint32_t x) {
    // (x | 1) * (x + 3) is always odd → bit 0 is always 1
    // Comparing to 0 → always false
    return (((x | 1u) * (x + 3u)) & 1u) == 0u;
}

/* ── Compile-time constant blinding ─────────────────────────────── */
// XOR mask embedded in code as an opaque computation, de-XOR at runtime.
// Usage: OBF_BLIND(buf, size, seed) at function exit

#define OBF_BLIND_MIX(a,b) (((a) << 7) | ((b) >> 1))

static inline void obf_blind_bytes(uint8_t* buf, size_t len, uint32_t seed) {
    uint8_t k = (uint8_t)(seed ^ (seed >> 8) ^ (seed >> 16) ^ (seed >> 24));
    for (size_t i = 0; i < len; i++) {
        buf[i] ^= k;
        k = (uint8_t)(k * 173u + 37u);  // simple LCG for key stream
    }
}

/* ── Junk ASM injection ─────────────────────────────────────────── */
// Inserts instruction sequences that are valid but semantically dead.
// Confuses linear-sweep disassemblers (objdump, IDA linear mode).

#if defined(__x86_64__) || defined(__i386__)
#define OBF_JUNK_ASM() \
    __asm__ __volatile__("xor %%eax,%%eax; test %%eax,%%eax; jz 1f; .byte 0xEB,0xFF; 1:" ::: "eax", "memory")
#elif defined(__aarch64__)
#define OBF_JUNK_ASM() \
    __asm__ __volatile__("eor w0, w0, w0; cbz w0, 1f; .inst 0x14000000; 1:" ::: "x0", "memory")
#elif defined(__arm__)
#define OBF_JUNK_ASM() \
    __asm__ __volatile__("eor r0, r0, r0; cmp r0, #0; beq 1f; .inst 0xEA000000; 1:" ::: "r0", "memory")
#else
#define OBF_JUNK_ASM() ((void)0)
#endif

/* ── Bogus branch injection ─────────────────────────────────────── */
// Injects a conditional branch that is always NOT taken, but the compiler
// and disassembler must conservatively assume it might be taken.
// The "target" is a junk instruction sequence.

#define OBF_BOGUS_BRANCH(seed) \
    do { \
        OBF_JUNK_ASM(); \
        if (obf_false((uint32_t)(seed))) { \
            volatile int _obf_never = 0xDEAD; \
            _obf_never = _obf_never * 0x41C64E6D + 12345; \
            (void)_obf_never; \
        } \
        OBF_JUNK_ASM(); \
    } while(0)

/* ── Indirect call ───────────────────────────────────────────────── */
// Hides the actual call target from static analysis.
// Usage: OBF_INDIRECT_CALL(func, (arg1, arg2))

#define OBF_INDIRECT_CALL_TYPED(ret, func, args) \
    do { \
        ret (*volatile _obf_fp) = (ret (*))func; \
        OBF_JUNK_ASM(); \
        _obf_fp args; \
        _obf_fp = NULL; \
    } while(0)

#define OBF_INDIRECT_CALL(func, args) \
    OBF_INDIRECT_CALL_TYPED(void, func, args)

/* ── Dead store injection ────────────────────────────────────────── */
// Writes to a volatile variable that is never read, confusing
// data-flow analysis about liveness of registers.

#define OBF_DEAD_STORE() \
    do { \
        volatile uint32_t _obf_ds = 0xCAFEBABE; \
        (void)_obf_ds; \
        _obf_ds ^= (uint32_t)(uintptr_t)&_obf_ds; \
    } while(0)

/* ── Force noinline ──────────────────────────────────────────────── */
#define OBF_NOINLINE __attribute__((noinline))

/* ── Combined obfuscation barrier ───────────────────────────────── */
// Place at function entry and before returns to confuse CFG and data-flow.

#define OBF_BARRIER(seed) \
    do { \
        OBF_JUNK_ASM(); \
        OBF_BOGUS_BRANCH(seed); \
        OBF_DEAD_STORE(); \
    } while(0)

#ifdef __cplusplus
}
#endif

#endif // YUNIAN_OBFUSCATE_H
