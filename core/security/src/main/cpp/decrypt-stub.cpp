// Runtime SO decrypt stub — constructor-based self-decryption
//
// pack_so.py encrypts the .text section in-place (NO file insertion).
// This function lives in .lianyu_decrypt (separate from .text) and is
// called via .init_array constructor before JNI_OnLoad.
//
// CRITICAL DESIGN RULES:
//   1. NO calls to .text functions (it's encrypted until we decrypt it).
//   2. NO calls through the PLT — PLT lazy binding may not work during
//      .init_array constructor phase. Use raw inline syscall (SVC #0)
//      for mprotect AND for logging.
//   3. Try RWX (PROT_READ|PROT_WRITE|PROT_EXEC) FIRST to keep EXEC
//      throughout. If W^X enforcement rejects RWX, fall back to RW
//      (remove EXEC), decrypt, then restore RX. On Android 16+ the
//      RX restore may fail (hardened W^X), so try RWX as last resort.
//   4. The mprotect range must NOT include .lianyu_decrypt or .plt.
//      Only cover the .text section's page-aligned range.

#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>
#include <fcntl.h>

// ── Syscall numbers ──
#if defined(__aarch64__)
#define __NR_mprotect 226
#define __NR_write    64
#define __NR_openat   56
#define __NR_close    57
#elif defined(__arm__)
#define __NR_mprotect 125
#define __NR_write    4
#define __NR_openat   322
#define __NR_close    6
#else
#define __NR_mprotect 226
#define __NR_write    64
#define __NR_openat   56
#define __NR_close    57
#endif

// ── Raw syscall mprotect (no PLT dependency) ──
static __attribute__((always_inline)) inline long
raw_mprotect(void *addr, size_t len, int prot) {
    long ret;
#if defined(__aarch64__)
    register long _x0 __asm__("x0") = (long)addr;
    register long _x1 __asm__("x1") = (long)len;
    register long _x2 __asm__("x2") = (long)prot;
    register long _x8 __asm__("x8") = __NR_mprotect;
    __asm__ volatile("svc #0" : "=r"(_x0) : "r"(_x0), "r"(_x1), "r"(_x2), "r"(_x8)
                     : "memory", "cc");
    ret = _x0;
#elif defined(__arm__)
    long _nr = __NR_mprotect;
    register long _r0 __asm__("r0") = (long)addr;
    register long _r1 __asm__("r1") = (long)len;
    register long _r2 __asm__("r2") = (long)prot;
    __asm__ volatile(
        "push {r7}\n"
        "mov r7, %[_n]\n"
        "svc #0\n"
        "pop {r7}\n"
        : "=r"(_r0)
        : [_n]"r"(_nr), "r"(_r0), "r"(_r1), "r"(_r2)
        : "memory", "cc");
    ret = _r0;
#else
    ret = mprotect(addr, len, prot);
#endif
    return ret;
}

// ── Raw syscall write to any fd (no PLT dependency) ──
static __attribute__((always_inline)) inline long
raw_write_fd(int fd, const char *str, int len) {
#if defined(__aarch64__)
    register long _x0 __asm__("x0") = (long)fd;
    register long _x1 __asm__("x1") = (long)str;
    register long _x2 __asm__("x2") = (long)len;
    register long _x8 __asm__("x8") = __NR_write;
    __asm__ volatile("svc #0" : "=r"(_x0) : "r"(_x0), "r"(_x1), "r"(_x2), "r"(_x8)
                     : "memory", "cc");
    return _x0;
#elif defined(__arm__)
    long _nr = __NR_write;
    register long _r0 __asm__("r0") = (long)fd;
    register long _r1 __asm__("r1") = (long)str;
    register long _r2 __asm__("r2") = (long)len;
    __asm__ volatile(
        "push {r7}\n"
        "mov r7, %[_n]\n"
        "svc #0\n"
        "pop {r7}\n"
        : "=r"(_r0)
        : [_n]"r"(_nr), "r"(_r0), "r"(_r1), "r"(_r2)
        : "memory", "cc");
    return _r0;
#else
    return write(fd, str, len);
#endif
}

