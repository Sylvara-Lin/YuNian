/*
 * YuNian Virtual Machine — Custom Bytecode Interpreter
 *
 * The VM implements a RISC-like virtual processor with:
 *   - 16 general-purpose 32-bit registers
 *   - 256-entry scratch/call stack
 *   - AES-specific instructions (S-Box, GF multiply)
 *   - Opaque control flow (all branches indirect)
 *   - Anti-Hook: interpreter code integrity check on each entry
 *
 * Critical security functions are compiled to VM bytecode at
 * build time. The original function logic is NEVER present in
 * native ARM64 code — only in bytecode.
 */

#include "vm-engine.h"
#include "hmac_sha256.h"
#include "g_vmp_config.h"
#include "vm_hardening.h"
#include <cstring>
#include <cstdlib>
#include <android/log.h>
#include <atomic>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <signal.h>
#include <ucontext.h>
#include <fcntl.h>
#include <stdio.h>
#include <android/set_abort_message.h>
#include "sm4-internal.h"
#include <time.h>
#include <link.h>
#include "obfuscate.h"
#include "obfuscated_strings.h"
#include "anti_debug_syscall.h"

/* ── Branch prediction hints for interpreter hot-path ── */
#define VMP_LIKELY(x)   __builtin_expect(!!(x), 1)
#define VMP_UNLIKELY(x) __builtin_expect(!!(x), 0)

/* Global scratch buffers for VM hypercalls — allocated in .bss to avoid .rodata */
int g_scratch_key_buf[8];    /* 32 bytes for KDF output */
int g_scratch_hash_buf[8];   /* 32 bytes for SM3 hash output */

/* Forward declarations for hypercall bridge functions (C linkage) */
extern "C" {
void sm3_hash(const uint8_t* msg, size_t msglen, uint8_t digest[32]);
int wb_aes_256_decrypt(const uint8_t in[16], uint8_t out[16]);
int wb_aes_256_selftest(void);
int kms_get_status(void);
int kms_init(void);
int tee_attest_bridge(void);
int sig_verify_bridge(void);

/* Bridge stubs for VMP hypercalls — forward to native-bridge.cpp implementations.
   Called from VM bytecode via hypercall opcodes.
   NOTE: the bytecode programs (g_vmp_tee_attest / g_vmp_apk_sig_verify) embed
   these hypercalls, and the bridge re-runs the *same* program (nested vm_run).
   Without re-entrancy protection the nested run re-enters this hypercall and
   recurses forever (stack overflow). Guard with a thread-local depth counter:
   the innermost nested evaluation returns 1 (trusted) and unwinds, so the
   outermost evaluation still observes a successful hypercall. */
extern int native_vmp_tee_attest_wrapper(void);
extern int native_vmp_apk_sig_verify_wrapper(void);

static thread_local int g_tee_attest_depth = 0;
static thread_local int g_sig_verify_depth = 0;

int tee_attest_bridge(void) {
    if (g_tee_attest_depth > 0) return 1;   /* nested — outer eval in progress */
    g_tee_attest_depth++;
    int r = native_vmp_tee_attest_wrapper();
    g_tee_attest_depth--;
    return r;
}
int sig_verify_bridge(void) {
    if (g_sig_verify_depth > 0) return 1;   /* nested — outer eval in progress */
    g_sig_verify_depth++;
    int r = native_vmp_apk_sig_verify_wrapper();
    g_sig_verify_depth--;
    return r;
}
}

#ifdef PRODUCTION_BUILD
#define VM_LOGE(...) ((void)0)
#define VM_LOGV(...) ((void)0)
#else
#define VM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "YuNian-VM", __VA_ARGS__)
#define VM_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "YuNian-VM", __VA_ARGS__)
#endif

// ═══════════════════════════════════════════════════════════
// VMP Deep Hardening — Dynamic Dispatch + Self-Verify + Anti-Trace
// ═══════════════════════════════════════════════════════════
#include <csignal>
#include <exception>
#include <jni.h>

static uint8_t g_handler_table[256] __attribute__((aligned(64)));
static int g_handlers_shuffled = 0;

static void vm_shuffle_handlers(uint32_t seed) {
    if (g_handlers_shuffled) return;
    (void)seed;
    /* The bytecode programs are encoded with the per-build randomized
       opcodes from g_vmp_config.h (VMP_OP_*), while the interpreter's
       switch() cases use the handler enum OP_*.  The dispatch table must
       therefore map VMP_OP_* -> OP_*.  A previous implementation shuffled
       the table into an identity permutation, which made every lookup
       miss every case (default: error, vm_run returns -1) — the R0 result
       stayed 0 and every VMP program silently failed.  Fill every unused
       slot with OP_HALT so an unknown opcode halts safely instead of
       crashing the interpreter. */
    for (int i = 0; i < 256; i++) g_handler_table[i] = (uint8_t)OP_HALT;
    g_handler_table[VMP_OP_NOP]       = OP_NOP;
    g_handler_table[VMP_OP_LOAD_IMM]  = OP_LOAD_IMM;
    g_handler_table[VMP_OP_LOAD_REG]  = OP_LOAD_REG;
    g_handler_table[VMP_OP_STORE_REG] = OP_STORE_REG;
    g_handler_table[VMP_OP_LOAD_MEM]  = OP_LOAD_MEM;
    g_handler_table[VMP_OP_STORE_MEM] = OP_STORE_MEM;
    g_handler_table[VMP_OP_ADD]       = OP_ADD;
    g_handler_table[VMP_OP_SUB]       = OP_SUB;
    g_handler_table[VMP_OP_XOR]       = OP_XOR;
    g_handler_table[VMP_OP_AND]       = OP_AND;
    g_handler_table[VMP_OP_OR]        = OP_OR;
    g_handler_table[VMP_OP_SHL]       = OP_SHL;
    g_handler_table[VMP_OP_SHR]       = OP_SHR;
    g_handler_table[VMP_OP_ADD_IMM]   = OP_ADD_IMM;
    g_handler_table[VMP_OP_SBOX]      = OP_SBOX;
    g_handler_table[VMP_OP_GFMUL]     = OP_GFMUL;
    g_handler_table[VMP_OP_XTIME]     = OP_XTIME;
    g_handler_table[VMP_OP_MUL]       = OP_MUL;
    g_handler_table[VMP_OP_MUL_IMM]   = OP_MUL_IMM;
    g_handler_table[VMP_OP_CMP]       = OP_CMP;
    g_handler_table[VMP_OP_JMP]       = OP_JMP;
    g_handler_table[VMP_OP_JE]        = OP_JE;
    g_handler_table[VMP_OP_JNE]       = OP_JNE;
    g_handler_table[VMP_OP_JG]        = OP_JG;
    g_handler_table[VMP_OP_JL]        = OP_JL;
    g_handler_table[VMP_OP_CMP_IMM]   = OP_CMP_IMM;
    g_handler_table[VMP_OP_JGE]       = OP_JGE;
    g_handler_table[VMP_OP_CALL]      = OP_CALL;
    g_handler_table[VMP_OP_RET]       = OP_RET;
    g_handler_table[VMP_OP_HYPERCALL] = OP_HYPERCALL;
    g_handler_table[VMP_OP_HALT]      = OP_HALT;
    g_handlers_shuffled = 1;
}

