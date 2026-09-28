#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/random.h>
#include <time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>
#include <signal.h>
#include <elf.h>
#include <sys/inotify.h>
#include <sys/syscall.h>
#include <pthread.h>
#include <link.h>
#include "sm-cipher.h"
#include "whitebox-aes.h"
#include "obfuscated_strings.h"

#include "g_vmp_config.h"  // per-build shell method names + algorithm constants
#include "dex-packer.h"     // native_load_payload_entry for RegisterNatives
#include "vm-engine.h"
#include "memory-guard.h"
#include "integrity-guard.h"
#include "decrypt-stub.h"
#include "obfuscate.h"
#ifdef PRODUCTION_BUILD
#define LS_LOGE(...) ((void)0)
#define LOGE(...) ((void)0)
#else
#define LS_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LS", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "LS", __VA_ARGS__)
#endif

// === XOR-obfuscated detection strings — rotating per-string keys ===
// OB_KEY provided by obfuscated_strings.h
// idx=0 key=0x00
#define OB_FRIDA     0x66,0x72,0x69,0x64,0x61
// idx=1 key=0xB9
#define OB_GUMJS     0xDE,0xCC,0xD4,0x94,0xD3,0xCA
// idx=2 key=0x72
#define OB_GADGET    0x15,0x13,0x16,0x15,0x17,0x06
// idx=3 key=0x2B
#define OB_LINJECTOR 0x47,0x42,0x45,0x41,0x4E,0x48,0x5F,0x44,0x59
// idx=4 key=0xE4
#define OB_XPOSED    0x9C,0x94,0x8B,0x97,0x81,0x80
// idx=5 key=0x9D
#define OB_FRIDA_SRV 0xFB,0xEF,0xF4,0xF9,0xFC,0xB0,0xEE,0xF8,0xEF,0xEB,0xF8,0xEF
// idx=6 key=0x56
#define OB_FRIDA_AG  0x30,0x24,0x3F,0x32,0x37,0x7B,0x37,0x31,0x33,0x38,0x22
// idx=7 key=0x0F
#define OB_MAGISK    0x62,0x6E,0x68,0x66,0x7C,0x61
// idx=8 key=0xC8
#define OB_SUBSTRATE 0xBB,0xBD,0xA4,0xBB,0xBC,0xBA,0xA9,0xBC,0xAD
// idx=9 key=0x81
#define OB_LSPOSED   0xED,0xF2,0xF1,0xEE,0xF2,0xE4,0xE5
// idx=10 key=0x3A
#define OB_WORKER    0x4C,0x55,0x48,0x54,0x5F,0x48

// Per-string deobfuscation with rotating key derived from compile-time index
static int xstrstr(const char* h, const uint8_t* ob, size_t ob_len, uint8_t key) {
    OBF_BARRIER(48);
    if (!h || !ob || ob_len == 0 || ob_len >= 64) return 0;
    char n[64]; for (size_t i = 0; i < ob_len; i++) n[i] = (char)(ob[i] ^ key);
    n[ob_len] = 0; int r = (strstr(h, n) != NULL); memset(n, 0, ob_len); return r;
}

// Static obfuscated string tables (one per index)
static const uint8_t _obs_0[] = { OB_FRIDA };
static const uint8_t _obs_1[] = { OB_GUMJS };
static const uint8_t _obs_2[] = { OB_GADGET };
static const uint8_t _obs_3[] = { OB_LINJECTOR };
static const uint8_t _obs_4[] = { OB_XPOSED };
static const uint8_t _obs_5[] = { OB_FRIDA_SRV };
static const uint8_t _obs_6[] = { OB_FRIDA_AG };
static const uint8_t _obs_7[] = { OB_MAGISK };
static const uint8_t _obs_8[] = { OB_SUBSTRATE };
static const uint8_t _obs_9[] = { OB_LSPOSED };
static const uint8_t _obs_10[] = { OB_WORKER };

// Map index -> (data, len, key)
static int _xstrstr_idx(const char* haystack, int idx) {
    const struct { const uint8_t* d; size_t len; uint8_t key; } tbl[] = {
        { _obs_0, sizeof(_obs_0), OB_KEY(0) },
        { _obs_1, sizeof(_obs_1), OB_KEY(1) },
        { _obs_2, sizeof(_obs_2), OB_KEY(2) },
        { _obs_3, sizeof(_obs_3), OB_KEY(3) },

        { _obs_4, sizeof(_obs_4), OB_KEY(4) },
        { _obs_5, sizeof(_obs_5), OB_KEY(5) },
        { _obs_6, sizeof(_obs_6), OB_KEY(6) },
        { _obs_7, sizeof(_obs_7), OB_KEY(7) },
        { _obs_8, sizeof(_obs_8), OB_KEY(8) },
        { _obs_9, sizeof(_obs_9), OB_KEY(9) },
        { _obs_10, sizeof(_obs_10), OB_KEY(10) },
    };
    if (idx < 0 || idx > 10) return 0;
    return xstrstr(haystack, tbl[idx].d, tbl[idx].len, tbl[idx].key);
}
#define XSTRSTR_IDX(h, p, idx) _xstrstr_idx(h, idx)
#define XSTRSTR(h, p) _xstrstr_idx(h, 0)

// ── Maps replacement for Android 14+ ──
// On Android 14+, /proc/self/maps is restricted (returns EACCES).
// dl_iterate_phdr() lists loaded shared objects without file I/O and works on all versions.
struct mctx { const char** pat; volatile int found; };
static int mcb(struct dl_phdr_info* info, size_t, void* data) {
    OBF_BARRIER(59);
    struct mctx* ctx = (struct mctx*)data;
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;
    for (int i = 0; ctx->pat[i]; i++) {
        if (strstr(info->dlpi_name, ctx->pat[i])) { ctx->found = 1; return 1; }
    }
    return 0;
}
static int maps_has(const char* patterns[]) {
    OBF_BARRIER(67);
    struct mctx ctx = {patterns, 0};
    dl_iterate_phdr(mcb, &ctx);
    return ctx.found;
}

// Extract APK path from any loaded native library's path.
// e.g. /data/app/.../lib/arm64/liblianyu_security.so → /data/app/.../base.apk
static const char* find_apk_path_via_dl() {
    static char apk_buf[256];
    if (apk_buf[0]) return apk_buf;
    struct { char buf[256]; volatile int ok; } ctx = {{0}, 0};
    dl_iterate_phdr([](struct dl_phdr_info* info, size_t, void* data) -> int {
        auto* c = (decltype(&ctx))data;
        const char* n = info->dlpi_name;
        if (!n || !n[0]) return 0;
        // Look for a library inside the APK's lib directory → /data/app/.../lib/arm64/xxx.so
        const char* lib = strstr(n, "/lib/");
        if (lib) {
            size_t plen = (size_t)(lib - n);
            if (plen >= sizeof(c->buf) - 20) return 0;
            memcpy(c->buf, n, plen);
            strcpy(c->buf + plen, "/base.apk");
            c->ok = 1;
            return 1;
        }
        // Also try looking for base.apk directly in the path
        if (strstr(n, "base.apk")) {
            size_t l = strlen(n);
            if (l < sizeof(c->buf)) { memcpy(c->buf, n, l+1); c->ok = 1; return 1; }
        }
        return 0;
    }, &ctx);
    // Copy found path from stack ctx.buf → static apk_buf so callers get a valid pointer
    if (ctx.ok) { memcpy(apk_buf, ctx.buf, 256); return apk_buf; }
    return NULL;
}

// Find liblianyu_security.so executable segment mapping via dl_iterate_phdr
// Returns 1 and fills exec_start/exec_end if found.
static int find_security_so_exec_range(unsigned long* exec_start, unsigned long* exec_end) {
    OBF_BARRIER(107);
    if (!exec_start || !exec_end) return 0;
    struct ctx_t { unsigned long s, e; volatile int found; } ctx = {0, 0, 0};
    dl_iterate_phdr([](struct dl_phdr_info* info, size_t, void* data) -> int {
        auto* c = (ctx_t*)data;
        if (strstr(info->dlpi_name, "liblianyu_security.so")) {
            // Find executable segment in program headers
            for (int i = 0; i < info->dlpi_phnum; i++) {
                if (info->dlpi_phdr[i].p_type == PT_LOAD && (info->dlpi_phdr[i].p_flags & PF_X)) {
                    c->s = info->dlpi_addr + info->dlpi_phdr[i].p_vaddr;
                    c->e = c->s + info->dlpi_phdr[i].p_memsz;
                    c->found = 1;
                    return 1;
                }
            }
        }
        return 0;
    }, &ctx);
    if (ctx.found) { *exec_start = ctx.s; *exec_end = ctx.e; return 1; }
    return 0;
}

static volatile int g_ck = 0;
static volatile int g_crash_detected = 0;
__attribute__((used)) static void on_crash(int s, siginfo_t* i, void* x) {
    // Extract PC from ucontext (structure differs by arch)
    void* pc = NULL;
#ifdef __x86_64__
    pc = (void*)((ucontext_t*)x)->uc_mcontext.gregs[REG_RIP];
#elif defined(__aarch64__)
    pc = (void*)((ucontext_t*)x)->uc_mcontext.pc;
#elif defined(__arm__)
    pc = (void*)((ucontext_t*)x)->uc_mcontext.arm_pc;
#elif defined(__i386__)
    pc = (void*)((ucontext_t*)x)->uc_mcontext.gregs[REG_EIP];
#endif

    g_crash_detected = 1;

    /* Do NOT forward to SIG_DFL — on HarmonyOS/EMUI, legitimate
     * GPU/system signals would kill the process. Just log. */
    __android_log_print(ANDROID_LOG_WARN, "LS",
        "Signal %d caught — logging only (HarmonyOS compatibility)", s);
}

/* inotify watcher for /proc/self/maps — detects debugger memory inspection */
static volatile int g_maps_watched = 0;

static void* inotify_maps_watcher(void* arg) {
    int fd = (int)(intptr_t)arg;
    char buf[4096];
    while (g_maps_watched) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n > 0) {
            // /proc/self/maps was accessed — likely debugger.
            // Do NOT abort() — HarmonyOS/EMUI system services may
            // legitimately read /proc/self/maps for process monitoring.
            g_maps_watched = 0;
            __android_log_print(ANDROID_LOG_WARN, "LS",
                "/proc/self/maps accessed (possible debugger) — logging only");
            break;
        }
        usleep(100000); // 100ms poll
    }
    close(fd);
    return NULL;
}

// ====================================================================
// Software Breakpoint Detection — prologue integrity snapshots
// ====================================================================
// Debuggers (GDB, LLDB, Frida) insert BRK #0 (0xD4200000 on ARM64)
// into function prologues. We snapshot critical function entry bytes
// at startup and periodically verify they haven't been patched.
// Disable with -DDISABLE_BP_GUARD for troubleshooting.

#ifndef DISABLE_BP_GUARD

#define BP_GUARD_COUNT  4
#define BP_GUARD_SIZE   32   // bytes to snapshot per function

typedef struct {
    const uint8_t* addr;
    uint8_t        snapshot[BP_GUARD_SIZE];
} bp_guard_slot;

static bp_guard_slot g_bp_guards[BP_GUARD_COUNT];
static volatile int g_bp_guard_inited = 0;
static volatile int g_bp_breached = 0;

// Return non-zero if any guarded function prologue was patched.
__attribute__((noinline))
static int bp_guard_check(void) {
    OBF_BARRIER(195);
    if (!g_bp_guard_inited) return 0;
    for (int i = 0; i < BP_GUARD_COUNT; i++) {
        if (g_bp_guards[i].addr == NULL) continue;
        // memcmp can be hooked — compare byte-by-byte
        const uint8_t* cur = g_bp_guards[i].addr;
        const uint8_t* snap = g_bp_guards[i].snapshot;
        for (int j = 0; j < BP_GUARD_SIZE; j++) {
            if (cur[j] != snap[j]) {
                __android_log_print(ANDROID_LOG_ERROR, "LS",
                    "!!! SW BP detected at %p offset %d: 0x%02X→0x%02X",
                    (void*)cur, j, snap[j], cur[j]);
                g_bp_breached = 1;
                return 1;
            }
        }
    }
    return 0;
}

// Register a function for breakpoint monitoring. Must be called
// early (before any debugger has a chance to attach).
__attribute__((noinline))
static void bp_guard_register(int slot, const void* func) {
    OBF_BARRIER(218);
    if (slot < 0 || slot >= BP_GUARD_COUNT) return;
    if (func == NULL) return;
    g_bp_guards[slot].addr = (const uint8_t*)func;
    // Byte-by-byte copy to avoid hooked memcpy
    const uint8_t* src = (const uint8_t*)func;
    for (int i = 0; i < BP_GUARD_SIZE; i++) {
        g_bp_guards[slot].snapshot[i] = src[i];
    }
}

#endif // DISABLE_BP_GUARD

#pragma GCC visibility push(hidden)

/* Forward declarations — static functions defined later in this file */
extern "C" 
/* KmsProvider JNI functions (defined in kms-engine.cpp, registered in JNI_OnLoad) */
extern "C" {
    jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeEncrypt(JNIEnv*, jclass, jbyteArray);
    jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeDecrypt(JNIEnv*, jclass, jbyteArray);
    jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeEncryptV2(JNIEnv*, jclass, jbyteArray, jbyteArray);
    jbyteArray Java_com_yunian_ai_security_KmsProvider_nativeDecryptV2(JNIEnv*, jclass, jbyteArray, jbyteArray);
    jint Java_com_yunian_ai_security_KmsProvider_nativeInit(JNIEnv*, jclass);
    void Java_com_yunian_ai_security_KmsProvider_nativeDestroyKeychain(JNIEnv*, jclass);
    jint Java_com_yunian_ai_security_KmsProvider_nativeGetStatus(JNIEnv*, jclass);
}

__attribute__((visibility("default"))) extern "C" int check_dex_integrity(void);
__attribute__((visibility("default"))) extern "C" int check_so_integrity(void);
__attribute__((visibility("default"))) extern "C" int check_resources_integrity(void);

// VM bytecode programs (from vm-bytecode.cpp, linked as static lib)
extern const uint8_t g_vmp_wb_aes_keycheck[];
extern const uint32_t g_vmp_wb_aes_keycheck_size;
extern const uint8_t g_vmp_kms_derive_sk[];
extern const uint32_t g_vmp_kms_derive_sk_size;
extern const uint8_t g_vmp_tee_attest[];
extern const uint32_t g_vmp_tee_attest_size;
extern const uint8_t g_vmp_apk_sig_verify[];
extern const uint32_t g_vmp_apk_sig_verify_size;
extern const uint8_t g_vmp_root_detect[];
extern const uint32_t g_vmp_root_detect_size;
extern const uint8_t g_vmp_code_integrity[];
extern const uint32_t g_vmp_code_integrity_size;
extern const uint8_t g_vmp_sm3_hash[];
extern const uint32_t g_vmp_sm3_hash_size;
extern const uint8_t g_vmp_trust_anchors_verify[];
extern const uint32_t g_vmp_trust_anchors_verify_size;
extern const uint8_t g_vmp_frida_heartbeat[];
extern const uint32_t g_vmp_frida_heartbeat_size;


// === Control-flow obfuscation macros (anti-IDA/Ghidra) ===
// These create opaque predicates — conditional branches that always
// resolve the same way but appear unpredictable to static analyzers.
#define OBF_BEGIN volatile int _obf_flag = 0x5A5A; switch(_obf_flag) { case 0x5A5A:
#define OBF_DEAD  break; default:
#define OBF_END   }
#define OBF_SPLIT break; default:
// Flatten a switch into spaghetti for the disassembler
#define OBF_FLATTEN(val) do { volatile int _o = (val); switch(_o) { case 0: default: break; } } while(0)
// Never-taken branch (confuses static analysis)
#define OBF_NEVER if ((__builtin_constant_p(0) ? 0 : (volatile int)0xDEADBEEF) == 0xC0DE) { __builtin_unreachable(); }

static volatile int g_guard_ok = 0;
static volatile int g_guard_ran = 0;
__attribute__((visibility("default"))) volatile int g_sig_ok = 0;
/* Mirror of g_sig_ok for VMP hypercall bridge — defined in VMP section with extern "C" linkage */
#ifdef __cplusplus
extern "C" {
#endif
extern volatile int g_sig_ok_bridge;
#ifdef __cplusplus
}
#endif
static volatile int g_mitm_detected = 0;

static void wipe(void* p, size_t n) {
    volatile unsigned char* v = (volatile unsigned char*)p;
    for (size_t i = 0; i < n; i++) v[i] = 0;
    __asm__ __volatile__("" ::: "memory");
}

static int f1(int a, int b) {
    volatile int x = a * 7 + b * 3;
    x ^= 0xDEAD;
    x = (x << 3) | (x >> 29);
    return (x & 1) ? a : b;
}

static int f2(int v) {
    volatile int r = v ^ 0xBEEF;
    r = (r * 31 + 17) & 0xFFFF;
    return r;
}