// ── Raw syscall openat (no PLT dependency) ──
static __attribute__((always_inline)) inline long
raw_openat(const char *path, int flags, int mode) {
#if defined(__aarch64__)
    register long _x0 __asm__("x0") = -100; // AT_FDCWD
    register long _x1 __asm__("x1") = (long)path;
    register long _x2 __asm__("x2") = (long)flags;
    register long _x3 __asm__("x3") = (long)mode;
    register long _x8 __asm__("x8") = __NR_openat;
    __asm__ volatile("svc #0" : "=r"(_x0) : "r"(_x0), "r"(_x1), "r"(_x2), "r"(_x3), "r"(_x8)
                     : "memory", "cc");
    return _x0;
#elif defined(__arm__)
    long _nr = __NR_openat;
    register long _r0 __asm__("r0") = -100;
    register long _r1 __asm__("r1") = (long)path;
    register long _r2 __asm__("r2") = (long)flags;
    register long _r3 __asm__("r3") = (long)mode;
    __asm__ volatile(
        "push {r7}\n"
        "mov r7, %[_n]\n"
        "svc #0\n"
        "pop {r7}\n"
        : "=r"(_r0)
        : [_n]"r"(_nr), "r"(_r0), "r"(_r1), "r"(_r2), "r"(_r3)
        : "memory", "cc");
    return _r0;
#else
    return open(path, flags, mode);
#endif
}

// Write to stderr (fd 2) — always available, may go to /dev/null on Android
static __attribute__((always_inline)) inline void
raw_write_str(const char *str, int len) {
    raw_write_fd(2, str, len);
}

// Simple strlen
static __attribute__((always_inline)) inline int
raw_strlen(const char *s) {
    int n = 0;
    while (s[n]) n++;
    return n;
}

// ── File-based logging ──
// stderr on Android apps goes to /dev/null, so we also write to a file.
// Try the app's data dir first (always writable by the app process).
static int g_log_fd = -1;

static __attribute__((always_inline)) inline void
raw_log_init() {
    if (g_log_fd >= 0) return;
    // O_WRONLY=1, O_CREAT=0100(64), O_TRUNC=01000(512) → flags=577
    // mode=0666(438)
    g_log_fd = (int)raw_openat("/data/data/com.yunian.ai/d2.log", 577, 438);
    if (g_log_fd < 0)
        g_log_fd = (int)raw_openat("/data/local/tmp/d2.log", 577, 438);
    if (g_log_fd < 0)
        g_log_fd = (int)raw_openat("/sdcard/d2.log", 577, 438);
}

#define RAW_LOG(msg) do { \
    int _n = raw_strlen(msg); \
    raw_write_str(msg, _n); \
    raw_log_init(); \
    if (g_log_fd >= 0) raw_write_fd(g_log_fd, msg, _n); \
} while(0)

// ── Global variables (in .data, patched by pack_so.py at build time) ──
// Non-zero sentinel values ensure .data placement (not .bss) so pack_so.py
// can find and patch them in the file.
// visibility("default") overrides -fvisibility=hidden so the version script
// can export these symbols to .dynsym for pack_so.py to find.
//
// ASLR NOTE: lianyu_text_start stores a SIGNED OFFSET from &lianyu_xor_key
// to .text start (NOT an absolute vaddr).  At runtime we compute:
//   text_ptr = &lianyu_xor_key + (int64_t)lianyu_text_start
// This is completely position-independent and works under ASLR.
extern "C" {

__attribute__((visibility("default")))
uint8_t lianyu_xor_key[16] = {
    0xDE, 0xAD, 0xBE, 0xEF, 0xDE, 0xAD, 0xBE, 0xEF,
    0xDE, 0xAD, 0xBE, 0xEF, 0xDE, 0xAD, 0xBE, 0xEF
};

__attribute__((visibility("default")))
uint64_t lianyu_text_start = 0xDEADBEEF42424242ULL;

__attribute__((visibility("default")))
uint64_t lianyu_text_size = 0xDEADBEEF42424242ULL;

__attribute__((visibility("default")))
void* lianyu_text_decrypt_ptr = nullptr;

} // extern "C"