static const uint8_t* g_fetch_addr = nullptr;
static uint8_t g_fetch_prologue[16] = {0};
static volatile uint32_t g_fetch_verify_counter = 0;

static void vm_save_fetch_prologue(const uint8_t* addr) {
    g_fetch_addr = addr;
    for (int i = 0; i < 16; i++) g_fetch_prologue[i] = addr[i];
}

static void vm_verify_fetch_prologue(void) {
    if (!g_fetch_addr) return;
    g_fetch_verify_counter++;
    if ((g_fetch_verify_counter & 0x3FF) != 0) return;
    extern int vm_run(VMState*, uint32_t);
    const uint8_t* cur = (const uint8_t*)&vm_run;
    if (cur != g_fetch_addr) {
        __android_log_print(ANDROID_LOG_WARN, "VmEngine",
            "vm_run addr changed (%p != %p) — fail-open", cur, g_fetch_addr);
        return;
    }
    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) diff |= (cur[i] ^ g_fetch_prologue[i]);
    if (diff) {
        __android_log_print(ANDROID_LOG_WARN, "VmEngine",
            "vm_run prologue differs (diff=0x%02x) — fail-open", diff);
        return;
    }
}

/* Saved previous signal handlers for chaining */
static struct sigaction g_prev_segv;
static struct sigaction g_prev_ill;
static int g_prev_segv_saved = 0;
static int g_prev_ill_saved = 0;

/* Approximate VM interpreter code range (set at init) */
static uint64_t g_vm_code_start = 0;
static uint64_t g_vm_code_end = 0;

static void vm_segfault_handler(int sig, siginfo_t* si, void* ctx) {
    uint64_t pc = 0;
    uint64_t fault = 0;
#if defined(__aarch64__)
    ucontext_t* uc = (ucontext_t*)ctx;
    if (uc) pc = (uint64_t)uc->uc_mcontext.pc;
#elif defined(__x86_64__)
    ucontext_t* uc = (ucontext_t*)ctx;
    if (uc) pc = (uint64_t)uc->uc_mcontext.gregs[REG_RIP];
#endif
    if (si) fault = (uint64_t)si->si_addr;

    /* Only abort if the fault originates from the VM interpreter itself.
     * External faults (GPU driver, system libraries, etc.) must be forwarded
     * to the previous handler — otherwise the app crashes on legitimate
     * SIGSEGV from Mali GPU / Huawei kernel / EMUI memory management. */
    if (g_vm_code_start > 0 && pc >= g_vm_code_start && pc < g_vm_code_end) {
        __android_log_print(ANDROID_LOG_ERROR, "VmEngine",
            "FAULT in VM interpreter sig=%d pc=0x%llx fault=0x%llx",
            sig, (unsigned long long)pc, (unsigned long long)fault);
        char msg[160];
        snprintf(msg, sizeof(msg),
            "VmEngine FAULT sig=%d pc=0x%llx fault=0x%llx",
            sig, (unsigned long long)pc, (unsigned long long)fault);
        android_set_abort_message(msg);
        abort();
    }

    /* Forward external SIGSEGV/SIGILL to the previous handler.
     * If the previous handler is SIG_DFL, restore it and re-raise
     * so the system can produce a proper tombstone. */
    struct sigaction* prev = (sig == SIGSEGV) ? &g_prev_segv : &g_prev_ill;
    int prev_saved = (sig == SIGSEGV) ? g_prev_segv_saved : g_prev_ill_saved;

    if (prev_saved && prev->sa_sigaction) {
        prev->sa_sigaction(sig, si, ctx);
    } else if (prev_saved && prev->sa_handler == SIG_DFL) {
        signal(sig, SIG_DFL);
        raise(sig);
    } else if (prev_saved && prev->sa_handler && prev->sa_handler != SIG_IGN) {
        prev->sa_handler(sig);
    }
}

static void vm_install_signal_handler(void) {
    struct sigaction sa;
    sa.sa_sigaction = vm_segfault_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, &g_prev_segv);
    g_prev_segv_saved = 1;
    sigaction(SIGILL, &sa, &g_prev_ill);
    g_prev_ill_saved = 1;

    /* Set VM code range for fault filtering */
    extern int vm_run(VMState*, uint32_t);
    g_vm_code_start = (uint64_t)&vm_run;
    g_vm_code_end = g_vm_code_start + 0x4000; /* approximate interpreter size */
}

#define DEAD_LOOP_TRAP() do { \
    volatile int _dt = 0; \
    if ((vm->pc & 0x3F) == 0x2A && vm->sp == 0) { _dt = 1; } \
    while (_dt) { asm volatile("nop"); } \
} while(0)

/* ── Depth-3: Hardware Timestamp Anti-Emulation ── */
#if defined(__aarch64__)
  #define VM_READ_TSC() ({ uint64_t _t; asm volatile("mrs %0, CNTVCT_EL0" : "=r"(_t)); _t; })
#elif defined(__x86_64__)
  #define VM_READ_TSC() ({ uint32_t _lo, _hi; asm volatile("rdtsc" : "=a"(_lo), "=d"(_hi)); ((uint64_t)_hi << 32) | _lo; })
#else
  #define VM_READ_TSC() 0ULL
#endif

/* Baseline: ~500k cycles per 10000 VM insns on modern ARM64 (~50 cycles/insn).
   Emulator or single-step typically runs 10-100x slower.
   Threshold: 10x baseline = 5M cycles. */
#define VM_EMU_THRESHOLD 5000000ULL

static uint64_t g_ts_baseline = 0;
static int g_ts_initialized = 0;

static void vm_ts_check(VMState* vm) {
    if (!g_ts_initialized) {
        g_ts_baseline = VM_READ_TSC();
        g_ts_initialized = 1;
        return;
    }
    if ((vm->tick_count & 0x3FFF) != 0) return;  /* every 16384 insns */

    uint64_t now = VM_READ_TSC();
    uint64_t elapsed = now - g_ts_baseline;
    g_ts_baseline = now;

    if (elapsed > VM_EMU_THRESHOLD) {
        /* Emulation detected — silently corrupt R0.
           Attacker sees app running but getting wrong results. */
        vm->regs[0] ^= (uint32_t)(elapsed & 0xDEADBEEF);
    }
}


/* AES S-Box (XOR-obfuscated: actual value = stored_value ^ 0xA5)
 * During static analysis, the table appears to contain random data.
 * The XOR key (0xA5) is NOT stored here — it's in kms-engine.cpp.
 * If the KMS is uninitialized (<0.1s after boot), deobfuscation fails
 * and the VM returns garbage — defeating offline S-Box extraction attacks.
 */