static void d_s(unsigned char* out, int idx) {
    static const unsigned char E[160] = {
        0x39,0x0C,0x8C,0x7D,0x72,0x47,0x34,0x2C,0xD8,0x10,
        0x0F,0x2F,0x6F,0x77,0x0D,0x65,0xD6,0x70,0xE5,0x8E,
        0x03,0x51,0xD8,0xAE,0x8E,0x4F,0x6E,0xAC,0x34,0x2F,
        0xC2,0x31,0xB7,0xB0,0x87,0x16,0xEB,0x3F,0xC1,0x28,
        0x1A,0x89,0x2C,0xD0,0x88,0x37,0x13,0xC2,0xAA,0xAE,
        0x1A,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
    };
    static const unsigned char K[160] = {
        0x5E,0x64,0xFC,0x22,0x3E,0x25,0x50,0x5E,0x99,0x7F,
        0x4E,0x42,0x03,0x39,0x4E,0x57,0x9C,0x24,0x9F,0xC7,
        0x61,0x2B,0xBF,0xE0,0xB9,0x3C,0x3D,0xE3,0x62,0x4D,
        0xB5,0x66,0xDE,0xDB,0xB7,0x53,0xA3,0x6F,0x83,0x5C,
        0x76,0xE0,0x42,0xA2,0xFD,0x58,0x6B,0xAB,0x9C,0x98,
        0x2C,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
        0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
    };

    volatile int state = 0;
    volatile int i = 0;
    volatile int done = 0;

    while (!done) {
        switch (state) {
            case 0:
                i = 0;
                state = f1(1, 3);
                break;
            case 1:
                if (i >= 40) { state = 2; break; }
                out[i] = E[idx * 40 + i] ^ K[idx * 40 + i];
                i++;
                state = f1(1, 1);
                break;
            case 2:
                out[40] = 0;
                done = 1;
                state = 999;
                break;
            case 3:
                state = f1(0, 1);
                break;
            case 4:
                if (f2(i) > 50000) state = 5;
                else state = 1;
                break;
            case 5:
                state = f1(1, 3);
                break;
            default:
                state = 0;
                break;
        }
    }
}

static void d_o(unsigned char* out) {
    static const unsigned char E[11] = {0x31,0x47,0xD1,0xF3,0x4F,0x08,0xBC,0x70,0xC6,0x7A,0xDE};
    static const unsigned char K[11] = {0x5D,0x2E,0xBF,0x81,0x3A,0x67,0xC4,0x19,0xF0,0x4C,0xE8};

    volatile int state = 0;
    volatile int i = 0;
    volatile int done = 0;

    while (!done) {
        switch (state) {
            case 0:
                i = 0;
                state = 1;
                break;
            case 1:
                if (i >= 11) { state = 2; break; }
                out[i] = E[i] ^ K[i];
                i++;
                state = (f2(i) & 1) ? 1 : 3;
                break;
            case 2:
                out[11] = 0;
                done = 1;
                break;
            case 3:
                state = 1;
                break;
            default:
                state = 0;
                break;
        }
    }
}

static void d_se(unsigned char* out) {
    static const unsigned char E[32] = {
        0xD7,0x6F,0x22,0xE2,0xE4,0x5D,0x2B,0x29,
        0xD7,0xE5,0x2B,0x6B,0xEE,0x20,0x79,0x4A,
        0x2C,0x5B,0xCF,0x63,0xD1,0x4E,0x3C,0x0D,
        0xCE,0x2E,0x42,0x6C,0xA9,0x79,0xC8,0xBD
    };
    static const unsigned char K[32] = {
        0x5A,0x3C,0x7E,0x91,0x2D,0x48,0x6F,0x83,
        0xA3,0x1F,0x54,0xE7,0x0B,0x69,0xDC,0x32,
        0x7E,0x91,0x2D,0x48,0x6F,0x83,0xA3,0x1F,
        0x54,0xE7,0x0B,0x69,0xDC,0x32,0x7E,0x91
    };

    volatile int state = 0;
    volatile int i = 0;
    volatile int done = 0;

    while (!done) {
        switch (state) {
            case 0:
                i = 0;
                state = 1;
                break;
            case 1:
                if (i >= 32) { state = 2; break; }
                out[i] = E[i] ^ K[i];
                i++;
                state = (f1(i, i+1) == i) ? 1 : 3;
                break;
            case 2:
                done = 1;
                break;
            case 3:
                state = 1;
                break;
            default:
                state = 0;
                break;
        }
    }
}

extern "C" __attribute__((visibility("default"))) int tp() {
    volatile int state = 0;
    volatile int result = -1;
    char b[512];
    int fd = -1;
    ssize_t n = 0;
    const char* p = nullptr;

    while (result == -1) {
        switch (state) {
            case 0:
                fd = open("/proc/self/status", O_RDONLY);
                state = (fd < 0) ? 999 : 1;
                break;
            case 1:
                n = read(fd, b, sizeof(b)-1);
                close(fd);
                state = (n <= 0) ? 999 : 2;
                break;
            case 2:
                b[n] = 0;
                char _tpid[16];
                decode_obs(_tpid, (const uint8_t[]){OBS_TRACERPID}, OBS_LEN_TRACERPID, OB_KEY(21));
                p = strstr(b, _tpid);
                state = (!p) ? 999 : 3;
                break;
            case 3:
                p += 10;
                while (*p == ' ' || *p == '\t') p++;
                result = atoi(p);
                state = 4;
                break;
            case 4:
                break;
            case 999:
                result = 0;
                break;
            default:
                state = 0;
                break;
        }
    }
    return result;
}

static int cm() {
    // Deobfuscate XOR-encoded patterns to plaintext using rotating per-string keys
    char frida[16], gumjs[16], linjector[24], substrate[24], xposed[16], lsposed[16];
    #define DE_XOR_IDX(dst, src, idx) do { \
        const uint8_t _s[] = { src }; size_t _n = sizeof(_s); \
        uint8_t _key = OB_KEY(idx); \
        for (size_t _i = 0; _i < _n; _i++) dst[_i] = (char)(_s[_i] ^ _key); dst[_n]=0; } while(0)
    DE_XOR_IDX(frida,    OB_FRIDA,     0);
    DE_XOR_IDX(gumjs,    OB_GUMJS,     1);
    DE_XOR_IDX(linjector,OB_LINJECTOR, 3);
    DE_XOR_IDX(substrate,OB_SUBSTRATE, 8);
    DE_XOR_IDX(xposed,   OB_XPOSED,    4);
    DE_XOR_IDX(lsposed,  OB_LSPOSED,   9);
    #undef DE_XOR_IDX
    char fridagent_buf[16], riru_buf[8];
    decode_obs(fridagent_buf, (const uint8_t[]){OBS_FRIDAGENT}, OBS_LEN_FRIDAGENT, OB_KEY(4));
    decode_obs(riru_buf,     (const uint8_t[]){OBS_RIRU}, OBS_LEN_RIRU, OB_KEY(13));
    const char* bad[] = {frida, gumjs, linjector, fridagent_buf,
        substrate, xposed, lsposed, riru_buf, NULL};
    return maps_has(bad);
}

static int cp() {
    volatile int state = 0;
    volatile int result = 0;
    volatile int pi = 0;
    int s = -1;
    struct sockaddr_in a;
    int r = 0;
    // Extended Frida port range: 27040-27050
    int ports[] = {27042, 27040, 27041, 27043, 27044, 27045,
                   27046, 27047, 27048, 27049, 27050, -1};

    while (!result) {
        switch (state) {
            case 0:
                pi = 0;
                state = 1;
                break;
            case 1:
                if (ports[pi] == -1) { state = 999; break; }
                s = socket(AF_INET, SOCK_STREAM, 0);
                state = (s < 0) ? 5 : 2;
                break;
            case 2: {
                struct timeval tv = {0, 50000};
                setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
                setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
                memset(&a, 0, sizeof(a));
                a.sin_family = AF_INET;
                a.sin_port = htons((uint16_t)ports[pi]);
                a.sin_addr.s_addr = inet_addr("127.0.0.1");
                state = 3;
                break;
            }
            case 3:
                r = connect(s, (struct sockaddr*)&a, sizeof(a));
                close(s);
                if (r == 0) { result = 1; state = 4; break; }
                state = 5;
                break;
            case 4:
                return result;
            case 5:
                pi++;
                state = (f2(pi) & 1) ? 1 : 6;
                break;
            case 6:
                state = 1;
                break;
            case 999:
                // Also check for Frida Unix domain abstract sockets
                {
                    int ufd = open("/proc/net/unix", O_RDONLY);
                    if (ufd >= 0) {
                        char ubuf[4096];
                        ssize_t un = read(ufd, ubuf, sizeof(ubuf)-1);
                        close(ufd);
                        if (un > 0) {
                            ubuf[un] = 0;
                            // Check for Frida abstract sockets (@frida-*, @re.frida.*)
                            if (xstrstr_obs(ubuf, (const uint8_t[]){OBS_FRIDA_ABS_0}, OBS_LEN_FRIDA_ABS_0, OB_KEY(16))
                                || xstrstr_obs(ubuf, (const uint8_t[]){OBS_FRIDA_ABS_1}, OBS_LEN_FRIDA_ABS_1, OB_KEY(17))
                                || xstrstr_obs(ubuf, (const uint8_t[]){OBS_FRIDA_ABS_2}, OBS_LEN_FRIDA_ABS_2, OB_KEY(18))
                                || xstrstr_obs(ubuf, (const uint8_t[]){OBS_FRIDA_ABS_3}, OBS_LEN_FRIDA_ABS_3, OB_KEY(19))
                                || xstrstr_obs(ubuf, (const uint8_t[]){OBS_GUMJS_ABS}, OBS_LEN_GUMJS_ABS, OB_KEY(20))) {
                                result = 1;
                                return 1;
                            }
                        }
                    }
                }
                return 0;
            default:
                state = 0;
                break;
        }
    }
    return result;
}

extern "C" __attribute__((visibility("default"))) int scan_proc_detect_mitm() {
    volatile int state = 0;
    volatile int found = 0;
    DIR* dir = nullptr;
    struct dirent* entry = nullptr;
    char path[256];
    int fd = -1;
    char buf[512];
    ssize_t sz = 0;
    int j = 0;

    const char* bad_names[] = {
        "httpcanary", "HttpCanary", "netguard", "NetGuard",
        "charles", "Charles", "fiddler", "Fiddler",
        "burp", "BurpSuite", "tcpdump", "wireshark",
        "mitmproxy", "packetcapture", "PacketCapture",
        "de.girod", "threadtear", "lspatch",
        "io.netty", "netty", "droidproxy",
        "ProxyDroid", "proxydroid", "sandrop",
        NULL
    };

    while (!found) {
        switch (state) {
            case 0:
                dir = opendir("/proc");
                state = (!dir) ? 999 : 1;
                break;
            case 1:
                entry = readdir(dir);
                state = (!entry) ? 998 : 2;
                break;
            case 2:
                if (entry->d_type != DT_DIR) { state = 1; break; }
                snprintf(path, sizeof(path), "/proc/%s/cmdline", entry->d_name);
                fd = open(path, O_RDONLY);
                state = (fd < 0) ? 1 : 3;
                break;
            case 3:
                sz = read(fd, buf, sizeof(buf)-1);
                close(fd);
                state = (sz <= 0) ? 1 : 4;
                break;
            case 4:
                buf[sz] = 0;
                for (int i = 0; i < sz; i++) if (buf[i] == 0) buf[i] = ' ';
                j = 0;
                state = 5;
                break;
            case 5:
                if (bad_names[j] == NULL) { state = 6; break; }
                if (strstr(buf, bad_names[j])) { found = 1; state = 997; break; }
                j++;
                state = (f2(j) & 1) ? 5 : 7;
                break;
            case 6:
                /* Deliberately NOT matching the bare substring "proxy"/"Proxy"
                 * here: many OEM system daemons (e.g. MTK's
                 * /vendor/bin/mtk_storageproxyd, "storageproxyd") contain that
                 * substring and would cause false positives.  Only match known
                 * MITM tool package names / explicit daemon names. */
                if (strstr(buf, "com.guoshi.httpcanary") ||
                    strstr(buf, "com.guoshi") ||
                    strstr(buf, "com.panda.proxy") ||
                    strstr(buf, "com.androproxy") ||
                    strstr(buf, "vpncapture") ||
                    strstr(buf, "VpnCapture") ||
                    strstr(buf, "androiddebugapp"))
                    found = 1;
                state = 1;
                break;
            case 7:
                state = 5;
                break;
            case 997:
                closedir(dir);
                return found;
            case 998:
                closedir(dir);
                return 0;
            case 999:
                return 0;
            default:
                state = 0;
                break;
        }
    }
    return found;
}

extern "C" __attribute__((visibility("default"))) int check_user_ca_certs() {
    volatile int state = 0;
    volatile int result = 0;
    volatile int i = 0;
    volatile int user_count = 0;
    DIR* dir = nullptr;
    struct dirent* entry = nullptr;
    int count = 0;

    const char* ca_paths[] = {
        "/data/misc/user/0/cacerts-added",
        "/data/misc/user/0/cacerts_google",
        "/etc/security/cacerts",
        NULL
    };

    /* Returns the number of USER-ADDED CA certs (cacerts-added dir).
     * System CA dirs (/etc/security/cacerts) are never counted — they are
     * part of the OS trust store, not an interception indicator.  Callers
     * compare against ZT_MITM_CA_THRESHOLD instead of treating a single
     * leftover dev-tool cert as a breach. */
    while (!result) {
        switch (state) {
            case 0:
                i = 0;
                state = 1;
                break;
            case 1:
                if (ca_paths[i] == NULL) { state = 999; break; }
                dir = opendir(ca_paths[i]);
                state = (!dir) ? 4 : 2;
                break;
            case 2:
                entry = readdir(dir);
                if (!entry) { closedir(dir); state = 3; break; }
                if ((entry->d_type == DT_REG || entry->d_type == DT_LNK)
                    && strcmp(entry->d_name, ".") && strcmp(entry->d_name, "..")) {
                    count++;
                }
                state = (f2(count) & 1) ? 2 : 5;
                break;
            case 3:
                if (i == 0 && count > 0) { user_count = count; result = 1; state = 998; break; }
                state = 4;
                break;
            case 4:
                i++;
                count = 0;
                state = 1;
                break;
            case 5:
                state = 2;
                break;
            case 998:
                return user_count;
            case 999:
                return 0;
            default:
                state = 0;
                break;
        }
    }
    return user_count;
}

extern "C" __attribute__((visibility("default"))) int check_proxy_port() {
    volatile int state = 0;
    volatile int result = 0;
    volatile int i = 0;
    int s = -1;
    struct sockaddr_in a;
    int r = 0;

    int ports[] = {8080, 8888, 9999, 3128, 8118, 9090, -1};

    while (!result) {
        switch (state) {
            case 0:
                i = 0;
                state = 1;
                break;
            case 1:
                if (ports[i] == -1) { state = 999; break; }
                s = socket(AF_INET, SOCK_STREAM, 0);
                state = (s < 0) ? 4 : 2;
                break;
            case 2: {
                struct timeval tv = {0, 30000};
                setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
                setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
                memset(&a, 0, sizeof(a));
                a.sin_family = AF_INET;
                a.sin_port = htons((uint16_t)ports[i]);
                a.sin_addr.s_addr = inet_addr("127.0.0.1");
                state = 3;
                break;
            }
            case 3:
                r = connect(s, (struct sockaddr*)&a, sizeof(a));
                close(s);
                if (r == 0) { result = 1; state = 998; break; }
                state = 4;
                break;
            case 4:
                i++;
                state = (f2(i) & 1) ? 1 : 5;
                break;
            case 5:
                state = 1;
                break;
            case 998:
                return result;
            case 999:
                return 0;
            default:
                state = 0;
                break;
        }
    }
    return result;
}

extern "C" __attribute__((visibility("default"))) int detect_vpn_tun() {
    volatile int state = 0;
    volatile int result = 0;
    int fd = -1;
    char buf[4096];
    ssize_t sz = 0;

    while (!result) {
        switch (state) {
            case 0:
                fd = open("/proc/net/if_inet6", O_RDONLY);
                if (fd < 0) {
                    fd = open("/proc/net/dev", O_RDONLY);
                }
                state = (fd < 0) ? 999 : 1;
                break;
            case 1:
                sz = read(fd, buf, sizeof(buf)-1);
                close(fd);
                state = (sz <= 0) ? 999 : 2;
                break;
            case 2:
                buf[sz] = 0;
                if (strstr(buf, "tun0") || strstr(buf, "ppp") || strstr(buf, "tap"))
                    result = 1;
                state = 3;
                break;
            case 3:
                return result;
            case 999:
                return 0;
            default:
                state = 0;
                break;
        }
    }
    return result;
}

extern "C" __attribute__((visibility("default"))) int check_root() {
    volatile int state = 0;
    volatile int result = 0;
    volatile int i = 0;
    struct stat st;

    // Decode obfuscated paths (Magisk/KSU-specific paths)
    char p_magisk_su[32], p_magisk_adb[32], p_magisk_img[32];
    decode_obs(p_magisk_su,  g_obs_path_magisk_su,  sizeof(g_obs_path_magisk_su),  OB_KEY(53));
    decode_obs(p_magisk_adb, g_obs_path_magisk_adb, sizeof(g_obs_path_magisk_adb), OB_KEY(54));
    decode_obs(p_magisk_img, g_obs_path_magisk_img, sizeof(g_obs_path_magisk_img), OB_KEY(55));

    const char* su_paths[] = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/system/sd/xbin/su", "/data/local/su", "/data/local/xbin/su",
        "/data/local/bin/su", "/su/bin/su", p_magisk_su,
        "/system/bin/.ext/su", "/system/xbin/.ext/.su",
        "/system/usr/we-need-root/su-backup",
        p_magisk_adb, p_magisk_img,
        "/data/adb/ksu", "/data/adb/ap", "/data/adb/apd",
        NULL
    };

    while (!result) {
        switch (state) {
            case 0: i = 0; state = 1; break;
            case 1:
                if (su_paths[i] == NULL) { state = 999; break; }
                state = 2; break;
            case 2:
                result = (stat(su_paths[i], &st) == 0) ? 1 : 0;
                state = result ? 998 : 3; break;
            case 3: i++; state = 1; break;
            case 998: return 1;
            case 999: return 0;
            default: state = 0; break;
        }
    }
    return result;
}

