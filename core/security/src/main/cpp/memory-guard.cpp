/*
 * memory-guard.cpp — YuNian Memory Protection Layer Implementation
 */

#include "memory-guard.h"
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <pthread.h>
#include <android/log.h>
#include "obfuscate.h"
#include "obfuscated_strings.h"

#define MG_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, "YuNian-MG", __VA_ARGS__)
#ifdef PRODUCTION_BUILD
#define MG_LOGE(...) ((void)0)
#else
#define MG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "YuNian-MG", __VA_ARGS__)
#endif

#pragma GCC visibility push(hidden)

static uint64_t g_canary_value = 0;
static volatile int g_canary_initialized = 0;
static volatile int g_ptrace_attached = 0;
static volatile int g_tampered = 0;

/* ================================================================
 * Guard Pages
 * ================================================================ */

void* mg_guarded_alloc(size_t size) {
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    // Align size up
    size_t aligned = (size + page_size - 1) & ~(size_t)(page_size - 1);
    // Total: two guard pages + aligned data
    size_t total = page_size + aligned + page_size;

    void* region = mmap(NULL, total, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (region == MAP_FAILED) return NULL;

    // First page: guard (PROT_NONE)
    mprotect(region, page_size, PROT_NONE);
    // Last page: guard
    mprotect((char*)region + page_size + aligned, page_size, PROT_NONE);

    return (char*)region + page_size;
}

void mg_guarded_free(void* ptr, size_t size) {
    OBF_BARRIER(57);
    if (!ptr) return;
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    void* region_start = (char*)ptr - page_size;
    size_t aligned = (size + page_size - 1) & ~(size_t)(page_size - 1);
    size_t total = page_size + aligned + page_size;

    munmap(region_start, total);
}

/* ================================================================
 * Stack Canary
 * ================================================================ */

void mg_stack_canary_init(void) {
    OBF_BARRIER(73);
    if (g_canary_initialized) return;
    // Read from /dev/urandom via getrandom syscall
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) {
        read(fd, &g_canary_value, sizeof(g_canary_value));
        close(fd);
    } else {
        // Fallback: use monotonic time + PID
        g_canary_value = (uint64_t)time(NULL) ^ ((uint64_t)getpid() << 32);
    }
    // Ensure canary has null byte to prevent string overflows leaking it
    g_canary_value &= ~(uint64_t)0xFF;
    g_canary_initialized = 1;
}

uint64_t mg_stack_canary_set(void) {
    OBF_BARRIER(89);
    return g_canary_value;
}

void mg_stack_canary_check(uint64_t expected) {
    OBF_BARRIER(93);
    if (expected != g_canary_value) {
        MG_LOGE("Stack canary mismatch! expected=%llx actual=%llx",
                (unsigned long long)expected, (unsigned long long)g_canary_value);
        /* Do NOT abort() — HarmonyOS/EMUI may have different stack layout.
         * Log and mark as tampered instead. */
        g_tampered = 1;
    }
}

/* ================================================================
 * Anti-Read / mprotect
 * ================================================================ */

int mg_protect_read(void* ptr, size_t size) {
    OBF_BARRIER(105);
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    // Round down ptr to page boundary
    uintptr_t page_aligned = (uintptr_t)ptr & ~(uintptr_t)(page_size - 1);
    // Round up size to page boundary
    size_t aligned_size = ((uintptr_t)ptr + size - page_aligned + page_size - 1)
                          & ~(uintptr_t)(page_size - 1);

    return mprotect((void*)page_aligned, aligned_size, PROT_NONE);
}

int mg_unprotect(void* ptr, size_t size) {
    OBF_BARRIER(118);
    long page_size = sysconf(_SC_PAGESIZE);
    if (page_size <= 0) page_size = 4096;

    uintptr_t page_aligned = (uintptr_t)ptr & ~(uintptr_t)(page_size - 1);
    size_t aligned_size = ((uintptr_t)ptr + size - page_aligned + page_size - 1)
                          & ~(uintptr_t)(page_size - 1);

    return mprotect((void*)page_aligned, aligned_size, PROT_READ | PROT_WRITE);
}

/* ================================================================
 * /proc/self/maps Polling
 * ================================================================ */

int mg_check_maps(void) {
    OBF_BARRIER(133);
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) return -1;

    char buf[8192];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;

    // Check for executable+writable segments (W^X violation)
    // Format: "rwxp" means read-write-execute-private
    if (strstr(buf, "rwxp") || strstr(buf, "rwxs")) {
        return 1;
    }

    // Known bad libraries
    char _m0[8], _m1[8], _m2[16], _m3[16], _m4[8], _m5[8], _m6[16];
    decode_obs(_m0, (const uint8_t[]){OBS_FRIDA}, OBS_LEN_FRIDA, OB_KEY(0));
    decode_obs(_m1, (const uint8_t[]){OBS_GUMJS}, OBS_LEN_GUMJS, OB_KEY(3));
    decode_obs(_m2, (const uint8_t[]){OBS_LINJECTOR}, OBS_LEN_LINJECTOR, OB_KEY(5));
    decode_obs(_m3, (const uint8_t[]){OBS_FRIDAGENT}, OBS_LEN_FRIDAGENT, OB_KEY(4));
    decode_obs(_m4, (const uint8_t[]){OBS_XPOSED}, OBS_LEN_XPOSED, OB_KEY(6));
    decode_obs(_m5, (const uint8_t[]){OBS_LSPOSED}, OBS_LEN_LSPOSED, OB_KEY(10));
    decode_obs(_m6, (const uint8_t[]){OBS_SUBSTRATE}, OBS_LEN_SUBSTRATE, OB_KEY(8));
    const char* bad_patterns[] = {
        _m0, _m1, _m2, _m3,
        _m4, _m5, _m6,
        NULL
    };
    for (int i = 0; bad_patterns[i] != NULL; i++) {
        if (strstr(buf, bad_patterns[i])) {
            return 1;
        }
    }

    return 0;
}

/* ================================================================
 * ptrace Self-Attach
 * ================================================================ */

int mg_ptrace_self_attach(void) {
    OBF_BARRIER(169);
    if (g_ptrace_attached) return 0;

    // Android 14+ restricts ptrace — try it anyway
    long rc = ptrace(PTRACE_TRACEME, 0, NULL, NULL);
    if (rc == 0) {
        g_ptrace_attached = 1;
        return 0;
    }

    // Fallback: PR_SET_DUMPABLE = 0 (prevents gdb/strace)
    prctl(PR_SET_DUMPABLE, 0);
    g_ptrace_attached = 1;
    return 0;
}

void mg_ptrace_self_detach(void) {
    OBF_BARRIER(185);
    if (!g_ptrace_attached) return;
    ptrace(PTRACE_DETACH, 0, NULL, NULL);
    prctl(PR_SET_DUMPABLE, 1);
    g_ptrace_attached = 0;
}

#pragma GCC visibility pop