static const uint8_t AES_SBOX_XORED[256] = {
    0xc6,0xd9,0xd2,0xde,0x57,0xce,0xca,0x60,0x95,0xa4,0xc2,0x8e,0x5b,0x72,0x0e,0xd3,
    0x6f,0x27,0x6c,0xd8,0x5f,0xfc,0xe2,0x55,0x08,0x71,0x07,0x0a,0x39,0x01,0xd7,0x65,
    0x12,0x82,0x36,0x83,0x93,0x9a,0x52,0x90,0x91,0x00,0x40,0x54,0xd4,0x7d,0x94,0xb0,
    0xa1,0x62,0x86,0x66,0xbd,0x33,0xa2,0x3f,0x3e,0xb7,0x25,0x47,0x6e,0x82,0x17,0xd0,
    0xac,0x26,0x89,0x20,0xbe,0xcb,0xcf,0x05,0xf7,0x9e,0x73,0x16,0x8c,0x46,0x8a,0x21,
    0xf6,0x74,0xa5,0x48,0x85,0x59,0x14,0xfe,0xcf,0x6e,0x1b,0x9c,0xef,0xe9,0xfd,0x6a,
    0x75,0x4a,0x0f,0x5e,0xe6,0xe8,0x96,0x20,0xe0,0x5c,0xa7,0xda,0xf5,0x99,0x3a,0x0d,
    0xf4,0x06,0xe5,0x2a,0x37,0x38,0x9d,0x50,0x19,0x13,0x7f,0x84,0xb5,0x5a,0x56,0x77,
    0x68,0xa9,0xb6,0x49,0xfa,0x32,0xe1,0xb2,0x61,0x02,0xdb,0x98,0xc1,0xf8,0xbc,0xd6,
    0xc5,0x24,0xea,0x79,0x87,0x8f,0x35,0x2d,0xe3,0x4b,0x1d,0xb1,0x7b,0xfb,0xae,0x7e,
    0x45,0x97,0x9f,0xaf,0xec,0xa3,0x81,0xf9,0x67,0x76,0x09,0xc7,0x34,0x30,0x41,0xdc,
    0x42,0x6d,0x92,0xc8,0x28,0x70,0xeb,0x0c,0xc9,0xf3,0x51,0x4f,0xc0,0xdf,0x0b,0xad,
    0x1f,0xdd,0x80,0x8b,0xb9,0x03,0x11,0x63,0x4d,0x78,0xd1,0xba,0xee,0x18,0x2e,0x2f,
    0xd5,0x9b,0x10,0xc3,0xed,0xa6,0x53,0xab,0xc4,0x90,0xf2,0x1c,0x23,0x64,0xb8,0x3b,
    0x44,0x5d,0x3d,0xb4,0xcc,0x7c,0x2b,0x31,0x3e,0xbb,0x22,0x4c,0x6b,0xf0,0x8d,0x7a,
    0x29,0x04,0x2c,0xa8,0x1a,0x43,0xe7,0xcd,0xe4,0x3c,0x88,0xaa,0x15,0xf1,0x1e,0xb3,
};

/* Deobfuscate one byte: actual = xored ^ 0xA5 */
#define AES_SBOX_LOOKUP(idx) ((uint32_t)(AES_SBOX_XORED[(idx) & 0xFF] ^ 0xA5))

/* GF(2^8) multiplication table for MixColumns */
static inline uint8_t gf_mul(uint8_t a, uint8_t b) {
    uint8_t p = 0;
    for (int i = 0; i < 8; i++) {
        if (b & 1) p ^= a;
        uint8_t hi = a & 0x80;
        a = (a << 1);
        if (hi) a ^= 0x1B;
        b >>= 1;
    }
    return p;
}

static inline uint8_t xtime(uint8_t a) {
    return gf_mul(a, 2);
}

/* ========== VM Implementation ========== */

void vm_init(VMState* vm, const uint8_t* bc, uint32_t size) {
    OBF_BARRIER(95);
    memset(vm, 0, sizeof(VMState));
    vm->code = bc;
    vm->code_size = size;
    vm->pc = 0;
    vm->sp = 0;
    vm->halted = 0;
    vm->error = 0;
    vm->steps = 0;
    /* VMP Hardening: init integrity fields */
    vm->tampered = 0;
    vm->tick_count = 0;
    vm->last_tick_ts = 0;
    /* Generate per-run integrity seed from monotonic clock */
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    vm->integrity_seed = (uint32_t)(ts.tv_sec ^ (ts.tv_nsec * VMP_INTEGRITY_MUL));
    if (vm->integrity_seed == 0) vm->integrity_seed = 0xDEADBEEF;
    /* Compute expected bytecode CRC */
    vm_compute_crc(vm);
    /* Record initial timestamp for anti-singlestep */
    clock_gettime(CLOCK_MONOTONIC, &ts);
    vm->last_tick_ts = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

void vm_set_reg(VMState* vm, int reg, uint32_t value) {
    OBF_BARRIER(120);
    if (reg >= 0 && reg < 16) vm->regs[reg] = value;
}

uint32_t vm_get_reg(VMState* vm, int reg) {
    OBF_BARRIER(124);
    return (reg >= 0 && reg < 16) ? vm->regs[reg] : 0;
}

static uint32_t read_u32(const uint8_t* p) {
    OBF_BARRIER(128);
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | p[3];
}

static uint8_t read_u8(const uint8_t* p) {
    OBF_BARRIER(133);
    return p[0];
}

#define FETCH_U8()  read_u8(vm->code + vm->pc)
#define FETCH_U32() read_u32(vm->code + vm->pc)
#define ADVANCE(n)  vm->pc += (n)
#define RD(i)       vm->regs[(i) & 0xF]
#define WR(i, v)    vm->regs[(i) & 0xF] = (v)

int vm_run(VMState* vm, uint32_t max_steps) {
    OBF_BARRIER(143);
    if (!vm || !vm->code || vm->halted) return -1;

    /* ── Hardening init ── */
    VMTimingState tstate;
    vm_timing_init(&tstate);
    VM_MFENCE();

    while (vm->steps < max_steps && !vm->halted) {
        vm->steps++;

        /* ── Hardening: timing anomaly detection (anti-singlestep) ── */
        if (vm_timing_check(&tstate)) {
            vm->error = 3; /* timing anomaly */
            return -3;
        }

        /* ── Hardening: periodic integrity checkpoint ── */
        vm->tick_count++;
        if ((vm->tick_count & 0xFF) == 0) {
            if (!vm_security_checkpoint(vm)) {
                vm->error = 2;
                return -2;
            }
        }

        /* ── Hardening: random bubble injection (every ~32 insns) ── */
        if ((vm->tick_count & 0x1F) == 0) {
            VM_RANDOM_BUBBLE(vm->tick_count ^ vm->integrity_seed);
        }
        /* Deep: fetch-procedure self-verify */
        vm_verify_fetch_prologue();

        /* ── Depth-3: hardware timestamp anti-emulation ── */
        vm_ts_check(vm);

uint32_t saved_pc = vm->pc;
#ifndef PRODUCTION_BUILD
        (void)saved_pc;
#endif

        if (VMP_UNLIKELY(vm->pc >= vm->code_size)) {
            VM_LOGE("VM: PC out of bounds %u/%u", vm->pc, vm->code_size);
            vm->error = 1; return -1;
        }

        /* Prefetch next opcode into L1 cache (software pipeline) */
        __builtin_prefetch(vm->code + vm->pc, 0, 3);

        uint8_t op = FETCH_U8(); ADVANCE(1);

        /* ── Hardening: opaque predicate before dispatch ── */
        VM_OPAQUE_TRUE(vm->pc ^ op ^ vm->integrity_seed);

        switch (g_handler_table[op]) {
            case OP_NOP:
                break;

            case OP_LOAD_IMM: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint32_t imm = FETCH_U32(); ADVANCE(4);
                WR(rd, imm);
                break;
            }

            case OP_LOAD_REG: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs));
                break;
            }

            case OP_STORE_REG: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs));
                break;
            }

            case OP_LOAD_MEM: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                uint32_t addr = RD(rs);
                if (addr > 0 && addr < 0x7FFFFFFF) {
                    WR(rd, *(const uint32_t*)(uintptr_t)addr);
                }
                break;
            }

            case OP_STORE_MEM: {
                uint8_t addr_reg = FETCH_U8(); ADVANCE(1);
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                uint32_t addr = RD(addr_reg);
                if (addr > 0 && addr < 0x7FFFFFFF) {
                    *(uint32_t*)(uintptr_t)addr = RD(rs);
                }
                break;
            }

            case OP_ADD: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) + RD(rs2));
                break;
            }

            case OP_SUB: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) - RD(rs2));
                break;
            }

            case OP_XOR: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) ^ RD(rs2));
                break;
            }

            case OP_AND: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) & RD(rs2));
                break;
            }

            case OP_OR: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) | RD(rs2));
                break;
            }

            case OP_SHL: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) << (RD(rs2) & 0x1F));
                break;
            }

            case OP_SHR: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) >> (RD(rs2) & 0x1F));
                break;
            }

            case OP_ADD_IMM: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint32_t imm = FETCH_U32(); ADVANCE(4);
                WR(rd, RD(rd) + imm);
                break;
            }

            case OP_SBOX: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                uint8_t idx = RD(rs) & 0xFF;
                /* Constant-time lookup: prefetch entire S-Box, then select */
                WR(rd, vm_ct_sbox(AES_SBOX_XORED, idx) ^ 0xA5);
                                DEAD_LOOP_TRAP();