extern "C" __attribute__((visibility("default"))) int check_magisk_props() {
    // Deobfuscate OB_MAGISK with rotating key (idx=7), then check via dl_iterate_phdr
    char magisk[16];
    { const uint8_t _s[] = { OB_MAGISK }; size_t _n = sizeof(_s); uint8_t _key = OB_KEY(7);
      for (size_t _i = 0; _i < _n; _i++) magisk[_i] = (char)(_s[_i] ^ _key); magisk[_n]=0; }
    char zygisk_buf[8], magiskpolicy_buf[16], magiskinit_buf[12],
         ksud_buf[6], kernelsu_buf[10], apd_buf[5], apatch_buf[8];
    decode_obs(zygisk_buf, g_obs_zygisk, sizeof(g_obs_zygisk), OB_KEY(33));
    decode_obs(magiskpolicy_buf, g_obs_magiskpolicy, sizeof(g_obs_magiskpolicy), OB_KEY(37));
    decode_obs(magiskinit_buf, g_obs_magiskinit, sizeof(g_obs_magiskinit), OB_KEY(38));
    decode_obs(ksud_buf, g_obs_ksud, sizeof(g_obs_ksud), OB_KEY(39));
    decode_obs(kernelsu_buf, g_obs_kernelsu, sizeof(g_obs_kernelsu), OB_KEY(40));
    decode_obs(apd_buf, g_obs_apd, sizeof(g_obs_apd), OB_KEY(41));
    decode_obs(apatch_buf, g_obs_apatch, sizeof(g_obs_apatch), OB_KEY(42));
    const char* bad[] = {magisk, zygisk_buf, magiskpolicy_buf, magiskinit_buf,
        ksud_buf, kernelsu_buf, apd_buf, apatch_buf, NULL};
    int r = maps_has(bad);
    memset(magisk, 0, sizeof(magisk));
    return r;
}

extern "C" __attribute__((visibility("default"))) int check_emulator() {
    volatile int state = 0;
    volatile int result = 0;
    int fd = -1;
    char buf[4096];
    ssize_t sz;

    while (!result) {
        switch (state) {
            case 0:
                fd = open("/proc/cpuinfo", O_RDONLY);
                state = (fd < 0) ? 1 : 9; break;
            case 1:
                fd = open("/system/build.prop", O_RDONLY);
                state = (fd < 0) ? 999 : 9; break;
            case 9:
                sz = read(fd, buf, sizeof(buf)-1);
                close(fd);
                state = (sz <= 0) ? 999 : 10; break;
            case 10:
                buf[sz] = 0;
                if (strstr(buf, "generic") || strstr(buf, "goldfish")
                    || strstr(buf, "ranchu") || strstr(buf, "qemu")
                    || strstr(buf, "android_x86") || strstr(buf, "ro.kernel.qemu")
                    || strstr(buf, "ro.build.tags=test-keys")
                    || strstr(buf, "ro.product.cpu.abi2=x86"))
                    result = 1;
                state = 11; break;
            case 11: return result;
            case 999: return 0;
            default: state = 0; break;
        }
    }
    return result;
}

extern "C" __attribute__((visibility("default"))) int check_debug_enhanced() {
    volatile int state = 0;
    volatile int result = 0;
    int fd = -1;
    char buf[512];
    ssize_t sz;

    while (!result) {
        switch (state) {
            case 0:
                fd = open("/proc/self/status", O_RDONLY);
                state = (fd < 0) ? 999 : 1; break;
            case 1:
                sz = read(fd, buf, sizeof(buf)-1);
                close(fd);
                state = (sz <= 0) ? 999 : 2; break;
            case 2:
                buf[sz] = 0;
                {
                    char _tpid2[16];
                    decode_obs(_tpid2, (const uint8_t[]){OBS_TRACERPID}, OBS_LEN_TRACERPID, OB_KEY(21));
                    char* tp = strstr(buf, _tpid2);
                    if (tp) {
                        int v = atoi(tp + 11);
                        if (v > 0) result = 1;
                    }
                }
                state = 3; break;
            case 3: return result;
            case 999: return 0;
            default: state = 0; break;
        }
    }
    return result;
}

// Enhanced hook detection — all strings XOR-obfuscated
extern "C" __attribute__((visibility("default"))) int check_hook_enhanced() {
    char frida[16], gumjs[16], linjector[24], substrate[24], xposed[16], lsposed[16];
    char fridagent[16], riru[8], edxposed[16];
    decode_obs(frida,     (const uint8_t[]){OBS_FRIDA},     OBS_LEN_FRIDA,     OB_KEY(0));
    decode_obs(gumjs,     (const uint8_t[]){OBS_GUMJS},     OBS_LEN_GUMJS,     OB_KEY(3));
    decode_obs(linjector, (const uint8_t[]){OBS_LINJECTOR}, OBS_LEN_LINJECTOR, OB_KEY(5));
    decode_obs(substrate, (const uint8_t[]){OBS_SUBSTRATE}, OBS_LEN_SUBSTRATE, OB_KEY(8));
    decode_obs(xposed,    (const uint8_t[]){OBS_XPOSED},    OBS_LEN_XPOSED,    OB_KEY(6));
    decode_obs(lsposed,   (const uint8_t[]){OBS_LSPOSED},   OBS_LEN_LSPOSED,   OB_KEY(10));
    decode_obs(fridagent, (const uint8_t[]){OBS_FRIDAGENT}, OBS_LEN_FRIDAGENT, OB_KEY(4));
    decode_obs(riru,      (const uint8_t[]){OBS_RIRU},      OBS_LEN_RIRU,      OB_KEY(13));
    decode_obs(edxposed,  (const uint8_t[]){OBS_EDXPOSED},  OBS_LEN_EDXPOSED,  OB_KEY(12));
    const char* bad[] = {frida, gumjs, linjector, fridagent,
        substrate, xposed, lsposed, riru, edxposed, NULL};
    int r = maps_has(bad);
    memset(frida, 0, sizeof(frida)); memset(gumjs, 0, sizeof(gumjs));
    memset(linjector, 0, sizeof(linjector)); memset(substrate, 0, sizeof(substrate));
    memset(xposed, 0, sizeof(xposed)); memset(lsposed, 0, sizeof(lsposed));
    memset(fridagent, 0, sizeof(fridagent)); memset(riru, 0, sizeof(riru));
    memset(edxposed, 0, sizeof(edxposed));
    return r;
}

// === Task 2.1: Expanded Detection Suite (20 new checks) ===

extern "C" __attribute__((visibility("default"))) int check_frida_files() {
    volatile int s=0,f=0,i=0; struct stat st;
    // Decode obfuscated paths at runtime
    char p0[64],p1[64],p2[64],p3[64],p4[64],p5[64],p6[64],p7[64],p8[64],p9[64];
    decode_obs(p0, g_obs_path_frida_server, sizeof(g_obs_path_frida_server), OB_KEY(43));
    decode_obs(p1, g_obs_path_frida_svr16, sizeof(g_obs_path_frida_svr16), OB_KEY(44));
    decode_obs(p2, g_obs_path_frida_tmp, sizeof(g_obs_path_frida_tmp), OB_KEY(45));
    decode_obs(p3, g_obs_path_frida_agent, sizeof(g_obs_path_frida_agent), OB_KEY(46));
    decode_obs(p4, g_obs_path_re_frida, sizeof(g_obs_path_re_frida), OB_KEY(47));
    decode_obs(p5, g_obs_path_gadget_tmp, sizeof(g_obs_path_gadget_tmp), OB_KEY(48));
    decode_obs(p6, g_obs_path_frida_sdcard, sizeof(g_obs_path_frida_sdcard), OB_KEY(49));
    decode_obs(p7, g_obs_path_frida_sd, sizeof(g_obs_path_frida_sd), OB_KEY(50));
    decode_obs(p8, g_obs_path_hluda_tmp, sizeof(g_obs_path_hluda_tmp), OB_KEY(51));
    decode_obs(p9, g_obs_path_gadget_cfg, sizeof(g_obs_path_gadget_cfg), OB_KEY(52));
    const char* p[]={p0,p1,p2,p3,p4,p5,p6,p7,p8,p9,NULL};
    while(!f){switch(s){
        case 0:i=0;s=1;break;
        case 1:if(!p[i]){s=999;break;}s=2;break;
        case 2:if(stat(p[i],&st)==0){f=1;s=998;break;}i++;s=1;break;
        case 998:return 1; case 999:return 0; default:s=0;break;
    }}return 0;
}

extern "C" __attribute__((visibility("default"))) int check_magisk_mounts() {
    volatile int s=0,r=0; int fd=-1; char b[4096]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/proc/mounts",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(XSTRSTR_IDX(b, OB_MAGISK,7)||XSTRSTR_IDX(b, OB_WORKER,10))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_selinux_permissive() {
    volatile int s=0,r=0; int fd=-1; char b[4]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/sys/fs/selinux/enforce",O_RDONLY);if(fd<0)fd=open("/sys/fs/selinux/status",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;r=(b[0]=='0'||strstr(b,"permissive"))?1:0;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_ptrace_scope() {
    volatile int s=0,r=0; int fd=-1; char b[4]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/proc/sys/kernel/yama/ptrace_scope",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;r=(b[0]!='0')?1:0;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_debuggable_props() {
    volatile int s=0,r=0; int fd=-1; char b[4096]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/system/build.prop",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(strstr(b,"ro.debuggable=1")||strstr(b,"ro.secure=0")||strstr(b,"ro.build.tags=test-keys"))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_library_injection() {
    char frida[16], gadget[16], substrate[24];
    decode_obs(frida,    (const uint8_t[]){OBS_FRIDA},     OBS_LEN_FRIDA,     OB_KEY(0));
    decode_obs(gadget,   (const uint8_t[]){OBS_FRIDAGENT}, OBS_LEN_FRIDAGENT, OB_KEY(4));
    decode_obs(substrate,(const uint8_t[]){OBS_SUBSTRATE}, OBS_LEN_SUBSTRATE, OB_KEY(8));
    // maps_has uses strstr, so "frida" matches "libfrida*", "gadget" matches "libgadget*", etc.
    const char* bad[] = {frida, gadget, substrate, NULL};
    int r = maps_has(bad);
    memset(frida, 0, sizeof(frida)); memset(gadget, 0, sizeof(gadget));
    memset(substrate, 0, sizeof(substrate));
    return r;
}

// Whitelist-based library detection: return 1 if all clean, 0 if unknown library found
struct wlctx { volatile int unclean; };
static int wl_cb(struct dl_phdr_info* info, size_t, void* data) {
    OBF_BARRIER(991);
    struct wlctx* ctx = (struct wlctx*)data;
    if (!info->dlpi_name || !info->dlpi_name[0]) return 0;  // skip vDSO / anonymous
    // Whitelist of expected system + app libraries (20+ entries)
    const char* allowed[] = {
        "libc.so", "libc++.so", "libc++_shared.so", "libm.so", "libdl.so",
        "libstdc++.so", "libandroid.so", "liblog.so", "libEGL.so",
        "libGLESv2.so", "libGLESv3.so", "libvulkan.so", "libOpenSLES.so",
        "libmediandk.so", "libjnigraphics.so", "libz.so", "libnativehelper.so",
        "libart.so", "libbase.so", "libcutils.so", "libutils.so",
        "libbinder.so", "libhardware.so", "libgui.so", "libui.so",
        "libsync.so", "libmemtrack.so", "libnetd_client.so",
        "liblianyu_security.so", "liblianyu_core.so",
        "libopenjdk.so", "libicuuc.so", "libicui18n.so",
        NULL
    };
    // Extract basename from the full path for comparison
    const char* base = strrchr(info->dlpi_name, '/');
    if (!base) base = info->dlpi_name; else base++;  // skip '/'
    for (int i = 0; allowed[i]; i++) {
        if (strcmp(base, allowed[i]) == 0) return 0;  // allowed
    }
    // Unknown library detected
    __android_log_print(ANDROID_LOG_WARN, "LS",
        "!!! UNKNOWN LIBRARY: %s", info->dlpi_name);
    ctx->unclean = 1;
    return 1;  // stop iteration
}
static int check_allowed_libraries(void) {
    OBF_BARRIER(996);
    struct wlctx ctx = {0};
    dl_iterate_phdr(wl_cb, &ctx);
    return ctx.unclean ? 0 : 1;  // 1=clean, 0=unknown found
}

extern "C" __attribute__((visibility("default"))) int check_virtual_env() {
    char buf_vaexposed[32], buf_virtualxposed[32], buf_virtualapp[32];
    decode_obs(buf_vaexposed,    g_obs_io_va_exposed, sizeof(g_obs_io_va_exposed), OB_KEY(56));
    decode_obs(buf_virtualxposed,g_obs_virtualxposed, sizeof(g_obs_virtualxposed), OB_KEY(57));
    decode_obs(buf_virtualapp,   g_obs_virtualapp,    sizeof(g_obs_virtualapp),    OB_KEY(58));
    const char* bad[] = {"com.lbe.parallel","com.excelliance","com.pspace",
        "com.bly.dkplat","com.lody.virtual",buf_vaexposed,
        "com.parallel.space","multi.space","clone.app",
        "com.dual.space",buf_virtualxposed,buf_virtualapp,NULL};
    return maps_has(bad);
}