// ── Decrypt function — in custom section, NOT encrypted by pack_so.py ──
// Section name does NOT start with .text. so the linker won't merge it
// into .text. It has SHF_EXECINSTR so it lands in an executable segment.
__attribute__((section(".lianyu_decrypt"), used, noinline, optimize("O0"), visibility("default")))
extern "C" void lianyu_d2_decrypt() {
    RAW_LOG("D2: enter\n");

    uint8_t* key_ptr  = lianyu_xor_key;

    // Compute .text runtime address via ASLR-independent offset.
    // lianyu_text_start stores the signed offset from &lianyu_xor_key to .text.
    uint8_t* text_ptr = key_ptr + (int64_t)lianyu_text_start;
    uint64_t text_sz  = lianyu_text_size;

    // Sentinel check — skip if not patched (debug builds)
    if (lianyu_text_start == 0xDEADBEEF42424242ULL) {
        RAW_LOG("D2: sentinel not patched, skip\n");
        return;
    }
    if (!text_ptr || !text_sz || !key_ptr) {
        RAW_LOG("D2: null check failed\n");
        return;
    }

    RAW_LOG("D2: sentinel ok\n");

    // 1. Make .text writable while keeping it executable.
    //    Strategy: Try RWX first (keep EXEC throughout). If RWX fails
    //    (W^X enforced), fall back to RW (remove EXEC), then restore
    //    RX after decryption. The restore may fail on Android 16+ which
    //    has hardened W^X (once EXEC is removed, it can't be re-added).
    //    In that case, try RWX as last resort.
    uint64_t page_start = ((uint64_t)text_ptr) & ~0xFFFULL;
    uint64_t page_end   = (((uint64_t)text_ptr) + text_sz + 0xFFF) & ~0xFFFULL;

    // Try RWX first — avoids the need to restore EXEC later
    long mp_ret = raw_mprotect((void*)page_start, page_end - page_start,
                               PROT_READ | PROT_WRITE | PROT_EXEC);
    int used_rwx = (mp_ret == 0);
    if (used_rwx) {
        RAW_LOG("D2: mprotect RWX ok\n");
    } else {
        RAW_LOG("D2: RWX failed, trying RW\n");
        // RWX failed (W^X enforced), try RW (remove EXEC)
        mp_ret = raw_mprotect((void*)page_start, page_end - page_start,
                              PROT_READ | PROT_WRITE);
    }
    if (mp_ret != 0) {
        RAW_LOG("D2: mprotect failed, abort\n");
        return;
    }
    if (!used_rwx) {
        RAW_LOG("D2: mprotect RW ok\n");
    }

    // 2. XOR decrypt in-place
    // Use volatile to prevent compiler from replacing with memset/memcpy
    volatile uint8_t* vp = text_ptr;
    for (uint64_t i = 0; i < text_sz; i++) {
        vp[i] ^= key_ptr[i & 0xF];
    }
    RAW_LOG("D2: xor done\n");

    // 3. Flush instruction cache using inline asm.
    // CRITICAL: Cannot use __builtin___clear_cache because it calls
    // __clear_cache() which lives in .text — at this point .text memory
    // is decrypted but the instruction cache still holds encrypted bytes,
    // so calling any .text function would SIGILL.
    // Instead we do cache flush instructions directly.
    {
        uint64_t addr = (uint64_t)text_ptr;
        uint64_t end  = (uint64_t)text_ptr + text_sz;
#if defined(__aarch64__)
        // ARM64: DC CVAU + DSB ISH + IC IVAU + DSB ISH + ISB
        for (uint64_t a = addr; a < end; a += 64) {
            __asm__ volatile("dc cvau, %0" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb ish" ::: "memory");
        for (uint64_t a = addr; a < end; a += 64) {
            __asm__ volatile("ic ivau, %0" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb ish" ::: "memory");
        __asm__ volatile("isb" ::: "memory");
#elif defined(__arm__)
        // ARM32: Use mcr p15 to clean D-cache and invalidate I-cache.
        // c15, c5, 7 = DCCMVAC (clean data cache by MVA)
        // c15, c5, 6 = ICIMVAU (invalidate I-cache by MVA)
        for (uint64_t a = addr; a < end; a += 32) {
            __asm__ volatile("mcr p15, 0, %0, c7, c10, 1" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb" ::: "memory");
        for (uint64_t a = addr; a < end; a += 32) {
            __asm__ volatile("mcr p15, 0, %0, c7, c5, 1" :: "r"(a) : "memory");
        }
        __asm__ volatile("dsb" ::: "memory");
        __asm__ volatile("isb" ::: "memory");
#else
        // x86/x86_64: Use __builtin___clear_cache (no separate I-cache issue
        // on x86 with strong memory ordering, but call for correctness).
        __builtin___clear_cache((char*)text_ptr, (char*)text_ptr + text_sz);
#endif
    }
    RAW_LOG("D2: cache flushed\n");

    // 4. Restore read-execute (only needed if we used RW, not RWX)
    if (!used_rwx) {
        long restore_ret = raw_mprotect((void*)page_start, page_end - page_start,
                     PROT_READ | PROT_EXEC);
        if (restore_ret != 0) {
            RAW_LOG("D2: RX restore failed, trying RWX\n");
            // RX restore failed (Android 16 hardened W^X), try RWX
            restore_ret = raw_mprotect((void*)page_start, page_end - page_start,
                          PROT_READ | PROT_WRITE | PROT_EXEC);
        }
        if (restore_ret != 0) {
            RAW_LOG("D2: RX/RWX restore failed\n");
        } else {
            RAW_LOG("D2: restore ok\n");
        }
    } else {
        RAW_LOG("D2: no restore needed (RWX)\n");
    }

    // 5. Wipe key from memory
    for (int i = 0; i < 16; i++) key_ptr[i] = 0;
    lianyu_text_start = 0;
    lianyu_text_size  = 0;
    lianyu_text_decrypt_ptr = nullptr;

    RAW_LOG("D2: done\n");
}

// ── Constructor — runs before JNI_OnLoad, after relocations ──
// We place a function pointer directly in .init_array using
// __attribute__((section(".init_array"))).  The linker processes
// .init_array entries at load time, calling each function pointer.
// This bypasses the compiler's constructor mechanism which is
// unreliable with -ffunction-sections + --gc-sections.
//
// CRITICAL: lianyu_auto_decrypt MUST be in .lianyu_decrypt (not .text),
// otherwise pack_so.py will encrypt it and the constructor will crash
// with SIGILL when the linker tries to call it.
extern "C" __attribute__((section(".lianyu_decrypt"), used, noinline, optimize("O0")))
void lianyu_auto_decrypt() {
    lianyu_d2_decrypt();
}

// Function pointer placed directly in the .init_array section.
// NOTE: we use .init_array.00000 (init_priority 0), NOT plain .init_array.
// GNU ld sorts SORT_BY_INIT_PRIORITY(.init_array.*) sections by their
// numeric priority, so .init_array.00000 is guaranteed to run BEFORE any
// compiler-generated constructors that land in plain .init_array.
// This is critical: those constructors live in .text which is still
// ENCRYPTED at load time — running them before lianyu_auto_decrypt would
// SIGILL. The symbols must also be listed as `global` in version-script.map
// so --gc-sections treats them as GC roots and keeps this entry alive.
// On ARM64, .init_array entries are 8-byte function pointers.
typedef void (*init_func_t)(void);
__attribute__((used, section(".init_array.00000")))
init_func_t lianyu_init_ptr = &lianyu_auto_decrypt;