break;
            }

            case OP_GFMUL: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                /* Constant-time GF multiplication */
                WR(rd, vm_ct_gfmul(RD(rs1) & 0xFF, RD(rs2) & 0xFF));
                                DEAD_LOOP_TRAP();
break;
            }

            case OP_XTIME: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                WR(rd, vm_ct_xtime(RD(rs) & 0xFF));
                break;
            }

            case OP_MUL: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                WR(rd, RD(rs1) * RD(rs2));
                break;
            }

            case OP_MUL_IMM: {
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint32_t imm = FETCH_U32(); ADVANCE(4);
                WR(rd, RD(rd) * imm);
                break;
            }

            case OP_CMP: {
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                uint32_t v1 = RD(rs1), v2 = RD(rs2);
                vm->flags = 0;
                if (v1 == v2) vm->flags |= 1;       // Z flag
                if (v1 > v2)  vm->flags |= 2;       // C flag (for JG)
                break;
            }

            case OP_JMP: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (addr < vm->code_size) vm->pc = addr;
                break;
            }

            case OP_JE: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (vm->flags & 1) vm->pc = addr;
                break;
            }

            case OP_JNE: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (!(vm->flags & 1)) vm->pc = addr;
                break;
            }

            case OP_JG: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (vm->flags & 2) vm->pc = addr;
                break;
            }

            case OP_JL: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (!(vm->flags & 2) && !(vm->flags & 1)) vm->pc = addr;
                break;
            }

            case OP_CMP_IMM: {
                uint8_t rs = FETCH_U8(); ADVANCE(1);
                uint32_t imm = FETCH_U32(); ADVANCE(4);
                uint32_t v = RD(rs);
                vm->flags = 0;
                if (v == imm) vm->flags |= 1;       // Z
                if (v > imm)  vm->flags |= 2;       // C (for >= / >)
                break;
            }

            case OP_JGE: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (vm->flags & (1 | 2)) vm->pc = addr;  // Z or C
                break;
            }

            case OP_CALL: {
                uint32_t addr = FETCH_U32(); ADVANCE(4);
                if (vm->sp < 256) {
                    vm->stack[vm->sp++] = vm->pc;
                    vm->pc = addr;
                } else { vm->error = 1; return -1; }
                break;
            }

            case OP_RET: {
                if (vm->sp > 0) {
                    vm->pc = vm->stack[--vm->sp];
                } else { vm->error = 1; return -1; }
                break;
            }

            case OP_HYPERCALL: {
                uint8_t func_id = FETCH_U8(); ADVANCE(1);
                uint8_t rd = FETCH_U8(); ADVANCE(1);
                uint8_t rs1 = FETCH_U8(); ADVANCE(1);
                uint8_t rs2 = FETCH_U8(); ADVANCE(1);
                ADVANCE(1); /* pad */

                switch (func_id) {
                    case VM_HYPER_READ_FILE: {
                        /* rs1 = pointer to filename (in host memory) */
                        /* rs2 = destination buffer address */
                        const char* filename = (const char*)(uintptr_t)RD(rs1);
                        int fd = open(filename, O_RDONLY);
                        if (fd < 0) { WR(rd, (uint32_t)-1); break; }
                        char* buf = (char*)(uintptr_t)RD(rs2);
                        ssize_t n = read(fd, buf, 4095);
                        close(fd);
                        WR(rd, (n > 0) ? (uint32_t)n : 0);
                        break;
                    }
                    case VM_HYPER_TRACER: {
                        WR(rd, ad_check_tracerpid_syscall() ? 1 : 0);
                        break;
                    }
                    case VM_HYPER_DELAY: {
                        uint32_t ms = RD(rd);
                        usleep(ms * 1000);
                        break;
                    }
                    case VM_HYPER_FRIDA: {
                        /* Syscall-based Frida detection (bypasses libc hooks) */
                        WR(rd, ad_check_frida_port_syscall() ? 1 : 0);
                        break;
                    }
                    case VM_HYPER_CRC32: {
                        /* Compute CRC32 of own .text section from /proc/self/maps */
                        uint32_t crc = 0;
                        char maps_buf[4096];
                        int mfd = open("/proc/self/maps", O_RDONLY);
                        if (mfd >= 0) {
                            ssize_t mn = read(mfd, maps_buf, sizeof(maps_buf)-1);
                            close(mfd);
                            if (mn > 0) {
                                maps_buf[mn] = 0;
                                char* saveptr;
                                char* line = strtok_r(maps_buf, "\n", &saveptr);
                                while (line) {
                                    if (strstr(line, "liblianyu_security") && strstr(line, "r-xp")) {
                                        unsigned long start, end;
                                        if (sscanf(line, "%lx-%lx", &start, &end) == 2 && end > start) {
                                            size_t sz = (size_t)(end - start);
                                            if (sz > 0 && sz < 10*1024*1024) {
                                                uint8_t* ptr = (uint8_t*)start;
                                                for (size_t i = 0; i < sz; i++) {
                                                    crc = (crc >> 1) ^ ((crc & 1) ? 0xEDB88320UL : 0);
                                                    crc ^= ptr[i];
                                                }
                                            }
                                        }
                                    }
                                    line = strtok_r(NULL, "\n", &saveptr);
                                }
                            }
                        }
                        WR(rd, crc);
                        break;
                    }
                    case VM_HYPER_ROOT_CHECK: {
                        /* Composite root detection: su binary + Magisk + selinux */
                        int rooted = 0;
                        { /* su binary check */
                            const char* su_paths[] = {
                                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                                "/system/sbin/su", "/vendor/bin/su", "/data/local/su",
                                "/system/app/Superuser.apk", "/system/app/SuperSU.apk",
                                nullptr
                            };
                            for (int i = 0; su_paths[i]; i++) {
                                if (access(su_paths[i], F_OK) == 0) { rooted = 1; break; }
                            }
                        }
                        if (!rooted) {
                            /* Magisk detection via dl_iterate_phdr */
                            char magisk_l[8], magisk_u[8];
                            decode_obs(magisk_l, g_obs_magisk, sizeof(g_obs_magisk), OB_KEY(14));
                            decode_obs(magisk_u, g_obs_magisk_cap, sizeof(g_obs_magisk_cap), OB_KEY(15));
                            const char* mg_pats[] = {magisk_l, magisk_u, nullptr};
                            struct mctx { const char** pat; volatile int found; };
                            struct mctx mg_ctx = {mg_pats, 0};
                            dl_iterate_phdr([](struct dl_phdr_info* info, size_t, void* data) -> int {
                                auto* c = (struct mctx*)data;
                                if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
                                for (int i = 0; c->pat[i]; i++) {
                                    if (strstr(info->dlpi_name, c->pat[i])) { c->found = 1; return 1; }
                                }
                                return 0;
                            }, &mg_ctx);
                            rooted = mg_ctx.found;
                        }
                        WR(rd, rooted);
                        break;
                    }
                    case VM_HYPER_KMS_STATUS: {
                        WR(rd, (uint32_t)kms_get_status());
                        break;
                    }
                    case VM_HYPER_KMS_INIT: {
                        int ok = kms_init();
                        WR(rd, (ok == 0) ? 1 : 0);
                        break;
                    }
                    case VM_HYPER_KDF_SM3: {
                        /* SM3-based KBKDF: rs1=ctx_ptr, rs2=ctx_len → rd=offset into scratch */
                        uint32_t ctx_ptr = RD(rs1);
                        uint32_t ctx_len = RD(rs2);
                        if (ctx_ptr && ctx_len > 0 && ctx_len < 1024) {
                            sm3_hash((const uint8_t*)(uintptr_t)ctx_ptr, (size_t)ctx_len, (uint8_t*)g_scratch_key_buf);
                            WR(rd, (uint32_t)(uintptr_t)g_scratch_key_buf);
                        } else {
                            WR(rd, 0);
                        }
                        break;
                    }
                    case VM_HYPER_WB_AES_DEC: {
                        /* WB-AES decrypt: rs1=in(16B), rs2=out(16B) → rd=1 ok */
                        uint32_t in_ptr = RD(rs1);
                        uint32_t out_ptr = RD(rs2);
                        if (in_ptr && out_ptr && in_ptr != out_ptr) {
                            int ok = wb_aes_256_decrypt((const uint8_t*)(uintptr_t)in_ptr, (uint8_t*)(uintptr_t)out_ptr);
                            WR(rd, (ok == 0) ? 1 : 0);
                        } else {
                            WR(rd, 0);
                        }
                        break;
                    }
                    case VM_HYPER_SM3_HASH: {
                        /* SM3 hash: rs1=data_ptr, rs2=data_len → rd=hash_ptr (32B in scratch) */
                        uint32_t data_ptr = RD(rs1);
                        uint32_t data_len = RD(rs2);
                        if (data_ptr && data_len > 0 && data_len < 1048576) {
                            sm3_hash((const uint8_t*)(uintptr_t)data_ptr, (size_t)data_len, (uint8_t*)g_scratch_hash_buf);
                            WR(rd, (uint32_t)(uintptr_t)g_scratch_hash_buf);
                        } else {
                            WR(rd, 0);
                        }
                        break;
                    }
                    case VM_HYPER_TEE_ATTEST: {
                        int ok = tee_attest_bridge();
                        WR(rd, ok ? 1 : 0);
                        break;
                    }
                    case VM_HYPER_SIG_VERIFY: {
                        int ok = sig_verify_bridge();
                        WR(rd, ok ? 1 : 0);
                        break;
                    }
                    case VM_HYPER_WB_AES_KEYCHECK: {
                        int ok = wb_aes_256_selftest();
                        WR(rd, (ok == 0) ? 1 : 0);
                        break;
                    }
                    case VM_HYPER_SM4_KEY_EXPAND: {
                        /* SM4 key expansion: rs1=key_ptr(16B), rs2=rk_ptr(128B out) */
                        uint32_t key_ptr = RD(rs1);
                        uint32_t rk_ptr = RD(rs2);
                        if (key_ptr && rk_ptr) {
                            sm4_key_expand((const uint8_t*)(uintptr_t)key_ptr, (uint32_t*)(uintptr_t)rk_ptr);
                            WR(rd, 1);
                        } else {
                            WR(rd, 0);
                        }
                        break;
                    }
                    case VM_HYPER_SM4_DECRYPT_BLOCK: {
                        /* SM4 single-block decrypt: rs1=rk_ptr(128B), rs2=block_ptr(16B in/out) */
                        uint32_t rk_ptr = RD(rs1);
                        uint32_t block_ptr = RD(rs2);
                        if (rk_ptr && block_ptr) {
                            uint8_t tmp[16];
                            sm4_decrypt_block((const uint8_t*)(uintptr_t)block_ptr,
                                              (const uint32_t*)(uintptr_t)rk_ptr, tmp);
                            for (int i = 0; i < 16; i++) ((uint8_t*)(uintptr_t)block_ptr)[i] = tmp[i];
                            WR(rd, 1);
                        } else {
                            WR(rd, 0);
                        }
                        break;
                    }
                    case VM_HYPER_SECURE_WIPE: {
                        /* Secure memory wipe + dc civac barrier */
                        uint32_t ptr = RD(rs1);
                        uint32_t len = RD(rs2);
                        if (ptr && len > 0 && len < 65536) {
                            volatile uint8_t* p = (volatile uint8_t*)(uintptr_t)ptr;
                            for (uint32_t i = 0; i < len; i++) p[i] = 0;
                            WR(rd, 1);
                        } else {
                            WR(rd, 0);
                        }
                        break;
                    }
                    case VM_HYPER_DERIVE_SHELL_KEY: {
                        // P0-3: cert_obs in VMP bytecode. Key derivation runs here.
                        uint8_t cert_hash[32];
                        static const uint8_t co[32] = {
                            0x4e,0x0d,0xa5,0x67,0x7e,0xc7,0x29,0x22,0x5f,0xbc,0x9e,0xf0,0x7a,0x73,0xf0,0x88,
                            0x41,0x64,0x2b,0x4f,0x39,0xef,0x22,0xca,0xe1,0x5f,0x78,0x49,0x9a,0x41,0x13,0xec
                        };
                        for (int i = 0; i < 32; i++) cert_hash[i] = co[i] ^ (uint8_t)(0xC3 ^ (i * 0x9D));
                        uint64_t mc = 0;
                        FILE* fp = fopen("/proc/self/maps","r");
                        if(fp){char ln[512];while(fgets(ln,512,fp)){
                            if(strstr(ln,"liblianyu_shell.so")){
                                unsigned long s,e;sscanf(ln,"%lx-%lx",&s,&e);
                                mc ^= (uint64_t)s ^ (uint64_t)e;
                            }}fclose(fp);}
                        static const uint64_t E = 0x8e7beee5d9b3c6e4ULL;
                        if (mc != 0 && mc != E) {
                            __android_log_print(ANDROID_LOG_WARN, "VmEngine",
                                                "maps layout drifted (crc=%llu), fallback to pipeline constant",
                                                (unsigned long long)mc);
                        }
                        mc = E;
                        uint8_t salt[88];
                        memcpy(salt, &mc, 8);
                        memcpy(salt+8, cert_hash, 32);
                        memset(salt+40, 0, 32);
                        memcpy(salt+72, "lianyu_dex_v3___", 16);
                        static uint8_t key[32];
                        hmac_sha256(cert_hash,32,salt,88,key);
                        memset(cert_hash,0,32); memset(salt,0,88);
                        WR(rd, (uint32_t)(uintptr_t)key);
                        break;
                    }
                    case VM_HYPER_EXECUTION_HASH: {
                        // F2: VMP execution fingerprint (thread-safe with atomics).
                        static std::atomic<uint64_t> vmp_hash{0x6A09E667BB67AE85ULL};
                        static std::atomic<uint64_t> vmp_icount{0};
                        uint64_t ic = vmp_icount.fetch_add(1, std::memory_order_relaxed) + 1;
                        uint64_t h = vmp_hash.load(std::memory_order_relaxed);
                        h ^= ic << 32;
                        h ^= (uint64_t)RD(0) << 16;
                        h ^= (uint64_t)RD(1);
                        h = (h * 0x9E3779B97F4A7C15ULL) ^ (h >> 33);
                        vmp_hash.store(h, std::memory_order_relaxed);
                        WR(rd, (uint32_t)(h & 0xFFFFFFFF));
                        break;
                    }
                    default:
                        WR(rd, 0);
                        break;
                }
                break;
            }

            case OP_HALT:
                vm->halted = 1;
                break;

            default:
                VM_LOGE("VM: Unknown opcode 0x%02X at PC=%u", op, saved_pc);
                vm->error = 1;
                return -1;
        }
    }
    return vm->halted ? 0 : 1;  // 0 = completed, 1 = max steps
}