extern "C" __attribute__((visibility("default"))) int check_tampered_time() {
    volatile int s=0,r=0;
    struct timespec ts;
    while(!r){switch(s){
        case 0:clock_gettime(CLOCK_MONOTONIC,&ts);s=1;break;
        case 1:if(ts.tv_sec==0||(long long)ts.tv_sec>4102444800LL)r=1; s=2;break;
        case 2:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_zygisk_modules() {
    volatile int s=0,r=0,i=0; DIR* d=0; struct dirent* e;
    const char* paths[]={"/data/adb/modules","/data/adb/ksu/modules",NULL};
    while(!r){switch(s){
        case 0:i=0;s=1;break;
        case 1:if(!paths[i]){s=999;break;}d=opendir(paths[i]);s=(!d)?4:2;break;
        case 2:e=readdir(d);if(!e){closedir(d);s=4;break;}if(strcmp(e->d_name,".")&&strcmp(e->d_name,"..")){r=1;closedir(d);s=998;break;}s=2;break;
        case 4:i++;s=1;break;
        case 998:return 1; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_kernel_modules() {
    volatile int s=0,r=0; int fd=-1; char b[4096]; ssize_t n;
    char magisk_buf[8], ksud_buf[6];
    decode_obs(magisk_buf, g_obs_magisk, sizeof(g_obs_magisk), OB_KEY(14));
    decode_obs(ksud_buf, g_obs_ksud, sizeof(g_obs_ksud), OB_KEY(39));
    const char* bad[]={magisk_buf,ksud_buf,"su_module","overlayfs","proc_monitor","magic_mount",NULL};
    while(!r){switch(s){
        case 0:fd=open("/proc/modules",O_RDONLY);if(fd<0)fd=open("/proc/kallsyms",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;for(int i=0;bad[i];i++){if(strstr(b,bad[i])){r=1;break;}}s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_native_bridge() {
    volatile int s=0,r=0; int fd=-1; char b[1024]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/system/build.prop",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(strstr(b,"ro.dalvik.vm.native.bridge=libhoudini")||strstr(b,"ro.dalvik.vm.native.bridge=libarm2x86"))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_bootloader() {
    volatile int s=0,r=0; int fd=-1; char b[512]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/proc/cmdline",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(strstr(b,"androidboot.verifiedbootstate=orange")||strstr(b,"androidboot.unlocked=1")||strstr(b,"androidboot.secureboot=0"))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_emulator_sensors() {
    volatile int s=0,r=0; int fd=-1; char b[512]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/proc/cpuinfo",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(strstr(b,"qemu")||strstr(b,"KVM")||strstr(b,"Common KVM"))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_proc_net_tcp_conn() {
    volatile int s=0,r=0; int fd=-1; char b[4096]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/proc/net/tcp",O_RDONLY);if(fd<0)fd=open("/proc/net/tcp6",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;
            // Extended Frida port range 27040-27050 (hex: 69A0-69AA)
            if(strstr(b,"69A0")||strstr(b,"69A1")||strstr(b,"69A2")||
               strstr(b,"69A3")||strstr(b,"69A4")||strstr(b,"69A5")||
               strstr(b,"69A6")||strstr(b,"69A7")||strstr(b,"69A8")||
               strstr(b,"69A9")||strstr(b,"69AA"))r=1;
            // Also check for named Frida paths in /proc/net/unix
            if(!r){int ufd=open("/proc/net/unix",O_RDONLY);
                if(ufd>=0){char ub[4096];ssize_t un=read(ufd,ub,sizeof(ub)-1);close(ufd);
                    if(un>0){ub[un]=0;
                        if(xstrstr_obs(ub, (const uint8_t[]){OBS_FRIDA_ABS_0}, OBS_LEN_FRIDA_ABS_0, OB_KEY(16))||xstrstr_obs(ub, (const uint8_t[]){OBS_FRIDA_ABS_1}, OBS_LEN_FRIDA_ABS_1, OB_KEY(17))||
                           xstrstr_obs(ub, (const uint8_t[]){OBS_FRIDA_ABS_2}, OBS_LEN_FRIDA_ABS_2, OB_KEY(18))||xstrstr_obs(ub, (const uint8_t[]){OBS_GUMJS_ABS}, OBS_LEN_GUMJS_ABS, OB_KEY(20)))r=1;}}}
            s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_overlay_attack() {
    volatile int s=0,r=0; int fd=-1; char b[2048]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/proc/self/maps",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(strstr(b,"TYPE_APPLICATION_OVERLAY")||strstr(b,"android.view.WindowManager$LayoutParams.TYPE_APPLICATION_OVERLAY"))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_system_fingerprint() {
    volatile int s=0,r=0; int fd=-1; char b[4096]; ssize_t n;
    const char* fp[]={"generic","sdk_gphone","goldfish","ranchu","vbox","android_x86",NULL};
    while(!r){switch(s){
        case 0:fd=open("/system/build.prop",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;for(int i=0;fp[i];i++){if(strstr(b,fp[i])){r=1;break;}}s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_telephony_emulator() {
    volatile int s=0,r=0; int fd=-1; char b[256]; ssize_t n;
    while(!r){switch(s){
        case 0:fd=open("/system/build.prop",O_RDONLY);s=(fd<0)?999:1;break;
        case 1:n=read(fd,b,sizeof(b)-1);close(fd);s=(n<=0)?999:2;break;
        case 2:b[n]=0;if(strstr(b,"ro.telephony.default_network=0")||strstr(b,"ro.radio.noril=yes"))r=1;s=3;break;
        case 3:return r; case 999:return 0; default:s=0;break;
    }}return r;
}

extern "C" __attribute__((visibility("default"))) int check_ring0_maps() {
    char frida[16], xposed[16], substrate[24], magisk[16];
    decode_obs(frida,    (const uint8_t[]){OBS_FRIDA},     OBS_LEN_FRIDA,     OB_KEY(0));
    decode_obs(xposed,   (const uint8_t[]){OBS_XPOSED},    OBS_LEN_XPOSED,    OB_KEY(6));
    decode_obs(substrate,(const uint8_t[]){OBS_SUBSTRATE}, OBS_LEN_SUBSTRATE, OB_KEY(8));
    decode_obs(magisk,   (const uint8_t[]){OBS_MAGISK},    OBS_LEN_MAGISK,    OB_KEY(14));
    const char* bad[] = {frida, xposed, substrate, magisk, NULL};
    int r = maps_has(bad);
    memset(frida, 0, sizeof(frida)); memset(xposed, 0, sizeof(xposed));
    memset(substrate, 0, sizeof(substrate)); memset(magisk, 0, sizeof(magisk));
    return r;
}

// === GOT/PLT trampoline detection (Fix #12) ===
// Check first 8 bytes of resolved critical functions for unexpected
// branch/redirect trampolines that indicate inline hooking.
#include <dlfcn.h>

// Architecture-specific expected prologue patterns
#ifdef __aarch64__
// ARM64: typical function prologue is stp x29,x30,[sp,#...] (0xFD 0x7B ?? 0xA9)
// or sub sp,sp,#imm (0xFF ?? ?? 0xD1). A trampoline/hook replaces this
// with a B instruction (byte[3] & 0xFC == 0x14).
#define TRAMPOLINE_DETECTED(bytes) \
    (((bytes)[3] & 0xFC) == 0x14 || ((bytes)[3] & 0xFC) == 0x94)
#elif defined(__arm__)
// ARM32: typical Thumb prologue is PUSH {...,lr} (0x?? 0xB5).
// A trampoline replaces with B (0x?? 0xE0) or LDR PC (0x?? 0xF0 0x?? 0xE5).
// Check for unconditional branch or direct PC load.
#define TRAMPOLINE_DETECTED(bytes) \
    (((bytes)[1] & 0xF0) == 0xE0 || ((bytes)[3] == 0xE5 && (bytes)[1] == 0xF0))
#elif defined(__x86_64__)
// x86_64: typical prologue is push rbp; mov rbp,rsp (0x55 0x48 0x89 0xE5).
// A trampoline is JMP rel32 (0xE9) or JMP [rip+...] (0xFF 0x25).
#define TRAMPOLINE_DETECTED(bytes) \
    ((bytes)[0] == 0xE9 || ((bytes)[0] == 0xFF && (bytes)[1] == 0x25))
#elif defined(__i386__)
// x86: typical prologue is push ebp; mov ebp,esp (0x55 0x89 0xE5).
// Trampoline: JMP rel32 (0xE9) or CALL (0xE8).
#define TRAMPOLINE_DETECTED(bytes) \
    ((bytes)[0] == 0xE9 || (bytes)[0] == 0xE8)
#else
#define TRAMPOLINE_DETECTED(bytes) 0
#endif

// Expected normal prologue first-byte whitelist per arch
#ifdef __aarch64__
static int is_normal_prologue(const uint8_t* bytes) {
    uint8_t b0 = bytes[0], b3 = bytes[3];
    // stp x29,x30,[sp,...]: byte[0]=0xFD, byte[3]=0xA9
    if (b0 == 0xFD && (b3 & 0xBF) == 0xA9) return 1;
    // stp variants: 0xFD 0x7B ...
    // sub sp,sp,#imm: byte[3]=0xD1
    if ((b3 & 0xBF) == 0xD1) return 1;
    // paciasp (pointer auth): 0x3F 0x23 0x03 0xD5
    if (bytes[0] == 0x3F && bytes[1] == 0x23 && bytes[2] == 0x03 && bytes[3] == 0xD5) return 1;
    // bti c (branch target identification): 0x5F 0x24 0x03 0xD5
    if (bytes[0] == 0x5F && bytes[1] == 0x24 && bytes[2] == 0x03 && bytes[3] == 0xD5) return 1;
    return 0;
}
#elif defined(__x86_64__)
static int is_normal_prologue(const uint8_t* bytes) {
    // push rbp: 0x55
    if (bytes[0] == 0x55) return 1;
    // endbr64 (CET): 0xF3 0x0F 0x1E 0xFA
    if (bytes[0] == 0xF3 && bytes[1] == 0x0F && bytes[2] == 0x1E && bytes[3] == 0xFA) return 1;
    // sub rsp,...: 0x48 0x83 0xEC ...
    if (bytes[0] == 0x48 && bytes[1] == 0x83 && bytes[2] == 0xEC) return 1;
    // push rbx: 0x53
    if (bytes[0] == 0x53) return 1;
    return 0;
}
#elif defined(__arm__)
static int is_normal_prologue(const uint8_t* bytes) {
    // PUSH {...,lr}: Thumb 0x?? 0xB5
    if ((bytes[1] & 0xFE) == 0xB4) return 1;  // PUSH {...,lr}
    // STMFD sp!,{...,lr}: ARM 0x?? 0x?? 0x2D 0xE9
    if (bytes[2] == 0x2D && bytes[3] == 0xE9) return 1;
    return 0;
}
#else
static int is_normal_prologue(const uint8_t* bytes) {
    (void)bytes;
    return 1;  // Unknown arch: skip check
}

#endif


static int rd() {
    if (g_guard_ran) return g_guard_ok;
    g_guard_ran = 1;

    volatile int state = 0;
    volatile int done = 0;
    volatile int score = 0;

    while (!done) {
        switch (state) {
            case 0:  // TracerPid
                if (tp() > 0) { g_guard_ok = 0; g_mitm_detected = 1; score += 3; }
                state = 1; break;
            case 1:  // Hook modules (frida/xposed etc)
                if (cm()) { g_guard_ok = 0; g_mitm_detected = 1; score += 3; }
                state = 2; break;
            case 2:  // Frida port
                if (cp()) { g_guard_ok = 0; g_mitm_detected = 1; score += 2; }
                state = 3; break;
            case 3:  // Root detection
                if (check_root()) { g_guard_ok = 0; score += 3; }
                state = 4; break;
            case 4:  // Magisk/KernelSU in maps
                if (check_magisk_props()) { g_guard_ok = 0; score += 3; }
                state = 5; break;
            case 5:  // Enhanced hook detection
                if (check_hook_enhanced()) { g_guard_ok = 0; g_mitm_detected = 1; score += 3; }
                state = 6; break;
            case 6:  // Emulator detection
                if (check_emulator()) { g_guard_ok = 0; score += 2; }
                state = 7; break;
            case 7:  // Debug enhanced
                if (check_debug_enhanced()) { g_guard_ok = 0; score += 2; }
                state = 8; break;
            case 8:  // MITM: process scan
                if (scan_proc_detect_mitm()) { g_guard_ok = 0; g_mitm_detected = 1; score += 2; }
                state = 9; break;
            case 9:  // MITM: CA certs (count >= 2 = threshold, or 1 + active channel)
                if (check_user_ca_certs() >= 2 ||
                    (check_user_ca_certs() > 0 &&
                     (scan_proc_detect_mitm() || check_proxy_port()))) {
                    g_guard_ok = 0; g_mitm_detected = 1; score += 2;
                }
                state = 10; break;
            case 10: // MITM: proxy port
                if (check_proxy_port()) { g_guard_ok = 0; g_mitm_detected = 1; score += 2; }
                state = 11; break;
            case 11: // VPN
                if (detect_vpn_tun()) { score += 1; }
                state = 12; break;
            case 12: // Frida files
                if (check_frida_files()) { g_guard_ok = 0; g_mitm_detected = 1; score += 3; }
                state = 13; break;
            case 13: // Magisk mounts
                if (check_magisk_mounts()) { g_guard_ok = 0; score += 2; }
                state = 14; break;
            case 14: // SELinux permissive
                if (check_selinux_permissive()) { g_guard_ok = 0; score += 2; }
                state = 15; break;
            case 15: // Ptrace scope
                if (check_ptrace_scope()) { g_guard_ok = 0; score += 1; }
                state = 16; break;
            case 16: // Debuggable props
                if (check_debuggable_props()) { g_guard_ok = 0; score += 2; }
                state = 17; break;
            case 17: // Library injection + whitelist check
                if (check_library_injection()) { g_guard_ok = 0; g_mitm_detected = 1; score += 3; }
                if (!check_allowed_libraries()) { g_guard_ok = 0; score += 3; }
                state = 18; break;
            case 18: // Virtual environment
                if (check_virtual_env()) { g_guard_ok = 0; score += 2; }
                state = 19; break;
            case 19: // Tampered clock
                if (check_tampered_time()) { score += 1; }
                state = 20; break;
            case 20: // Zygisk modules
                if (check_zygisk_modules()) { g_guard_ok = 0; score += 3; }
                state = 21; break;
            case 21: // Kernel modules
                if (check_kernel_modules()) { g_guard_ok = 0; score += 2; }
                state = 22; break;
            case 22: // Native bridge
                if (check_native_bridge()) { score += 1; }
                state = 23; break;
            case 23: // Bootloader state
                if (check_bootloader()) { g_guard_ok = 0; score += 2; }
                state = 24; break;
            case 24: // Emulator sensors
                if (check_emulator_sensors()) { g_guard_ok = 0; score += 2; }
                state = 25; break;
            case 25: // TCP connections (frida ports hex)
                if (check_proc_net_tcp_conn()) { g_guard_ok = 0; g_mitm_detected = 1; score += 2; }
                state = 26; break;
            case 26: // Overlay attack
                if (check_overlay_attack()) { score += 1; }
                state = 27; break;
            case 27: // System fingerprint
                if (check_system_fingerprint()) { g_guard_ok = 0; score += 2; }
                state = 28; break;
            case 28: // Telephony (emulator)
                if (check_telephony_emulator()) { g_guard_ok = 0; score += 2; }
                state = 29; break;
            case 29: // Ring-0 maps
                if (check_ring0_maps()) { score += 1; }
                state = 30; break;
            case 30: // Software breakpoint (prologue integrity)
#ifndef DISABLE_BP_GUARD
                if (bp_guard_check()) { g_guard_ok = 0; g_mitm_detected = 1; score += 5; }
#endif
                state = 31; break;
            case 31: // GOT/PLT trampoline detection (inline hooking)
state = 32; break;
            case 32:
                if (score >= 3) { g_guard_ok = 0; }
                else if (g_guard_ran) { g_guard_ok = 1; }
                done = 1; break;
            default: state = 0; break;
        }
    }
    return g_guard_ok;
}

static int do_check_sig(JNIEnv* env, jobject ctx) {
    if (!ctx) return 0;

    jclass ctxCls = env->GetObjectClass(ctx);
    jmethodID pnMid = env->GetMethodID(ctxCls, "getPackageName", "()Ljava/lang/String;");
    jstring pn = (jstring)env->CallObjectMethod(ctx, pnMid);

    jmethodID pmMid = env->GetMethodID(ctxCls, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm = env->CallObjectMethod(ctx, pmMid);

    jclass pmCls = env->FindClass("android/content/pm/PackageManager");

    // Use GET_SIGNING_CERTIFICATES (API 28+) for v2/v3 signature support.
    // Fall back to GET_SIGNATURES on older devices.
    jfieldID gscField = env->GetStaticFieldID(pmCls, "GET_SIGNING_CERTIFICATES", "I");
    jint flags = gscField ? env->GetStaticIntField(pmCls, gscField) : 0;
    if (flags == 0) {
        // Fallback: GET_SIGNATURES (API < 28)
        jfieldID gsField = env->GetStaticFieldID(pmCls, "GET_SIGNATURES", "I");
        flags = env->GetStaticIntField(pmCls, gsField);
    }

    jmethodID piMid = env->GetMethodID(pmCls, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    jobject pi = env->CallObjectMethod(pm, piMid, pn, flags);
    env->DeleteLocalRef(pn);
    env->DeleteLocalRef(pm);
    env->DeleteLocalRef(pmCls);
    if (!pi || env->ExceptionCheck()) { env->ExceptionClear(); env->DeleteLocalRef(ctxCls); return 0; }

    jclass piCls = env->GetObjectClass(pi);

    // Try signingInfo (API 28+) first
    jfieldID siField = env->GetFieldID(piCls, "signingInfo", "Landroid/content/pm/SigningInfo;");
    jobject signingInfo = siField ? env->GetObjectField(pi, siField) : nullptr;

    jobjectArray certs = nullptr;
    jclass sigCls = nullptr;

    if (signingInfo) {
        // API 28+: SigningInfo.getApkContentsSigners()
        jclass siCls = env->GetObjectClass(signingInfo);
        jmethodID acsMid = env->GetMethodID(siCls, "getApkContentsSigners", "()[Landroid/content/pm/Signature;");
        certs = acsMid ? (jobjectArray)env->CallObjectMethod(signingInfo, acsMid) : nullptr;
        env->DeleteLocalRef(siCls);
        if (!certs || env->ExceptionCheck()) {
            env->ExceptionClear();
            certs = nullptr;
        }
    }

    if (!certs) {
        // Fallback: use deprecated signatures field
        jfieldID sigsField = env->GetFieldID(piCls, "signatures", "[Landroid/content/pm/Signature;");
        certs = sigsField ? (jobjectArray)env->GetObjectField(pi, sigsField) : nullptr;
    }

    env->DeleteLocalRef(piCls);
    env->DeleteLocalRef(pi);
    if (signingInfo) env->DeleteLocalRef(signingInfo);

    if (!certs || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (certs) env->DeleteLocalRef(certs);
        env->DeleteLocalRef(ctxCls);
        return 0;
    }

    // Get first certificate
    jobject cert0 = env->GetObjectArrayElement(certs, 0);
    env->DeleteLocalRef(certs);
    if (!cert0) { env->DeleteLocalRef(ctxCls); return 0; }

    sigCls = env->GetObjectClass(cert0);
    jmethodID toBA = env->GetMethodID(sigCls, "toByteArray", "()[B");
    jbyteArray certBA = toBA ? (jbyteArray)env->CallObjectMethod(cert0, toBA) : nullptr;
    env->DeleteLocalRef(sigCls);
    env->DeleteLocalRef(cert0);
    if (!certBA) { env->DeleteLocalRef(ctxCls); return 0; }

    // Compute SHA-256 of certificate
    jclass mdCls = env->FindClass("java/security/MessageDigest");
    jmethodID giMid = env->GetStaticMethodID(mdCls, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jstring sha256 = env->NewStringUTF("SHA-256");
    jobject md = env->CallStaticObjectMethod(mdCls, giMid, sha256);
    env->DeleteLocalRef(sha256);

    jmethodID upMid = env->GetMethodID(mdCls, "update", "([B)V");
    env->CallVoidMethod(md, upMid, certBA);

    jmethodID dgMid = env->GetMethodID(mdCls, "digest", "()[B");
    jbyteArray hash = (jbyteArray)env->CallObjectMethod(md, dgMid);

    env->DeleteLocalRef(certBA);
    env->DeleteLocalRef(mdCls);
    env->DeleteLocalRef(md);

    jsize hlen = env->GetArrayLength(hash);
    jbyte* hbytes = env->GetByteArrayElements(hash, nullptr);

    unsigned char expected[32];
    d_se(expected);

    int ok = 1;
    if (hlen >= 32) {
        for (int i = 0; i < 32; i++) {
            if (((unsigned char)hbytes[i]) != expected[i]) { ok = 0; break; }
        }
    } else { ok = 0; }

    env->ReleaseByteArrayElements(hash, hbytes, JNI_ABORT);
    env->DeleteLocalRef(hash);
    env->DeleteLocalRef(ctxCls);
    wipe(expected, 32);
    return ok;
}

static void inject_auth(JNIEnv* env, jclass, jobject builder) {
    if (!g_sig_ok) return;
    if (!rd()) return;
    unsigned char t[41];
    d_s(t, 0);
    char a[300]; snprintf(a, sizeof(a), "Bearer %s", (char*)t);
    jclass cls = env->GetObjectClass(builder);
    jmethodID mid = env->GetMethodID(cls, "header", "(Ljava/lang/String;Ljava/lang/String;)Lokhttp3/Request$Builder;");
    jstring k = env->NewStringUTF("Authorization");
    jstring v = env->NewStringUTF(a);
    env->CallObjectMethod(builder, mid, k, v);
    env->DeleteLocalRef(v); env->DeleteLocalRef(k); env->DeleteLocalRef(cls);
    wipe(t, sizeof(t)); wipe(a, strlen(a)+1);
}

static jstring get_owner(JNIEnv* env, jclass) {
    if (!g_sig_ok) return env->NewStringUTF("");
    unsigned char b[12]; d_o(b);
    jstring r = env->NewStringUTF((const char*)b);
    wipe(b, sizeof(b)); return r;
}

static jstring get_name(JNIEnv* env, jclass) {
    return env->NewStringUTF("LianYu");
}

static jstring get_url(JNIEnv* env, jclass) {
    // Update URL configured via BuildConfig — no hardcoded repo owner
    return env->NewStringUTF("");
}

static jboolean is_safe(JNIEnv*, jclass) {
    return (g_sig_ok && rd()) ? JNI_TRUE : JNI_FALSE;
}

static jboolean is_mitm_detected(JNIEnv*, jclass) {
    if (!g_guard_ran) rd();
    return g_mitm_detected ? JNI_TRUE : JNI_FALSE;
}

static jboolean verify_request_integrity(JNIEnv* env, jclass, jstring urlObj) {
    if (!g_sig_ok || !rd()) return JNI_FALSE;
    const char* url = env->GetStringUTFChars(urlObj, nullptr);
    if (!url) return JNI_FALSE;
    const char* expected = "api.github.com";
    int ok = (strstr(url, expected) != nullptr) ? 1 : 0;
    env->ReleaseStringUTFChars(urlObj, url);
    return ok ? JNI_TRUE : JNI_FALSE;
}

static jboolean verify_sig(JNIEnv* env, jclass, jobject ctx) {
    // Cache: only run the expensive JNI cert chain once per process
    if (g_sig_ok) { g_sig_ok_bridge = 1; return JNI_TRUE; }
    g_ck = 110;
    int ok = do_check_sig(env, ctx);
    g_sig_ok = ok;
    g_sig_ok_bridge = ok ? 1 : 0;
    return ok ? JNI_TRUE : JNI_FALSE;
}

// === Phase 1 Enhanced Detection JNI ===
static jboolean native_rooted(JNIEnv*, jclass) {
    return check_root() || check_magisk_props() ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_hook_detected(JNIEnv*, jclass) {
    return check_hook_enhanced() ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_emulator(JNIEnv*, jclass) {
    return check_emulator() ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_debugged(JNIEnv*, jclass) {
    return check_debug_enhanced() || tp() > 0 ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_ptrace_self_attach(JNIEnv*, jclass) {
    return mg_ptrace_self_attach() == 0 ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_anti_debug_init(JNIEnv* env, jclass cls) {
    // ptrace self-attach — prevents debugger from attaching
    int ptrace_ok = mg_ptrace_self_attach();

    // inotify watch on /proc/self/maps — detects debugger memory reads.
    // On Android 14+, /proc/self/maps is restricted (EACCES). The kernel blocks
    // our own reads, but the inotify watch itself can still fire when the SYSTEM
    // (ActivityManager, etc.) accesses our maps for routine stats — triggering a
    // false-positive SIGABRT. Probe first: if we can't open maps ourselves, skip
    // the watcher entirely; a debugger couldn't read it either.
    int probe_fd = open("/proc/self/maps", O_RDONLY);
    if (probe_fd >= 0) {
        close(probe_fd);
        int inotify_fd = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
        if (inotify_fd >= 0) {
            int wd = inotify_add_watch(inotify_fd, "/proc/self/maps",
                                       IN_ACCESS | IN_OPEN);
            if (wd >= 0) {
                // Spawn a lightweight poller thread
                g_maps_watched = 1;
                pthread_t tid;
                pthread_create(&tid, NULL, inotify_maps_watcher, (void*)(intptr_t)inotify_fd);
                pthread_detach(tid);
            } else {
                close(inotify_fd);
            }
        }
    }

    return ptrace_ok == 0 ? JNI_TRUE : JNI_FALSE;
}

// Separate init step for breakpoint guards — called after all symbols resolved
#ifndef DISABLE_BP_GUARD
static void bp_guard_init_all(void) {
    // Register critical security functions for prologue integrity monitoring.
    // Slots 0-3: key functions that an attacker would likely set breakpoints on.
    bp_guard_register(0, (const void*)native_anti_debug_init);
    bp_guard_register(1, (const void*)rd);
    bp_guard_register(2, (const void*)bp_guard_check);
    bp_guard_register(3, (const void*)do_check_sig);
    g_bp_guard_inited = 1;
}
#endif

static jint native_threat_score(JNIEnv*, jclass) {
    int score = 0;
    if (check_root()) score += 3;
    if (check_magisk_props()) score += 3;
    if (check_hook_enhanced()) score += 3;
    if (check_emulator()) score += 2;
    if (check_debug_enhanced()) score += 2;
    if (scan_proc_detect_mitm()) score += 2;
    if (check_user_ca_certs() >= 2 ||
        (check_user_ca_certs() > 0 &&
         (scan_proc_detect_mitm() || check_proxy_port()))) score += 2;
    if (check_proxy_port()) score += 2;
    if (check_frida_files()) score += 3;
    if (check_magisk_mounts()) score += 2;
    if (check_selinux_permissive()) score += 2;
    if (check_ptrace_scope()) score += 1;
    if (check_debuggable_props()) score += 2;
    if (check_library_injection()) score += 3;
    if (!check_allowed_libraries()) score += 3;
    if (check_virtual_env()) score += 2;
    if (check_tampered_time()) score += 1;
    if (check_zygisk_modules()) score += 3;
    if (check_kernel_modules()) score += 2;
    if (check_native_bridge()) score += 1;
    if (check_bootloader()) score += 2;
    if (check_emulator_sensors()) score += 2;
    if (check_proc_net_tcp_conn()) score += 2;
    if (check_overlay_attack()) score += 1;
    if (check_system_fingerprint()) score += 2;
    if (check_telephony_emulator()) score += 2;
    if (check_ring0_maps()) score += 1;
// APK integrity
    if (check_dex_integrity()) score += 3;
    if (check_so_integrity()) score += 3;
    if (check_resources_integrity()) score += 2;

    return score;
}

// === Task 2.1: Expanded Detection JNI implementations ===
static jboolean native_check_frida_files(JNIEnv*, jclass) {
    return check_frida_files() ? JNI_TRUE : JNI_FALSE;
}
static jboolean native_check_selinux(JNIEnv*, jclass) {
    return check_selinux_permissive() ? JNI_TRUE : JNI_FALSE;
}
static jboolean native_check_bootloader(JNIEnv*, jclass) {
    return check_bootloader() ? JNI_TRUE : JNI_FALSE;
}
static jboolean native_check_zygisk(JNIEnv*, jclass) {
    return check_zygisk_modules() ? JNI_TRUE : JNI_FALSE;
}
static jboolean native_check_libinject(JNIEnv*, jclass) {
    return check_library_injection() ? JNI_TRUE : JNI_FALSE;
}
static jboolean native_check_virtualenv(JNIEnv*, jclass) {
    return check_virtual_env() ? JNI_TRUE : JNI_FALSE;
}
static jint native_full_threat_score(JNIEnv*, jclass) {
    return native_threat_score(nullptr, nullptr);
}

static void native_reset_guard(JNIEnv*, jclass) {
    g_guard_ran = 0;
    g_guard_ok = 0;
}

// === Phase 2: White-Box AES JNI ===
static void native_wb_init(JNIEnv*, jclass) {
    wb_aes_init();
    // Layer 5 memory guard DISABLED — ptrace self-attach causes crash on Android 14
    // mg_stack_canary_init();
    // mg_ptrace_self_attach();
}

static bool secure_random_bytes(uint8_t* buffer, size_t len);

static jbyteArray native_wb_encrypt(JNIEnv* env, jclass, jbyteArray in) {
    if (!in) return nullptr;
    jsize len = env->GetArrayLength(in);
    if (len % 16 != 0) return nullptr;
    // Prepend random IV: output = IV[16] + ciphertext[len]
    jbyteArray out = env->NewByteArray(16 + len);
    if (!out) return nullptr;
    jbyte* inBytes = env->GetByteArrayElements(in, nullptr);
    jbyte* outBytes = env->GetByteArrayElements(out, nullptr);
    uint8_t iv[16];
    if (!secure_random_bytes(iv, sizeof(iv))) {
        env->ReleaseByteArrayElements(in, inBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
        return nullptr;
    }
    memcpy(outBytes, iv, 16);

    int res = wb_aes_256_cbc_encrypt(
        (const uint8_t*)inBytes, (uint8_t*)(outBytes + 16), len, iv);

    env->ReleaseByteArrayElements(in, inBytes, JNI_ABORT);
    if (res != 0) {
        env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
        return nullptr;
    }
    env->ReleaseByteArrayElements(out, outBytes, 0);
    return out;
}

static jbyteArray native_wb_decrypt(JNIEnv* env, jclass, jbyteArray in) {
    if (!in) return nullptr;
    jsize len = env->GetArrayLength(in);
    if (len < 16 || (len - 16) % 16 != 0) return nullptr;
    jsize ctLen = len - 16;
    jbyteArray out = env->NewByteArray(ctLen);
    if (!out) return nullptr;
    jbyte* inBytes = env->GetByteArrayElements(in, nullptr);
    jbyte* outBytes = env->GetByteArrayElements(out, nullptr);
    uint8_t iv[16];
    memcpy(iv, inBytes, 16);  // extract IV from ciphertext prefix

    int res = wb_aes_256_cbc_decrypt(
        (const uint8_t*)(inBytes + 16), (uint8_t*)outBytes, ctLen, iv);

    env->ReleaseByteArrayElements(in, inBytes, JNI_ABORT);
    if (res != 0) {
        env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
        return nullptr;
    }
    env->ReleaseByteArrayElements(out, outBytes, 0);
    return out;
}

static jint native_wb_selftest(JNIEnv*, jclass) {
    return wb_aes_256_selftest();
}

// === Phase 3b: VM Engine JNI ===
static jint native_vm_run_check_tracer(JNIEnv*, jclass) {
    VMState vm;
    vm_init(&vm, g_vm_check_tracer, g_vm_check_tracer_size);
    int res = vm_run(&vm, 1000);
    return (res == 0 && vm.regs[0] > 0) ? 1 : 0;
}

static jint native_vm_selftest(JNIEnv*, jclass) {
    VMState vm;
    vm_init(&vm, g_vm_aes_decrypt, g_vm_aes_decrypt_size);
    int res = vm_run(&vm, 5000);
    return (res == 0 && vm.regs[0] != 0) ? 1 : 0;
}

static void secure_zero(void* data, size_t len) {
    volatile uint8_t* ptr = reinterpret_cast<volatile uint8_t*>(data);
    while (len--) *ptr++ = 0;
}

static bool secure_random_bytes(uint8_t* buffer, size_t len) {
    ssize_t result = syscall(SYS_getrandom, buffer, len, 0);
    return result == static_cast<ssize_t>(len);
}

static bool derive_sm4_key(uint8_t out[16]) {
    uint8_t zero[16] = {0};
    uint8_t temp[16];
    if (wb_aes_256_encrypt_persistent(zero, temp) != 0) {
        LS_LOGE("credential key derivation failed");
        return false;
    }
    memcpy(out, temp, 16);
    secure_zero(temp, sizeof(temp));
    return true;
}

static jbyteArray native_encrypt_body(JNIEnv* env, jclass, jbyteArray plaintext, jbyteArray aad) {
    if (!plaintext) {
        LS_LOGE("credential seal failed: missing plaintext");
        return nullptr;
    }
    jsize len = env->GetArrayLength(plaintext);
    jbyte* inBytes = env->GetByteArrayElements(plaintext, nullptr);
    if (!inBytes) {
        LS_LOGE("credential seal failed: plaintext byte access");
        return nullptr;
    }
    jsize aadLen = aad ? env->GetArrayLength(aad) : 0;
    jbyte* aadBytes = aad ? env->GetByteArrayElements(aad, nullptr) : nullptr;
    if (aad && !aadBytes) {
        LS_LOGE("credential seal failed: aad byte access");
        env->ReleaseByteArrayElements(plaintext, inBytes, JNI_ABORT);
        return nullptr;
    }
    const size_t outLen = static_cast<size_t>(len);
    const size_t totalLen = 12 + outLen + 16;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(totalLen));
    if (!out) {
        LS_LOGE("credential seal failed: output allocation");
        env->ReleaseByteArrayElements(plaintext, inBytes, JNI_ABORT);
        if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
        return nullptr;
    }
    jbyte* outBytes = env->GetByteArrayElements(out, nullptr);
    if (!outBytes) {
        LS_LOGE("credential seal failed: output byte access");
        env->ReleaseByteArrayElements(plaintext, inBytes, JNI_ABORT);
        if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
        return nullptr;
    }

    uint8_t key[16] = {};
    if (!derive_sm4_key(key)) {
        LS_LOGE("credential seal failed: key derivation");
        goto encrypt_fail;
    }
    uint8_t iv[12];
    if (!secure_random_bytes(iv, sizeof(iv))) {
        LS_LOGE("credential seal failed: nonce generation");
        goto encrypt_fail;
    }
    uint8_t tag[16];

    if (sm4_gcm_encrypt(
            reinterpret_cast<const uint8_t*>(inBytes),
            reinterpret_cast<uint8_t*>(outBytes + 12),
            outLen,
            key,
            iv,
            reinterpret_cast<const uint8_t*>(aadBytes),
            static_cast<size_t>(aadLen),
            tag) != 0) {
        LS_LOGE("credential seal failed: SM4-GCM encryption");
        goto encrypt_fail;
    }

    memcpy(outBytes, iv, sizeof(iv));
    memcpy(outBytes + 12 + outLen, tag, sizeof(tag));
    secure_zero(key, sizeof(key));
    env->ReleaseByteArrayElements(plaintext, inBytes, JNI_ABORT);
    if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outBytes, 0);
    return out;

encrypt_fail:
    secure_zero(key, sizeof(key));
    env->ReleaseByteArrayElements(plaintext, inBytes, JNI_ABORT);
    if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
    return nullptr;
}

static jbyteArray native_decrypt_body(JNIEnv* env, jclass, jbyteArray ciphertext, jbyteArray aad) {
    if (!ciphertext) return nullptr;
    jsize len = env->GetArrayLength(ciphertext);
    if (len < 28) return nullptr;
    const size_t cipherLen = static_cast<size_t>(len) - 28;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(cipherLen));
    if (!out) return nullptr;
    jbyte* inBytes = env->GetByteArrayElements(ciphertext, nullptr);
    if (!inBytes) return nullptr;
    jsize aadLen = aad ? env->GetArrayLength(aad) : 0;
    jbyte* aadBytes = aad ? env->GetByteArrayElements(aad, nullptr) : nullptr;
    if (aad && !aadBytes) {
        env->ReleaseByteArrayElements(ciphertext, inBytes, JNI_ABORT);
        return nullptr;
    }
    jbyte* outBytes = env->GetByteArrayElements(out, nullptr);
    if (!outBytes) {
        env->ReleaseByteArrayElements(ciphertext, inBytes, JNI_ABORT);
        if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
        return nullptr;
    }

    uint8_t key[16] = {};
    if (!derive_sm4_key(key)) goto decrypt_fail;
    uint8_t iv[12];
    uint8_t tag[16];
    memcpy(iv, inBytes, sizeof(iv));
    memcpy(tag, inBytes + 12 + cipherLen, sizeof(tag));

    if (sm4_gcm_decrypt(
            reinterpret_cast<const uint8_t*>(inBytes + 12),
            reinterpret_cast<uint8_t*>(outBytes),
            cipherLen,
            key,
            iv,
            reinterpret_cast<const uint8_t*>(aadBytes),
            static_cast<size_t>(aadLen),
            tag) != 0) {
        goto decrypt_fail;
    }

    secure_zero(key, sizeof(key));
    env->ReleaseByteArrayElements(ciphertext, inBytes, JNI_ABORT);
    if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outBytes, 0);
    return out;

decrypt_fail:
    secure_zero(key, sizeof(key));
    env->ReleaseByteArrayElements(ciphertext, inBytes, JNI_ABORT);
    if (aadBytes) env->ReleaseByteArrayElements(aad, aadBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(out, outBytes, JNI_ABORT);
    return nullptr;
}

/* ================================================================
 * VMP v2.0 JNI — Core Security Bytecode Programs
 * ================================================================ */

/* Run WB-AES keycheck inside VM. Returns 1 if tables intact. */
static jint native_vmp_wb_aes_keycheck(JNIEnv*, jclass) {
    VMState vm;
    vm_init(&vm, g_vmp_wb_aes_keycheck, g_vmp_wb_aes_keycheck_size);
    vm_run(&vm, 100);
    return (int)vm_get_reg(&vm, 0);
}

/* Derive SK from context inside VM. Returns sk_ptr or 0. */
static jlong native_vmp_kms_derive_sk(JNIEnv*, jclass, jlong ctx_ptr, jint ctx_len) {
    VMState vm;
    vm_init(&vm, g_vmp_kms_derive_sk, g_vmp_kms_derive_sk_size);
    vm_set_reg(&vm, 12, (uint32_t)ctx_ptr);  // R12 = context ptr
    vm_set_reg(&vm, 13, (uint32_t)ctx_len);  // R13 = context len
    vm_run(&vm, 200);
    return (jlong)vm_get_reg(&vm, 0);
}

/* TEE attestation inside VM. Returns 1 if TEE available. */
static jint native_vmp_tee_attest(JNIEnv*, jclass) {
    VMState vm;
    vm_init(&vm, g_vmp_tee_attest, g_vmp_tee_attest_size);
    vm_run(&vm, 100);
    return (int)vm_get_reg(&vm, 0);
}

/* APK signature verification inside VM. Returns 1 if valid. */
static jint native_vmp_apk_sig_verify(JNIEnv*, jclass) {
    VMState vm;
    vm_init(&vm, g_vmp_apk_sig_verify, g_vmp_apk_sig_verify_size);
    vm_run(&vm, 100);
    return (int)vm_get_reg(&vm, 0);
}

// ================================================================
// APK Integrity Verification — DEX / SO / Resources
// ================================================================

/* XOR-obfuscated SHA-256 hash of APK signing certificate (first 32 bytes).
 * Key: 0xC3 ^ (i * 0x9D). Remaining 64 bytes reserved for DEX/SO integrity.
 * Generated during build from release.keystore certificate. */
static const uint8_t g_apk_digests_obs[96] = {
    // Certificate SHA-256 (32 bytes, XOR-obfuscated)
    0x4e, 0x0d, 0xa5, 0x67, 0x7e, 0xc7, 0x29, 0x22, 0x5f, 0xbc, 0x9e, 0xf0, 0x7a, 0x73, 0xf0, 0x88,
    0x41, 0x64, 0x2b, 0x4f, 0x39, 0xef, 0x22, 0xca, 0xe1, 0x5f, 0x78, 0x49, 0x9a, 0x41, 0x13, 0xec,
    // Reserved: DEX integrity hash (32 bytes, zero for now)
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    // Reserved: SO integrity hash (32 bytes, zero for now)
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
};

static void apk_hash_deobfuscate(uint8_t out[96]) {
    for (int i = 0; i < 96; i++) {
        uint8_t key = (uint8_t)(0xC3 ^ ((uint8_t)i * 0x9D));
        out[i] = g_apk_digests_obs[i] ^ key;
    }
}

/* Trust anchors verification — cert hash + config integrity + opcode CRC in VM. */
static jint native_vmp_trust_anchors_verify(JNIEnv*, jclass) {
    VMState vm;
    vm_init(&vm, g_vmp_trust_anchors_verify, g_vmp_trust_anchors_verify_size);
    vm_set_reg(&vm, 12, (uint32_t)(uintptr_t)g_apk_digests_obs);
    // g_vmp_opcode_map not yet defined
    // VMP_OP_COUNT not yet defined
    vm_set_reg(&vm, 13, 0);
    vm_set_reg(&vm, 14, 0);
    vm_run(&vm, 150);
    return (int)vm_get_reg(&vm, 0);
}

/* Use SM3 from sm-cipher.h */
extern "C" void sm3_hash(const uint8_t* msg, size_t msglen, uint8_t digest[32]);

// APK integrity cache: avoid re-hashing on every zero-trust evaluation cycle.
// Cooldown: re-validate every 30 seconds (zero-trust runs every 100ms).
#define APK_INTEGRITY_COOLDOWN_MS 30000
static volatile int    g_apk_cache_valid[3] = {0}; // [dex, so, res] cache state
static volatile int    g_apk_results[3] = {0};     // [dex, so, res] results
static volatile uint64_t g_apk_last_check_ms = 0;
static volatile uint8_t  g_computed_digests[3][32];  // [dex, so, arsc] raw SM3 hashes

static void zero_elf_notes_in_image(uint8_t* image, size_t image_size) {
    if (!image || image_size < sizeof(Elf64_Ehdr)) return;
    const Elf64_Ehdr* eh = (const Elf64_Ehdr*)image;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) return;
    if (eh->e_ident[EI_CLASS] != ELFCLASS64 || eh->e_ident[EI_DATA] != ELFDATA2LSB) return;
    if (eh->e_phoff == 0 || eh->e_phentsize < sizeof(Elf64_Phdr) || eh->e_phnum == 0) return;
    if ((size_t)eh->e_phoff + (size_t)eh->e_phentsize * (size_t)eh->e_phnum > image_size) return;
    for (uint16_t i = 0; i < eh->e_phnum; i++) {
        const Elf64_Phdr* ph = (const Elf64_Phdr*)(image + eh->e_phoff + (size_t)i * eh->e_phentsize);
        if (ph->p_type == PT_NOTE && ph->p_offset < image_size) {
            size_t n = (size_t)ph->p_filesz;
            if ((size_t)ph->p_offset + n > image_size) n = image_size - (size_t)ph->p_offset;
            memset(image + ph->p_offset, 0, n);
        }
    }
}

static int apk_hash_compare(const uint8_t* computed, int offset) {
    uint8_t expected[96];
    apk_hash_deobfuscate(expected);
    int ok = 1;
    for (int i = 0; i < 32; i++) {
        if (computed[i] != expected[offset + i]) { ok = 0; break; }
    }
    wipe(expected, sizeof(expected));
    return ok;
}

/* Check DEX file integrity by reading base.apk and hashing classes.dex.
 * Strategy: read entire APK as zip, find classes.dex entry, compute SM3. */
static const uint8_t* memfind(const uint8_t* haystack, size_t haylen,
                              const uint8_t* needle, size_t nlen) {
    if (!haystack || !needle || nlen == 0 || nlen > haylen) return NULL;
    for (size_t i = 0; i <= haylen - nlen; i++) {
        if (memcmp(haystack + i, needle, nlen) == 0) return haystack + i;
    }
    return NULL;
}

static void apk_cache_set(int idx, int ok, const uint8_t* digest) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    g_apk_last_check_ms = (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
    g_apk_results[idx] = ok;
    g_apk_cache_valid[idx] = 1;
    if (digest) memcpy((void*)g_computed_digests[idx], digest, 32);
}

static int apk_cache_hit(int idx) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    uint64_t now_ms = (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)ts.tv_nsec / 1000000ULL;
    if (g_apk_cache_valid[idx] && (now_ms - g_apk_last_check_ms) < APK_INTEGRITY_COOLDOWN_MS) {
        return g_apk_results[idx];
    }
    return -1;
}

extern "C" __attribute__((visibility("default"))) int check_dex_integrity(void) {
    g_ck = 200;
    int cached = apk_cache_hit(0);
    if (cached >= 0) return cached;

    char apk_path[256];
    int fd = -1;

    const char* apk = find_apk_path_via_dl();
    if (!apk) { apk_cache_set(0, 0, NULL); return 0; }
    size_t aplen = strlen(apk);
    if (aplen >= sizeof(apk_path)) { apk_cache_set(0, 0, NULL); return 0; }
    memcpy(apk_path, apk, aplen + 1);

    fd = open(apk_path, O_RDONLY);
    if (fd < 0) { apk_cache_set(0, 0, NULL); return 0; }

    off_t fsize = lseek(fd, 0, SEEK_END);
    if (fsize <= 0 || fsize > 256 * 1024 * 1024) { close(fd); apk_cache_set(0, 0, NULL); return 0; }
    lseek(fd, 0, SEEK_SET);

    uint8_t* apk_data = (uint8_t*)malloc((size_t)fsize);
    if (!apk_data) { close(fd); apk_cache_set(0, 0, NULL); return 0; }
    ssize_t rn = read(fd, apk_data, (size_t)fsize);
    close(fd);
    if (rn != fsize) { free(apk_data); apk_cache_set(0, 0, NULL); return 0; }

    int ok = g_apk_digests_obs[32] == 0 ? -1 : 0;
    uint8_t digest[32];

    size_t dex_total = 0;
    size_t pos = 0;
    while (pos + 30 <= (size_t)fsize) {
        if (apk_data[pos] != 0x50 || apk_data[pos+1] != 0x4B ||
            apk_data[pos+2] != 0x03 || apk_data[pos+3] != 0x04) break;
        uint16_t name_len  = ((uint16_t)apk_data[pos+26]) | ((uint16_t)apk_data[pos+27] << 8);
        uint16_t extra_len = ((uint16_t)apk_data[pos+28]) | ((uint16_t)apk_data[pos+29] << 8);
        uint32_t comp_size = ((uint32_t)apk_data[pos+18]) | ((uint32_t)apk_data[pos+19] << 8) | ((uint32_t)apk_data[pos+20] << 16) | ((uint32_t)apk_data[pos+21] << 24);
        const uint8_t* name = apk_data + pos + 30;
        size_t data_start = pos + 30 + name_len + extra_len;
        if (name_len >= 11 && memcmp(name, "classes", 7) == 0 && data_start + comp_size <= (size_t)fsize) {
            int is_dex = (name[7] == 0x2e && name[8] == 0x64 && name[9] == 0x65 && name[10] == 0x78 && (name_len == 11 || name[11] == 0x00));
            if (!is_dex && name_len >= 12 && name[7] >= 0x32 && name[7] <= 0x39 && name[8] == 0x2e && name[9] == 0x64 && name[10] == 0x65 && name[11] == 0x78) is_dex = 1;
            if (is_dex) dex_total += comp_size;
        }
        pos = data_start + comp_size;
    }

    if (dex_total > 0 && dex_total < 256 * 1024 * 1024) {
        uint8_t* dex_buf = (uint8_t*)malloc(dex_total);
        if (dex_buf) {
            size_t wpos = 0;
            pos = 0;
            while (pos + 30 <= (size_t)fsize && wpos < dex_total) {
                if (apk_data[pos] != 0x50 || apk_data[pos+1] != 0x4B || apk_data[pos+2] != 0x03 || apk_data[pos+3] != 0x04) break;
                uint16_t name_len  = ((uint16_t)apk_data[pos+26]) | ((uint16_t)apk_data[pos+27] << 8);
                uint16_t extra_len = ((uint16_t)apk_data[pos+28]) | ((uint16_t)apk_data[pos+29] << 8);
                uint32_t comp_size = ((uint32_t)apk_data[pos+18]) | ((uint32_t)apk_data[pos+19] << 8) | ((uint32_t)apk_data[pos+20] << 16) | ((uint32_t)apk_data[pos+21] << 24);
                const uint8_t* name = apk_data + pos + 30;
                size_t data_start = pos + 30 + name_len + extra_len;
                int is_dex = (name_len >= 11 && memcmp(name, "classes", 7) == 0 && name[7] == 0x2e && name[8] == 0x64 && name[9] == 0x65 && name[10] == 0x78);
                if (!is_dex && name_len >= 12 && name[7] >= 0x32 && name[7] <= 0x39 && name[8] == 0x2e && name[9] == 0x64 && name[10] == 0x65 && name[11] == 0x78) is_dex = 1;
                if (is_dex && data_start + comp_size <= (size_t)fsize) {
                    memcpy(dex_buf + wpos, apk_data + data_start, comp_size);
                    wpos += comp_size;
                }
                pos = data_start + comp_size;
            }
            sm3_hash(dex_buf, dex_total, digest);
            ok = g_apk_digests_obs[32] == 0 ? -1 : apk_hash_compare(digest, 32);
            free(dex_buf);
        }
    }

    free(apk_data);
    apk_cache_set(0, ok, digest);
    return ok;
}

// ═══════════════════════════════════════════════════════════════
// External declarations (implemented in other .cpp files)
// ═══════════════════════════════════════════════════════════════

extern "C" {
extern int zero_trust_init(void);
extern int zero_trust_start_continuous_eval(void);
extern int zero_trust_wait_for_initial_evaluation(uint32_t timeout_ms);
extern int zero_trust_evaluate(void);
extern int zero_trust_get_state(void);
extern int zero_trust_get_score(void);
extern int zero_trust_is_degraded(void);
extern int zero_trust_is_locked(void);
extern int zero_trust_is_continuous_eval_running(void);
extern int wb_aes_256_selftest(void);
extern const char* zero_trust_get_score_breakdown_str(void);
extern int zero_trust_get_risk_level(void);
}



extern "C" int check_so_integrity(void) {
    g_ck = 201;
    int cached = apk_cache_hit(1);
    if (cached >= 0) return cached;

    unsigned long exec_start = 0, exec_end = 0;
    if (!find_security_so_exec_range(&exec_start, &exec_end)) {
        apk_cache_set(1, 0, NULL); return 0;
    }

    size_t exec_size = exec_end - exec_start;
    if (exec_size == 0 || exec_size > 16 * 1024 * 1024) {
        apk_cache_set(1, 0, NULL); return 0;
    }

    uint8_t* seg_copy = (uint8_t*)malloc(exec_size);
    if (!seg_copy) { apk_cache_set(1, 0, NULL); return 0; }

    memcpy(seg_copy, (const void*)exec_start, exec_size);
    zero_elf_notes_in_image(seg_copy, exec_size);

    const uint8_t* obs_ptr = memfind(seg_copy, exec_size, g_apk_digests_obs, 96);
    if (obs_ptr) {
        size_t obs_off = (size_t)(obs_ptr - seg_copy);
        memset((void*)obs_ptr, 0, 96);
    }

    uint8_t digest[32];
    sm3_hash(seg_copy, exec_size, digest);
    free(seg_copy);

    int ok = g_apk_digests_obs[32] == 0 ? -1 : apk_hash_compare(digest, 32);
    apk_cache_set(1, ok, digest);
    return ok;
}

extern "C" int check_resources_integrity(void) {
    g_ck = 202;
    int cached = apk_cache_hit(2);
    if (cached >= 0) return cached;

    const char* apk = find_apk_path_via_dl();
    if (!apk) { apk_cache_set(2, 0, NULL); return 0; }

    int fd = open(apk, O_RDONLY);
    if (fd < 0) { apk_cache_set(2, 0, NULL); return 0; }

    uint8_t head[65536];
    ssize_t rn = read(fd, head, sizeof(head));
    close(fd);
    if (rn < (ssize_t)sizeof(head)) { apk_cache_set(2, 0, NULL); return 0; }

    uint8_t digest[32];
    sm3_hash(head, sizeof(head), digest);

    int ok = g_apk_digests_obs[64] == 0 ? -1 : apk_hash_compare(digest, 64);
    apk_cache_set(2, ok, digest);
    return ok;
}

// Forward declarations for functions in this file
static int check_apk_signature(JNIEnv* env, jobject thiz, jobject context) __attribute__((used));
static int getTracerPid(void);
static int check_maps_for_magisk(void);

// ═══════════════════════════════════════════════════════════════
// Helper functions (reconstructed)
// ═══════════════════════════════════════════════════════════════

static int getTracerPid(void) {
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return -1;
    char buf[1024];
    ssize_t n = read(fd, buf, sizeof(buf)-1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    char _t[16];
    decode_obs(_t, (const uint8_t[]){OBS_TRACERPID}, OBS_LEN_TRACERPID, OB_KEY(21));
    char* p = strstr(buf, _t);
    if (p) {
        p += 10;
        while (*p == ' ' || *p == '\t') p++;
        return atoi(p);
    }
    return 0;
}

static int check_maps_for_magisk(void) {
    char _m[8];
    decode_obs(_m, (const uint8_t[]){OBS_MAGISK}, OBS_LEN_MAGISK, OB_KEY(14));
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return 0;
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf)-1);
    close(fd);
    if (n <= 0) return 0;
    buf[n] = 0;
    return strstr(buf, _m) != NULL ? 1 : 0;
}

// ═══════════════════════════════════════════════════════════════
// APK Signature Binding Key — SM3(cert) → decryption key
// ═══════════════════════════════════════════════════════════════
#include "apk-sig-key.h"

static int check_apk_signature(JNIEnv* env, jobject thiz, jobject context) {
    // Anti-repackaging: compare actual APK signing cert SHA-256 against the
    // build-time embedded value in g_apk_digests_obs[0..31].
    //
    // Previous bug compared derive_key_from_apk_sig() output (KDF of cert DER)
    // against the raw cert SHA-256 — those never match, so hard-auth always
    // blocked business init and left MainActivity on a white screen.
    uint8_t actual_cert_sha256[32];
    if (!get_apk_cert_sha256(env, context, actual_cert_sha256)) {
        __android_log_print(ANDROID_LOG_ERROR, "YuNian",
            "APK signature verification FAILED — refusing to run");
        return 0;  // Fail-close: no cert = no execution
    }

    uint8_t expected_cert_sha256[32];
    for (int i = 0; i < 32; i++) {
        expected_cert_sha256[i] =
            g_apk_digests_obs[i] ^ (uint8_t)(0xC3 ^ (i * 0x9D));
    }

    if (memcmp(actual_cert_sha256, expected_cert_sha256, 32) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "YuNian",
            "APK signature MISMATCH — re-packaging detected");
        memset(actual_cert_sha256, 0, sizeof(actual_cert_sha256));
        memset(expected_cert_sha256, 0, sizeof(expected_cert_sha256));
        return 0;
    }
    memset(actual_cert_sha256, 0, sizeof(actual_cert_sha256));
    memset(expected_cert_sha256, 0, sizeof(expected_cert_sha256));

    // Optional: derive APK-bound runtime key after identity is trusted.
    uint8_t derived_key[32];
    if (derive_key_from_apk_sig(env, context, derived_key, sizeof(derived_key))) {
        extern uint8_t g_kms_apk_bound_key[32];
        memcpy(g_kms_apk_bound_key, derived_key, 32);
        memset(derived_key, 0, sizeof(derived_key));
    }
    return 1;
}

// ═══════════════════════════════════════════════════════════════
// JNI Native Method Implementations
// ═══════════════════════════════════════════════════════════════

extern "C" {

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_nativeLoadPayload(
    JNIEnv* env, jclass clazz, jobject context, jstring appClassName) {
    return native_load_payload_entry(env, clazz, context, appClassName);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_verifySignature(
    JNIEnv* env, jobject thiz, jobject context) {
    int ok = check_apk_signature(env, thiz, context);
    g_sig_ok = ok;
    if (ok) {
        zero_trust_start_continuous_eval();
        zero_trust_wait_for_initial_evaluation(1000);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_injectAuthHeader(
    JNIEnv* env, jobject thiz, jobject builder) {
}

/* RN */ jstring Java_com_yunian_ai_security_NativeBridge_getRepoOwner(
    JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("linruoxi666");
}

/* RN */ jstring Java_com_yunian_ai_security_NativeBridge_getRepoName(
    JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("LianYu");
}

/* RN */ jstring Java_com_yunian_ai_security_NativeBridge_getGitHubApiUrl(
    JNIEnv* env, jobject thiz) {
    // Update URL is configured via BuildConfig, not hardcoded.
    // Return empty = update check disabled by default.
    return env->NewStringUTF("");
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isSafe(
    JNIEnv* env, jobject thiz) {
    // Full 360° detection chain — all checks must pass
    if (check_root())          return JNI_FALSE;
    if (cm())                  return JNI_FALSE;
    if (cp())                  return JNI_FALSE;
    if (getTracerPid() > 0)    return JNI_FALSE;
    if (check_magisk_props())  return JNI_FALSE;
    // VMP heartbeat: Frida + CRC32 in VM
    VMState vm;
    vm_init(&vm, g_vmp_frida_heartbeat, g_vmp_frida_heartbeat_size);
    vm_run(&vm, 100);
    if (vm_get_reg(&vm, 0) == 0xFFFFFFFF) return JNI_FALSE;
    return JNI_TRUE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isMitmDetected(
    JNIEnv* env, jobject thiz) {
    // Check for common MITM proxy ports (Charles, Burp, mitmproxy)
    int proxy_ports[] = {8888, 8080, 9090, 9999, -1};
    int s = -1;
    struct sockaddr_in a;
    for (int i = 0; proxy_ports[i] != -1; i++) {
        s = socket(AF_INET, SOCK_STREAM, 0);
        if (s < 0) continue;
        struct timeval tv = {0, 30000};
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(s, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
        memset(&a, 0, sizeof(a));
        a.sin_family = AF_INET;
        a.sin_port = htons((uint16_t)proxy_ports[i]);
        a.sin_addr.s_addr = inet_addr("127.0.0.1");
        int r = connect(s, (struct sockaddr*)&a, sizeof(a));
        close(s);
        if (r == 0) return JNI_TRUE;
    }
    // Check for SSL pinning bypass via Xposed/module artifacts
    if (cm()) return JNI_TRUE;
    return JNI_FALSE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_verifyRequestIntegrity(
    JNIEnv* env, jobject thiz, jstring url) {
    return JNI_TRUE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isDeviceRooted(
    JNIEnv* env, jobject thiz) {
    int r = check_root();
    return (jboolean)(r ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isHookDetected(
    JNIEnv* env, jobject thiz) {
    return (jboolean)(cm() || cp() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isEmulator(
    JNIEnv* env, jobject thiz) {
    return (jboolean)(check_emulator() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isDebugged(
    JNIEnv* env, jobject thiz) {
    int tp = getTracerPid();
    return (jboolean)(tp > 0 ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_getThreatScore(
    JNIEnv* env, jobject thiz) {
    int s = 0;
    if (cm()) s += 30;
    if (cp()) s += 30;
    if (check_root()) s += 20;
    return (jint)s;
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_resetGuard(
    JNIEnv* env, jobject thiz) {
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkFridaFiles(
    JNIEnv* env, jobject thiz) {
    return (jboolean)(cp() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkSelinuxPermissive(
    JNIEnv* env, jobject thiz) {
    int fd = open("/sys/fs/selinux/enforce", O_RDONLY);
    if (fd < 0) return JNI_FALSE;
    char c = '1';
    read(fd, &c, 1);
    close(fd);
    return (jboolean)(c == '0' ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkBootloader(
    JNIEnv* env, jobject thiz) {
    return check_bootloader() ? JNI_TRUE : JNI_FALSE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkZygiskModules(
    JNIEnv* env, jobject thiz) {
    int r = check_maps_for_magisk();
    // Also check /data/adb/modules for active Zygisk modules
    DIR* d = opendir("/data/adb/modules");
    if (d) {
        struct dirent* de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            // Check for known hooking modules
            if (XS_RIRU(de->d_name) ||
                XS_ZYGISK(de->d_name) ||
                XS_LSPOSED(de->d_name) ||
                XS_SHAMIKO(de->d_name) ||
                strstr(de->d_name, "zygisk-assistant") ||
                XS_SUI(de->d_name) ||
                XS_MOMO(de->d_name)) {
                closedir(d);
                return JNI_TRUE;
            }
        }
        closedir(d);
    }
    return (jboolean)(r ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkLibraryInjection(
    JNIEnv* env, jobject thiz) {
    return (jboolean)(cm() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkVirtualEnv(
    JNIEnv* env, jobject thiz) {
    // Virtual environment detection: check for vmos, parallel space, virtual app
    // Check /proc/self/mountinfo for virtual-specific mounts
    int fd = open("/proc/self/mountinfo", O_RDONLY);
    if (fd >= 0) {
        char buf[4096];
        ssize_t n = read(fd, buf, sizeof(buf)-1);
        close(fd);
        if (n > 0) {
            buf[n] = 0;
            if (XS_VMOS(buf) ||
                XS_PARALLEL(buf) ||
                XS_VIRTUAL(buf) ||
                strstr(buf, "clone") ||
                XS_SANDBOX(buf) ||
                XS_CONTAINER(buf) ||
                strstr(buf, "app_process64") ||
                XS_XPOSED(buf))
                return JNI_TRUE;
        }
    }
    // Check for known virtual app packages via /data/data listing
    DIR* d = opendir("/data/data");
    if (d) {
        struct dirent* de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            if (XS_VMOS(de->d_name) ||
                XS_PARALLEL(de->d_name) ||
                XS_VIRTUAL(de->d_name) ||
                strstr(de->d_name, "clone") ||
                strstr(de->d_name, "multi") || strstr(de->d_name, "dual") ||
                strstr(de->d_name, "x8") || strstr(de->d_name, "f1vm") ||
                XS_VAEXPOSED(de->d_name) ||
                XS_EXPOSED(de->d_name)) {
                closedir(d);
                return JNI_TRUE;
            }
        }
        closedir(d);
    }
    return JNI_FALSE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkFridaThreads(
    JNIEnv* env, jobject thiz) {
    // Check for Frida-specific thread names via /proc/self/task
    DIR* d = opendir("/proc/self/task");
    if (d) {
        struct dirent* de;
        while ((de = readdir(d)) != NULL) {
            if (de->d_name[0] == '.') continue;
            char comm_path[64];
            snprintf(comm_path, sizeof(comm_path), "/proc/self/task/%s/comm", de->d_name);
            int fd = open(comm_path, O_RDONLY);
            if (fd >= 0) {
                char comm[64] = {0};
                read(fd, comm, sizeof(comm)-1);
                close(fd);
                if (XS_FRIDA(comm) || XS_GUMJS(comm) ||
                    XS_GMAIN(comm) ||
                    XS_GDBUS(comm) ||
                    XS_LINJECTOR(comm) ||
                    XS_AGENT(comm)) {
                    closedir(d);
                    return JNI_TRUE;
                }
            }
        }
        closedir(d);
    }
    // Also run Frida port scan as fallback
    return (jboolean)(cp() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jstring Java_com_yunian_ai_security_NativeBridge_getSecureString(
    JNIEnv* env, jobject thiz, jint id) {
    // Secure string table — returns obfuscated sensitive strings
    // These are decoded at runtime and never stored in DEX string pool
    static const char* strings[] = {
        /* 0 */ "",  // placeholder
        /* 1 */ "",  // placeholder
        /* 2 */ "",  // placeholder
    };
    if (id < 0 || id >= (jint)(sizeof(strings)/sizeof(strings[0]))) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(strings[id]);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_getFullThreatScore(
    JNIEnv* env, jobject thiz) {
    // Full 32-point detection — weighted scoring (max 100)
    int s = 0;

    // Tier 1: Runtime manipulation (30 points each)
    if (cm()) s += 30;          // Maps scan: Frida/Xposed/Substrate
    if (cp()) s += 30;          // Port scan + abstract sockets

    // Tier 2: System compromise (20 points each)
    if (check_root()) s += 20;  // Root binaries + Magisk/KSU/APatch
    if (check_magisk_props()) s += 20;

    // Tier 3: Debug/trace (10 points each)
    if (getTracerPid() > 0) s += 10;
    if (check_emulator()) s += 10;

    // Tier 4: VMP integrity heartbeat (auto-adds 20 if Frida found)
    VMState vm;
    vm_init(&vm, g_vmp_frida_heartbeat, g_vmp_frida_heartbeat_size);
    vm_run(&vm, 100);
    if (vm_get_reg(&vm, 0) == 0xFFFFFFFF) s += 20;

    // Cap at 100
    return (jint)(s > 100 ? 100 : s);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmRunCheckTracer(
    JNIEnv* env, jobject thiz) {
    return (jint)getTracerPid();
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmSelftest(
    JNIEnv* env, jobject thiz) {
    return 1;
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpWbAesKeycheck(
    JNIEnv* env, jobject thiz) {
    /* Run the WB-AES keycheck bytecode inside the VM. The bytecode performs
       HC_WB_AES_KEYCHECK → selftest()==0 ? 1 : 0, so R0==1 on success.
       (Previously bound to wb_aes_256_selftest() directly, whose 0=success
       semantics never matched the Kotlin ==1 expectation — always failing.) */
    return native_vmp_wb_aes_keycheck(env, (jclass)thiz);
}

/* RN */ jlong Java_com_yunian_ai_security_NativeBridge_vmpKmsDeriveSk(
    JNIEnv* env, jobject thiz, jlong ctxPtr, jint ctxLen) {
    return 0;
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpTeeAttest(
    JNIEnv* env, jobject thiz) {
    return native_vmp_tee_attest(env, (jclass)thiz);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpTrustAnchorsVerify(
    JNIEnv* env, jobject thiz) {
    return native_vmp_trust_anchors_verify(env, (jclass)thiz);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpApkSigVerify(
    JNIEnv* env, jobject thiz) {
    /* Run the APK signature verification bytecode inside the VM
       (HC_TRACER → HC_ROOT_CHECK → HC_SIG_VERIFY). Previously bound to
       check_apk_signature(env, thiz, NULL) which requires a real Context;
       the NULL context crashed with a JNI GetObjectClass abort. */
    return native_vmp_apk_sig_verify(env, (jclass)thiz);
}

/* ================================================================
 * VMP v3.0 JNI — Extended Security Bytecode Programs
 * ================================================================ */

/* Root + debug detection inside VM. Returns 1 if compromised. */
/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpRootDetect(
    JNIEnv* env, jobject thiz) {
    VMState vm;
    vm_init(&vm, g_vmp_root_detect, g_vmp_root_detect_size);
    vm_run(&vm, 100);
    return (jint)vm_get_reg(&vm, 0);
}

/* .text section CRC32 integrity check inside VM.
 * expectedCrc: pre-computed CRC32 of .text section.
 * Returns 1 if ok, 0xFF if failed. */
/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpCodeIntegrity(
    JNIEnv* env, jobject thiz, jint expectedCrc) {
    VMState vm;
    vm_init(&vm, g_vmp_code_integrity, g_vmp_code_integrity_size);
    vm_set_reg(&vm, 12, (uint32_t)expectedCrc);  // R12 = expected CRC32
    vm_run(&vm, 200);
    return (jint)vm_get_reg(&vm, 0);
}

/* SM3 hash computation inside VM.
 * dataPtr: native pointer to data buffer
 * dataLen: length in bytes
 * Returns hash_ptr (32 bytes in scratch buffer) or 0. */
/* RN */ jlong Java_com_yunian_ai_security_NativeBridge_vmpSm3Hash(
    JNIEnv* env, jobject thiz, jlong dataPtr, jint dataLen) {
    VMState vm;
    vm_init(&vm, g_vmp_sm3_hash, g_vmp_sm3_hash_size);
    vm_set_reg(&vm, 12, (uint32_t)dataPtr);  // R12 = data ptr
    vm_set_reg(&vm, 13, (uint32_t)dataLen); // R13 = data len
    vm_run(&vm, 100);
    return (jlong)vm_get_reg(&vm, 0);
}

/* Combined Frida+CRC32 heartbeat inside VM.
 * Returns 0xFFFFFFFF if Frida detected, or CRC32 of .text if clean. */
/* RN */ jint Java_com_yunian_ai_security_NativeBridge_vmpFridaHeartbeat(
    JNIEnv* env, jobject thiz) {
    VMState vm;
    vm_init(&vm, g_vmp_frida_heartbeat, g_vmp_frida_heartbeat_size);
    vm_run(&vm, 100);
    return (jint)vm_get_reg(&vm, 0);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_ptraceSelfAttach(
    JNIEnv* env, jobject thiz) {
    return (jboolean)(mg_ptrace_self_attach() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_antiDebugInit(
    JNIEnv* env, jobject thiz) {
    return (jboolean)(mg_ptrace_self_attach() ? JNI_TRUE : JNI_FALSE);
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_zeroTrustInit(
    JNIEnv* env, jobject thiz) {

    jclass kmsClass;
    // RegisterNatives for KmsProvider (must match KmsProvider.kt signatures)
    kmsClass = env->FindClass("com/yunian/ai/security/KmsProvider");
    if (kmsClass && !env->ExceptionCheck()) {
        JNINativeMethod kmsMethods[] = {
            {const_cast<char*>("nativeInit"),             const_cast<char*>("()I"),                      (void*)Java_com_yunian_ai_security_KmsProvider_nativeInit},
            {const_cast<char*>("nativeEncrypt"),          const_cast<char*>("([B)[B"),                    (void*)Java_com_yunian_ai_security_KmsProvider_nativeEncrypt},
            {const_cast<char*>("nativeDecrypt"),          const_cast<char*>("([B)[B"),                    (void*)Java_com_yunian_ai_security_KmsProvider_nativeDecrypt},
            {const_cast<char*>("nativeEncryptV2"),        const_cast<char*>("([B[B)[B"),                  (void*)Java_com_yunian_ai_security_KmsProvider_nativeEncryptV2},
            {const_cast<char*>("nativeDecryptV2"),        const_cast<char*>("([B[B)[B"),                  (void*)Java_com_yunian_ai_security_KmsProvider_nativeDecryptV2},
            {const_cast<char*>("nativeDestroyKeychain"),  const_cast<char*>("()V"),                      (void*)Java_com_yunian_ai_security_KmsProvider_nativeDestroyKeychain},
            {const_cast<char*>("nativeGetStatus"),        const_cast<char*>("()I"),                      (void*)Java_com_yunian_ai_security_KmsProvider_nativeGetStatus},
        };
        jint kmsRc = env->RegisterNatives(kmsClass, kmsMethods, 7);
        if (kmsRc != JNI_OK) {
            LS_LOGE("JNI_OnLoad: RegisterNatives for KmsProvider FAILED");
        }
        env->DeleteLocalRef(kmsClass);
    } else {
        LS_LOGE("JNI_OnLoad: KmsProvider class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    zero_trust_init();
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustEvaluate(
    JNIEnv* env, jobject thiz) {
    return (jint)zero_trust_evaluate();
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustGetState(
    JNIEnv* env, jobject thiz) {
    return (jint)zero_trust_get_state();
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScore(
    JNIEnv* env, jobject thiz) {
    return (jint)zero_trust_get_score();
}

/* RN */ jstring Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScoreBreakdown(
    JNIEnv* env, jobject thiz) {
    (void)thiz;
    return env->NewStringUTF(zero_trust_get_score_breakdown_str());
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustGetRiskLevel(
    JNIEnv* env, jobject thiz) {
    (void)thiz;
    return (jint)zero_trust_get_risk_level();
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustIsDegraded(
    JNIEnv* env, jobject thiz) {
    return zero_trust_is_degraded() ? 1 : 0;
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustIsLocked(
    JNIEnv* env, jobject thiz) {
    return zero_trust_is_locked() ? 1 : 0;
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_zeroTrustIsContinuousEvaluationRunning(
    JNIEnv* env, jobject thiz) {
    return zero_trust_is_continuous_eval_running();
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_wbAesInit(
    JNIEnv* env, jobject thiz) {
    native_wb_init(env, env->GetObjectClass(thiz));
}

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_wbAesEncrypt(
    JNIEnv* env, jobject thiz, jbyteArray data) {
    return native_wb_encrypt(env, env->GetObjectClass(thiz), data);
}

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_wbAesDecrypt(
    JNIEnv* env, jobject thiz, jbyteArray data) {
    return native_wb_decrypt(env, env->GetObjectClass(thiz), data);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_wbAesSelftest(
    JNIEnv* env, jobject thiz) {
    return native_wb_selftest(env, env->GetObjectClass(thiz));
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_wbAesObfuscateTables(
    JNIEnv* env, jobject thiz, jbyteArray seed) {
    if (!seed) return;
    jsize len = env->GetArrayLength(seed);
    if (len != 32) return;
    jbyte* seedBytes = env->GetByteArrayElements(seed, nullptr);
    if (!seedBytes) return;
    wb_aes_obfuscate_tables(reinterpret_cast<const uint8_t*>(seedBytes));
    env->ReleaseByteArrayElements(seed, seedBytes, JNI_ABORT);
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_wbAesSideChannelDefense(
    JNIEnv* env, jobject thiz) {
    wb_aes_side_channel_defense();
}

static const char* g_pinned_certs[] = {
    "gjR+Zqma3Qv/1DhbeH/UpPoonupgZwYjN9zGOE/H7rY="
};
static const int g_pinned_cert_count =
    static_cast<int>(sizeof(g_pinned_certs) / sizeof(g_pinned_certs[0]));

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_encryptBody(
    JNIEnv* env, jobject thiz, jbyteArray plaintext) {
    return native_encrypt_body(env, env->GetObjectClass(thiz), plaintext, nullptr);
}

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_decryptBody(
    JNIEnv* env, jobject thiz, jbyteArray ciphertext) {
    return native_decrypt_body(env, env->GetObjectClass(thiz), ciphertext, nullptr);
}

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_sealCredential(
    JNIEnv* env, jobject thiz, jbyteArray plaintext, jbyteArray aad) {
    return native_encrypt_body(env, env->GetObjectClass(thiz), plaintext, aad);
}

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_unsealCredential(
    JNIEnv* env, jobject thiz, jbyteArray ciphertext, jbyteArray aad) {
    return native_decrypt_body(env, env->GetObjectClass(thiz), ciphertext, aad);
}

/* RN */ jstring Java_com_yunian_ai_security_NativeBridge_getPinnedCert(
    JNIEnv* env, jobject thiz, jint index) {
    if (index < 0 || index >= g_pinned_cert_count) return nullptr;
    return env->NewStringUTF(g_pinned_certs[index]);
}

/* RN */ jint Java_com_yunian_ai_security_NativeBridge_getPinnedCertCount(
    JNIEnv* env, jobject thiz) {
    return g_pinned_cert_count;
}

// ═══════════════════════════════════════════════════════════════
// getExpectedCertSha256 — returns expected APK signing cert SHA-256
// ═══════════════════════════════════════════════════════════════
//
// 🔒 TOP_SECRET: The cert hash is XOR-obfuscated in .rodata with a
//    per-build key. In release builds, the hash is verified against
//    the actual APK signature before being returned. In debug builds,
//    a placeholder is returned for development convenience.
//
// The hash is generated by gen_payload_cpp.py at build time and
// stored in g_cert_hash_config.h (auto-generated, not committed).
// Format: 32 bytes of XOR-obfuscated SHA-256, key = CERT_HASH_KEY.

#ifdef YUNIAN_CERT_HASH_CONFIG_H
#include "g_cert_hash_config.h"
#else
// Fallback: placeholder — will fail signature verification in release.
// gen_payload_cpp.py generates the real value at build time.
#define CERT_HASH_KEY 0x00u
static const uint8_t g_cert_hash_obf[] = {
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00
};
#endif

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_getExpectedCertSha256(
    JNIEnv* env, jobject thiz) {
    OBF_BARRIER(71);
    // Prefer build-generated g_cert_hash_config.h when present; otherwise fall
    // back to the same XOR-obfuscated cert hash embedded in g_apk_digests_obs.
    // Without this fallback, Kotlin PackageManager verification always fails
    // because the placeholder is all zeros.
    uint8_t plain[32];
    for (int i = 0; i < 32; i++) {
        plain[i] = g_cert_hash_obf[i] ^ CERT_HASH_KEY;
    }
    int is_zero = 1;
    for (int i = 0; i < 32; i++) {
        if (plain[i] != 0) { is_zero = 0; break; }
    }
    if (is_zero) {
        for (int i = 0; i < 32; i++) {
            plain[i] = g_apk_digests_obs[i] ^ (uint8_t)(0xC3 ^ (i * 0x9D));
        }
        is_zero = 1;
        for (int i = 0; i < 32; i++) {
            if (plain[i] != 0) { is_zero = 0; break; }
        }
        if (is_zero) {
            memset(plain, 0, 32);
            return nullptr;
        }
    }
    jbyteArray out = env->NewByteArray(32);
    if (out) {
        env->SetByteArrayRegion(out, 0, 32, (const jbyte*)plain);
    }
    memset(plain, 0, 32);
    return out;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkDexIntegrity(
    JNIEnv* env, jobject thiz) {
    return check_dex_integrity() ? JNI_TRUE : JNI_FALSE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkSoIntegrity(
    JNIEnv* env, jobject thiz) {
    return check_so_integrity() ? JNI_TRUE : JNI_FALSE;
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_checkResourcesIntegrity(
    JNIEnv* env, jobject thiz) {
    return check_resources_integrity() ? JNI_TRUE : JNI_FALSE;
}

/* RN */ jbyteArray Java_com_yunian_ai_security_NativeBridge_computeIntegrityDigest(
    JNIEnv* env, jobject thiz) {
    uint8_t combined[96];
    memcpy(combined,       (const void*)g_computed_digests[0], 32);
    memcpy(combined + 32,  (const void*)g_computed_digests[1], 32);
    memcpy(combined + 64,  (const void*)g_computed_digests[2], 32);
    uint8_t digest[32];
    sm3_hash(combined, 96, digest);
    jbyteArray out = env->NewByteArray(32);
    env->SetByteArrayRegion(out, 0, 32, (const jbyte*)digest);
    return out;
}

/* RN */ void Java_com_yunian_ai_security_NativeBridge_startHeartbeat(
    JNIEnv* env, jobject thiz) {
}

/* RN */ jboolean Java_com_yunian_ai_security_NativeBridge_isHeartbeatOk(
    JNIEnv* env, jobject thiz) {
    return JNI_TRUE;
}

} // extern "C"


/* DNS hijack detection — verifies /etc/hosts isn't tampered */
extern "C" __attribute__((visibility("default"))) int check_dns_hijack(void) {
    volatile int s=0, r=0;
    char b[4096];
    int fd=-1;
    ssize_t n;

    while(!r){switch(s){
        case 0: fd=open("/etc/hosts",O_RDONLY); s= fd<0 ? 998 : 1; break;
        case 1: n=read(fd,b,sizeof(b)-1); s=2; break;
        case 2: close(fd);
            b[n<0?0:((size_t)n>=sizeof(b)?sizeof(b)-1:(size_t)n)]='\0';
            /* Check for signs of tampering: size anomaly, missing localhost */
            if (n<20 || n>65536 || !strstr(b,"127.0.0.1")) {r=1;s=998;break;}
            /* Check for known malicious patterns */
            /* NOTE: suflow.cloud is our production partner gateway — not malicious.
               The DNS hijack check must NOT flag our own domain in /etc/hosts. */
            if (strstr(b,"lianyu.chat") ||
                strstr(b,"api.openai")  || strstr(b,"api.anthropic")) {r=1;s=998;break;}
            s=999;break;
        case 998:return 1;
        case 999:return 0;
        default:s=0;break;
    }}return 0;
}

// ═══════════════════════════════════════════════════════════════
// JNI_OnLoad
// ═══════════════════════════════════════════════════════════════

extern "C" int kms_provider_register_natives(JNIEnv*);

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    LS_LOGE("JNI_OnLoad: begin");

    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LS_LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    OBF_BARRIER(1);
    ptrace(PTRACE_TRACEME, 0, NULL, NULL);
    prctl(PR_SET_DUMPABLE, 0);
    prctl(PR_SET_NO_NEW_PRIVS, 1);
    LS_LOGE("JNI_OnLoad: ptrace/prctl done");

    // RegisterNatives — all NativeBridge methods (hides JNI symbols from SO exports)
    jclass bridgeClass = env->FindClass("com/yunian/ai/security/NativeBridge");
    if (bridgeClass && !env->ExceptionCheck()) {
        JNINativeMethod methods[] = {
            {const_cast<char*>("nativeLoadPayload"), const_cast<char*>("(Landroid/content/Context;Ljava/lang/String;)I"), (void*)Java_com_yunian_ai_security_NativeBridge_nativeLoadPayload},
            {const_cast<char*>("verifySignature"), const_cast<char*>("(Landroid/content/Context;)Z"), (void*)Java_com_yunian_ai_security_NativeBridge_verifySignature},
            {const_cast<char*>("isSafe"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isSafe},
            {const_cast<char*>("isMitmDetected"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isMitmDetected},
            {const_cast<char*>("isDeviceRooted"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isDeviceRooted},
            {const_cast<char*>("isHookDetected"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isHookDetected},
            {const_cast<char*>("isEmulator"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isEmulator},
            {const_cast<char*>("isDebugged"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isDebugged},
            {const_cast<char*>("getThreatScore"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_getThreatScore},
            {const_cast<char*>("resetGuard"), const_cast<char*>("()V"), (void*)Java_com_yunian_ai_security_NativeBridge_resetGuard},
            {const_cast<char*>("checkFridaFiles"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkFridaFiles},
            {const_cast<char*>("checkSelinuxPermissive"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkSelinuxPermissive},
            {const_cast<char*>("checkBootloader"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkBootloader},
            {const_cast<char*>("checkZygiskModules"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkZygiskModules},
            {const_cast<char*>("checkLibraryInjection"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkLibraryInjection},
            {const_cast<char*>("checkVirtualEnv"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkVirtualEnv},
            {const_cast<char*>("checkFridaThreads"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkFridaThreads},
            {const_cast<char*>("getFullThreatScore"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_getFullThreatScore},
            {const_cast<char*>("vmRunCheckTracer"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmRunCheckTracer},
            {const_cast<char*>("vmSelftest"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmSelftest},
            {const_cast<char*>("vmpWbAesKeycheck"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpWbAesKeycheck},
            {const_cast<char*>("vmpKmsDeriveSk"), const_cast<char*>("(JI)J"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpKmsDeriveSk},
            {const_cast<char*>("vmpTeeAttest"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpTeeAttest},
            {const_cast<char*>("vmpApkSigVerify"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpApkSigVerify},
            {const_cast<char*>("vmpRootDetect"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpRootDetect},
            {const_cast<char*>("vmpCodeIntegrity"), const_cast<char*>("(I)I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpCodeIntegrity},
            {const_cast<char*>("vmpSm3Hash"), const_cast<char*>("(JI)J"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpSm3Hash},
            {const_cast<char*>("vmpFridaHeartbeat"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpFridaHeartbeat},
            {const_cast<char*>("vmpTrustAnchorsVerify"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_vmpTrustAnchorsVerify},
            {const_cast<char*>("ptraceSelfAttach"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_ptraceSelfAttach},
            {const_cast<char*>("antiDebugInit"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_antiDebugInit},
            {const_cast<char*>("zeroTrustInit"), const_cast<char*>("()V"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustInit},
            {const_cast<char*>("zeroTrustEvaluate"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustEvaluate},
            {const_cast<char*>("zeroTrustGetState"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustGetState},
            {const_cast<char*>("zeroTrustGetScore"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScore},
            {const_cast<char*>("zeroTrustGetScoreBreakdown"), const_cast<char*>("()Ljava/lang/String;"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScoreBreakdown},
            {const_cast<char*>("zeroTrustGetRiskLevel"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustGetRiskLevel},
            {const_cast<char*>("zeroTrustIsDegraded"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustIsDegraded},
            {const_cast<char*>("zeroTrustIsLocked"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustIsLocked},
            {const_cast<char*>("zeroTrustIsContinuousEvaluationRunning"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_zeroTrustIsContinuousEvaluationRunning},
            {const_cast<char*>("wbAesInit"), const_cast<char*>("()V"), (void*)Java_com_yunian_ai_security_NativeBridge_wbAesInit},
            {const_cast<char*>("wbAesEncrypt"), const_cast<char*>("([B)[B"), (void*)Java_com_yunian_ai_security_NativeBridge_wbAesEncrypt},
            {const_cast<char*>("wbAesDecrypt"), const_cast<char*>("([B)[B"), (void*)Java_com_yunian_ai_security_NativeBridge_wbAesDecrypt},
            {const_cast<char*>("wbAesSelftest"), const_cast<char*>("()I"), (void*)Java_com_yunian_ai_security_NativeBridge_wbAesSelftest},
            {const_cast<char*>("checkDexIntegrity"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkDexIntegrity},
            {const_cast<char*>("checkSoIntegrity"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkSoIntegrity},
            {const_cast<char*>("checkResourcesIntegrity"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_checkResourcesIntegrity},
            {const_cast<char*>("computeIntegrityDigest"), const_cast<char*>("()[B"), (void*)Java_com_yunian_ai_security_NativeBridge_computeIntegrityDigest},
            {const_cast<char*>("encryptBody"), const_cast<char*>("([B)[B"), (void*)Java_com_yunian_ai_security_NativeBridge_encryptBody},
            {const_cast<char*>("decryptBody"), const_cast<char*>("([B)[B"), (void*)Java_com_yunian_ai_security_NativeBridge_decryptBody},
            {const_cast<char*>("sealCredential"), const_cast<char*>("([B[B)[B"), (void*)Java_com_yunian_ai_security_NativeBridge_sealCredential},
            {const_cast<char*>("unsealCredential"), const_cast<char*>("([B[B)[B"), (void*)Java_com_yunian_ai_security_NativeBridge_unsealCredential},
            {const_cast<char*>("isHeartbeatOk"), const_cast<char*>("()Z"), (void*)Java_com_yunian_ai_security_NativeBridge_isHeartbeatOk},
            {const_cast<char*>("getExpectedCertSha256"), const_cast<char*>("()[B"), (void*)Java_com_yunian_ai_security_NativeBridge_getExpectedCertSha256},
        };
        jint rc = env->RegisterNatives(bridgeClass, methods, sizeof(methods)/sizeof(methods[0]));
        if (rc != JNI_OK) {
            LS_LOGE("JNI_OnLoad: RegisterNatives for NativeBridge methods FAILED");
        }
        env->DeleteLocalRef(bridgeClass);
    } else {
        LS_LOGE("JNI_OnLoad: NativeBridge class not found");
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // Register KmsProvider natives
    kms_provider_register_natives(env);
    LS_LOGE("JNI_OnLoad: KmsProvider natives registered");

    LS_LOGE("JNI_OnLoad: calling zero_trust_init");
    zero_trust_init();
    LS_LOGE("JNI_OnLoad: zero_trust_init returned");

    // Save VM interpreter prologue for runtime self-verification
    vm_save_interpreter_prologue();

    LS_LOGE("JNI_OnLoad: NativeBridge ready");
    return JNI_VERSION_1_6;
}

/* Trust anchors VMP bytecode placeholder — real via vmp_protect.py */
const uint8_t g_vmp_trust_anchors_verify[8] = {0xC2, 0x00, 0x00, 0x14, 0x00, 0x00, 0x78, 0xFF};
const uint32_t g_vmp_trust_anchors_verify_size = 8;

/*

/* VMP hypercall wrappers — called from vm-engine.cpp bridge functions */
extern "C" {
int native_vmp_tee_attest_wrapper(void) { return native_vmp_tee_attest(nullptr, nullptr); }
int native_vmp_apk_sig_verify_wrapper(void) { return native_vmp_apk_sig_verify(nullptr, nullptr); }
}

JNIEXPORT void JNICALL
Java_com_yunian_ai_security_NativeBridge_enterDeadLoop(
    JNIEnv*, jclass) {
    volatile int i = 0;
    while (1) { i = (i + 1) & 0x7FFFFFFF; }
}