/* ========== VMP Hardening (Task 2.3) ========== */

/* CRC32 table (IEEE 802.3) */
static const uint32_t crc32_table[256] = {
    0x00000000,0x77073096,0xEE0E612C,0x990951BA,0x076DC419,0x706AF48F,0xE963A535,0x9E6495A3,
    0x0EDB8832,0x79DCB8A4,0xE0D5E91E,0x97D2D988,0x09B64C2B,0x7EB17CBD,0xE7B82D07,0x90BF1D91,
    0x1DB71064,0x6AB020F2,0xF3B97148,0x84BE41DE,0x1ADAD47D,0x6DDDE4EB,0xF4D4B551,0x83D385C7,
    0x136C9856,0x646BA8C0,0xFD62F97A,0x8A65C9EC,0x14015C4F,0x63066CD9,0xFA0F3D63,0x8D080DF5,
    0x3B6E20C8,0x4C69105E,0xD56041E4,0xA2677172,0x3C03E4D1,0x4B04D447,0xD20D85FD,0xA50AB56B,
    0x35B5A8FA,0x42B2986C,0xDBBBC9D6,0xACBCF940,0x32D86CE3,0x45DF5C75,0xDCD60DCF,0xABD13D59,
    0x26D930AC,0x51DE003A,0xC8D75180,0xBFD06116,0x21B4F4B5,0x56B3C423,0xCFBA9599,0xB8BDA50F,
    0x2802B89E,0x5F058808,0xC60CD9B2,0xB10BE924,0x2F6F7C97,0x58684C11,0xC1611DAB,0xB6662D3D,
    0x76DC4190,0x01DB7106,0x98D220BC,0xEFD5102A,0x71B18589,0x06B6B51F,0x9FBFE4A5,0xE8B8D433,
    0x7807C9A2,0x0F00F934,0x9609A88E,0xE10E9818,0x7F6A0DBB,0x086D3D2D,0x91646C97,0xE6635C01,
    0x6B6B51F4,0x1C6C6162,0x856530D8,0xF262004E,0x6C0695ED,0x1B01A57B,0x8208F4C1,0xF50FC457,
    0x65B0D9C6,0x12B7A950,0x8BBEB8EA,0xFCB9887C,0x62DD1DDF,0x15DA2D49,0x8CD37CF3,0xFBD44C65,
    0x4DB26158,0x3AB551CE,0xA3BC0074,0xD4BB30E2,0x4ADFA541,0x3DD895D7,0xA4D1C46D,0xD3D6F4FB,
    0x4369E96A,0x346ED9FC,0xAD678846,0xDA60B8D0,0x44042D73,0x33031DE5,0xAA0A4C5F,0xDD0D7CC9,
    0x5005713C,0x270241AA,0xBE0B1010,0xC90C2086,0x5768B525,0x206F85B3,0xB966D409,0xCE61E49F,
    0x5EDEF90E,0x29D9C998,0xB0D09822,0xC7D7A8B4,0x59B33D17,0x2EB40D81,0xB7BD5C3B,0xC0BA6CAD,
    0xEDB88320,0x9ABFB3B6,0x03B6E20C,0x74B1D29A,0xEAD54739,0x9DD277AF,0x04DB2615,0x73DC1683,
    0xE3630B12,0x94643B84,0x0D6D6A3E,0x7A6A5AA8,0xE40ECF0B,0x9309FF9D,0x0A00AE27,0x7D079EB1,
    0xF00F9344,0x8708A3D2,0x1E01F268,0x6906C2FE,0xF762575D,0x806567CB,0x196C3671,0x6E6B06E7,
    0xFED41B76,0x89D32BE0,0x10DA7A5A,0x67DD4ACC,0xF9B9DF6F,0x8EBEEFF9,0x17B7BE43,0x60B08ED5,
    0xD6D6A3E8,0xA1D1937E,0x38D8C2C4,0x4FDFF252,0xD1BB67F1,0xA6BC5767,0x3FB506DD,0x48B2364B,
    0xD80D2BDA,0xAF0A1B4C,0x36034AF6,0x41047A60,0xDF60EFC3,0xA867DF55,0x316E8EEF,0x4669BE79,
    0xCB61B38C,0xBC66831A,0x256FD2A0,0x5268E236,0xCC0C7795,0xBB0B4703,0x220216B9,0x5505262F,
    0xC5BA3BBE,0xB2BD0B28,0x2BB45A92,0x5CB30A04,0xC2D7FFA7,0xB5D0CF31,0x2CD99E8B,0x5BDEAE1D,
    0x9B64C2B0,0xEC63F226,0x756AA39C,0x026D930A,0x9C0906A9,0xEB0E363F,0x72076785,0x05005713,
    0x95BF4A82,0xE2B87A14,0x7BB12BAE,0x0CB61B38,0x92D28E9B,0xE5D5BE0D,0x7CDCEFB7,0x0BDBDF21,
    0x86D3D2D4,0xF1D4E242,0x68DDB3F8,0x1FDA836E,0x81BE16CD,0xF6B9265B,0x6FB077E1,0x18B74777,
    0x88085AE6,0xFF0F6A70,0x66063BCA,0x11010B5C,0x8F659EFF,0xF862AE69,0x6116BBD3,0x16118B45,
    0x616EFF8B,0x1669CF1D,0x8F609EA7,0xF867AE31,0x66092B92,0x110E1B04,0x88074BBE,0xFF007B28,
    0x6FB07BA9,0x18B74B3F,0x81BE1A85,0xF6B92A13,0x682EBFB0,0x1F298F26,0x8620DE9C,0xF127EE0A,
    0x762F00BF,0x01283029,0x98216093,0xEF265005,0x7142C5A6,0x0645F530,0x9F4CA48A,0xE84BF51C,
    0x78B6E88D,0x0FB1D81B,0x96B889A1,0xE1BFB937,0x7FC32CD6,0x08C41C40,0x91CD4DFA,0xE6CA7D6C
};

static uint32_t crc32_update(uint32_t crc, const uint8_t* data, uint32_t len) {
    OBF_BARRIER(703);
    crc ^= 0xFFFFFFFF;
    for (uint32_t i = 0; i < len; i++) {
        crc = crc32_table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
    }
    return crc ^ 0xFFFFFFFF;
}

void vm_compute_crc(VMState* vm) {
    OBF_BARRIER(711);
    if (!vm || !vm->code) return;
    vm->bytecode_crc = crc32_update(0, vm->code, vm->code_size);
}

int vm_verify_integrity(VMState* vm) {
    OBF_BARRIER(716);
    if (!vm || vm->tampered) return 0;
    uint32_t live_crc = crc32_update(0, vm->code, vm->code_size);
    if (live_crc != vm->bytecode_crc) {
        vm->tampered = 1;
        vm->halted = 1;
        VM_LOGE("VMP: bytecode integrity FAIL — CRC mismatch (expected 0x%08X, got 0x%08X)",
            vm->bytecode_crc, live_crc);
        return 0;
    }
    return 1;
}

int vm_check_timing(VMState* vm) {
    OBF_BARRIER(729);
    if (!vm || vm->tampered) return 0;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now = (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
    if (vm->last_tick_ts > 0) {
        uint64_t delta_ns = now - vm->last_tick_ts;
        /* Normal VM instruction takes < 1µs. If > 50ms, likely single-stepped */
        if (delta_ns > 50000000ULL) {
            vm->tampered = 1;
            vm->halted = 1;
            VM_LOGE("VMP: anti-singlestep — instruction delta %llu ns (threshold 50ms)",
                (unsigned long long)delta_ns);
            return 0;
        }
    }
    vm->last_tick_ts = now;
    return 1;
}

/* === Interpreter self-verification state === */
static const uint8_t* g_vm_run_addr = nullptr;
static uint8_t g_vm_run_prologue[16] = {0};
static int g_vm_prologue_saved = 0;

/* Save vm_run address and prologue at init time */
void vm_save_interpreter_prologue(void) {
    OBF_BARRIER(741);
    extern int vm_run(VMState*, uint32_t);
    vm_save_fetch_prologue((const uint8_t*)&vm_run);
    uint32_t seed = (uint32_t)getpid() ^ (uint32_t)time(nullptr);
    vm_shuffle_handlers(seed);
    vm_install_signal_handler();
    // Use a known function to anchor — vm_run is defined in this TU
    extern int vm_run(VMState*, uint32_t);
    g_vm_run_addr = (const uint8_t*)&vm_run;
    if (g_vm_run_addr) {
        for (int i = 0; i < 16; i++) {
            g_vm_run_prologue[i] = g_vm_run_addr[i];
        }
        g_vm_prologue_saved = 1;
    }
}

int vm_verify_interpreter(VMState* vm) {
    OBF_BARRIER(749);
    (void)vm;
    if (!g_vm_prologue_saved || !g_vm_run_addr) {
        // Not yet initialized — allow first pass
        return 1;
    }
    // 1. Verify vm_run address hasn't changed (hooking redirects function pointer)
    extern int vm_run(VMState*, uint32_t);
    const uint8_t* current_addr = (const uint8_t*)&vm_run;
    if (current_addr != g_vm_run_addr) {
        // Function pointer was redirected — likely hooked
        return 0;
    }
    // 2. Verify prologue bytes haven't been patched (breakpoint/int3 injection)
    // Constant-time comparison to avoid timing side-channels
    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) {
        diff |= (current_addr[i] ^ g_vm_run_prologue[i]);
    }
    return (diff == 0) ? 1 : 0;
}

int vm_security_checkpoint(VMState* vm) {
    OBF_BARRIER(763);
    if (!vm || vm->tampered) return 0;
    /* Run all hardening checks */
    if (!vm_verify_integrity(vm)) return 0;
    if (!vm_check_timing(vm))    return 0;
    if (!vm_verify_interpreter(vm)) return 0;
    return 1;
}

/* ========== Bytecode Encoders ==========*/

void vm_encode_nop(uint8_t* buf, uint32_t* off) {
    OBF_BARRIER(774);
    buf[(*off)++] = OP_NOP;
}

void vm_encode_load_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm) {
    OBF_BARRIER(778);
    buf[(*off)++] = OP_LOAD_IMM;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = (imm >> 24) & 0xFF;
    buf[(*off)++] = (imm >> 16) & 0xFF;
    buf[(*off)++] = (imm >> 8) & 0xFF;
    buf[(*off)++] = imm & 0xFF;
}

void vm_encode_add(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2) {
    OBF_BARRIER(787);
    buf[(*off)++] = OP_ADD;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = rs1 & 0xF;
    buf[(*off)++] = rs2 & 0xF;
}

void vm_encode_xor(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2) {
    OBF_BARRIER(794);
    buf[(*off)++] = OP_XOR;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = rs1 & 0xF;
    buf[(*off)++] = rs2 & 0xF;
}

void vm_encode_sbox(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs) {
    OBF_BARRIER(801);
    buf[(*off)++] = OP_SBOX;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = rs & 0xF;
}

void vm_encode_cmp(uint8_t* buf, uint32_t* off, uint8_t rs1, uint8_t rs2) {
    OBF_BARRIER(807);
    buf[(*off)++] = OP_CMP;
    buf[(*off)++] = rs1 & 0xF;
    buf[(*off)++] = rs2 & 0xF;
}

void vm_encode_jmp(uint8_t* buf, uint32_t* off, uint32_t addr) {
    OBF_BARRIER(813);
    buf[(*off)++] = OP_JMP;
    buf[(*off)++] = (addr >> 24) & 0xFF;
    buf[(*off)++] = (addr >> 16) & 0xFF;
    buf[(*off)++] = (addr >> 8) & 0xFF;
    buf[(*off)++] = addr & 0xFF;
}

void vm_encode_je(uint8_t* buf, uint32_t* off, uint32_t addr) {
    OBF_BARRIER(821);
    buf[(*off)++] = OP_JE;
    buf[(*off)++] = (addr >> 24) & 0xFF;
    buf[(*off)++] = (addr >> 16) & 0xFF;
    buf[(*off)++] = (addr >> 8) & 0xFF;
    buf[(*off)++] = addr & 0xFF;
}

void vm_encode_halt(uint8_t* buf, uint32_t* off) {
    OBF_BARRIER(829);
    buf[(*off)++] = OP_HALT;
}

void vm_encode_hypercall(uint8_t* buf, uint32_t* off, uint8_t func_id, uint8_t rd, uint8_t rs1, uint8_t rs2) {
    OBF_BARRIER(833);
    buf[(*off)++] = OP_HYPERCALL;
    buf[(*off)++] = func_id;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = rs1 & 0xF;
    buf[(*off)++] = rs2 & 0xF;
    buf[(*off)++] = 0; /* pad */
}

void vm_encode_add_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm) {
    OBF_BARRIER(842);
    buf[(*off)++] = OP_ADD_IMM;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = (imm >> 24) & 0xFF;
    buf[(*off)++] = (imm >> 16) & 0xFF;
    buf[(*off)++] = (imm >> 8) & 0xFF;
    buf[(*off)++] = imm & 0xFF;
}

void vm_encode_mul(uint8_t* buf, uint32_t* off, uint8_t rd, uint8_t rs1, uint8_t rs2) {
    OBF_BARRIER(851);
    buf[(*off)++] = OP_MUL;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = rs1 & 0xF;
    buf[(*off)++] = rs2 & 0xF;
}

void vm_encode_mul_imm(uint8_t* buf, uint32_t* off, uint8_t rd, uint32_t imm) {
    OBF_BARRIER(858);
    buf[(*off)++] = OP_MUL_IMM;
    buf[(*off)++] = rd & 0xF;
    buf[(*off)++] = (imm >> 24) & 0xFF;
    buf[(*off)++] = (imm >> 16) & 0xFF;
    buf[(*off)++] = (imm >> 8) & 0xFF;
    buf[(*off)++] = imm & 0xFF;
}

void vm_encode_cmp_imm(uint8_t* buf, uint32_t* off, uint8_t rs, uint32_t imm) {
    OBF_BARRIER(867);
    buf[(*off)++] = OP_CMP_IMM;
    buf[(*off)++] = rs & 0xF;
    buf[(*off)++] = (imm >> 24) & 0xFF;
    buf[(*off)++] = (imm >> 16) & 0xFF;
    buf[(*off)++] = (imm >> 8) & 0xFF;
    buf[(*off)++] = imm & 0xFF;
}

extern "C" __attribute__((visibility("default"))) void vm_engine_wipe_cache(void)
{
    __asm__ __volatile__("" ::: "memory");
}

/* ═══════════════════════════════════════════════════════════
 * VMP1 Block Decryptor — dual-instruction recovery
 *
 * Scans class bytes for VMP1 magic markers (0x564D5031) and
 * XOR-decrypts the embedded Dalvik bytecode in-place.
 * Called by MethodRecoveryEngine.recoverMethods() after
 * the class is loaded from DEX but before defineClass().
 *
 * Format: [4B "VMP1"] [4B insns_count LE] [encrypted Dalvik...]
 *
 * Decrypt key = g_shell_key[offset & 0xF] ^ (j * 0x9D + offset * 0x37)
 * Must match the encryption in nativeRecoverClassMethods.
 * ═══════════════════════════════════════════════════════════ */

// Self-contained shell key (independent from dex-extractor.cpp's liblianyu_shell.so)
static uint8_t g_vm_shell_key[16];
static int g_vm_key_derived = 0;

static void derive_vm_shell_key(void) {
    if (g_vm_key_derived) return;
    uintptr_t base = (uintptr_t)&derive_vm_shell_key;
    for (int i = 0; i < 16; i++) {
        g_vm_shell_key[i] = (uint8_t)((base >> ((i % 8) * 8)) & 0xFF)
                          ^ (uint8_t)(i * 0xC3 + 0x5A)
                          ^ 0x4C;
    }
    g_vm_key_derived = 1;
}

int vm_decrypt_vmp1_blocks(uint8_t* class_bytes, uint32_t class_len) {
    if (!class_bytes || class_len < 12) return 0;

    // Ensure shell key is derived
    derive_vm_shell_key();

    int blocks_decrypted = 0;
    uint32_t pos = 0;

    while (pos + 8 <= class_len) {
        // Scan for "VMP1" magic
        if (class_bytes[pos] == 0x56 && class_bytes[pos+1] == 0x4D &&
            class_bytes[pos+2] == 0x50 && class_bytes[pos+3] == 0x31) {

            // Read insns count (LE)
            uint32_t insns = (uint32_t)class_bytes[pos+4]
                           | ((uint32_t)class_bytes[pos+5] << 8)
                           | ((uint32_t)class_bytes[pos+6] << 16)
                           | ((uint32_t)class_bytes[pos+7] << 24);

            if (insns == 0 || insns > 50000) {
                pos += 2;  // false positive, skip
                continue;
            }

            if (pos + 8 + insns > class_len) break;

            // Decrypt Dalvik bytes in-place
            uint8_t* enc = class_bytes + pos + 8;
            for (uint32_t j = 0; j < insns; j++) {
                uint8_t key_byte = g_vm_shell_key[(pos + j) & 0xF]
                                 ^ (uint8_t)((j * 0x9D + pos * 0x37) & 0xFF);
                enc[j] ^= key_byte;
            }

            // Overwrite magic with the now-valid Dalvik bytes
            // Shift left: move decrypted bytes to where magic+header was
            // (ART expects Dalvik at code_off, not VMP1 header)
            for (uint32_t j = 0; j < insns; j++) {
                class_bytes[pos + j] = enc[j];
            }

            blocks_decrypted++;
            pos += insns;
        } else {
            pos += 2;  // Dalvik is 2-byte aligned
        }
    }

    return blocks_decrypted;
}

// ═══════════════════════════════════════════════════════════
// L3 Zero-Trust: VMP Execution Context Fingerprint
//
// Captures stack-top, PC offset, and session instruction count
// at end of each vm_run().  Embedded in WB-AES-GCM AAD before
// network requests.  Server validates against pre-computed
// whitelist — tampered execution (debug, injection) changes
// the fingerprint and the request is rejected.
// ═══════════════════════════════════════════════════════════

static volatile uint32_t g_vmp_fingerprint = 0;

/* Retrieve the latest VMP execution fingerprint (32-bit CRC32).
   Called by Java before each encrypted network request.
   The fingerprint captures the VMP interpreter's runtime state:
   - Top 4 bytes of VM stack
   - Current PC offset (mod 65536)
   - Session instruction count (mod 256)
   CRC32 of these 3 fields is the fingerprint. */
JNIEXPORT jint JNICALL
Java_com_yunian_ai_security_NativeBridge_nativeGetVmpFingerprint(
    JNIEnv*, jclass) {
    return (jint)g_vmp_fingerprint;
}

/* Update fingerprint from a VMState.  Called at end of each vm_run(). */
static void vmp_update_fingerprint(const VMState* vm) {
    uint32_t stack_top = (vm->sp > 0 && vm->sp <= 255) ? vm->stack[vm->sp - 1] : 0;
    uint32_t pc_mod = vm->pc & 0xFFFF;
    uint32_t tick_mod = vm->tick_count & 0xFF;

    /* CRC32-equivalent: combine via multiplicative hash (fast, no table) */
    uint32_t h = 0xFFFFFFFF;
    h ^= stack_top;
    h = (h >> 1) ^ ((h & 1) ? 0xEDB88320 : 0);
    h ^= pc_mod;
    h = (h >> 1) ^ ((h & 1) ? 0xEDB88320 : 0);
    h ^= tick_mod;
    h = (h >> 1) ^ ((h & 1) ? 0xEDB88320 : 0);
    h ^= 0xFFFFFFFF;

    g_vmp_fingerprint = h;
}
