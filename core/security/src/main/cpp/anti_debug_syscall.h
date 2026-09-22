/**
 * anti_debug_syscall.h — Direct syscall anti-debugging.
 *
 * All /proc reads and socket operations use raw syscall() to bypass
 * libc hooks (Frida intercepts fopen/read/connect at libc level).
 * Linux x86_64 syscall numbers are inline — ARM64 uses svc #0.
 *
 * Usage:
 *   #include "anti_debug_syscall.h"
 *   if (ad_check_tracerpid_syscall()) { _exit(1); }
 *   if (ad_check_frida_port_syscall()) { _exit(1); }
 */

#ifndef ANTI_DEBUG_SYSCALL_H
#define ANTI_DEBUG_SYSCALL_H

#include <stdint.h>
#include <stddef.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>

#ifdef __aarch64__
  // ARM64 syscall numbers
  #define __NR_openat  56
  #define __NR_read    63
  #define __NR_close   57
  #define __NR_socket  198
  #define __NR_connect 203
  #define __NR_ptrace  117
  #define __NR_getpid  172
  #define __NR_fcntl   25
#elif defined(__arm__)
  // ARM32 (armeabi-v7a) syscall numbers
  #define __NR_openat  322
  #define __NR_read    3
  #define __NR_close   6
  #define __NR_socket  281
  #define __NR_connect 283
  #define __NR_ptrace  26
  #define __NR_getpid  20
  #define __NR_fcntl   55
#elif defined(__x86_64__)
  // x86_64 syscall numbers
  #define __NR_openat  257
  #define __NR_read    0
  #define __NR_close   3
  #define __NR_socket  41
  #define __NR_connect 42
  #define __NR_ptrace  101
  #define __NR_getpid  39
#elif defined(__i386__)
  #define __NR_openat  295
  #define __NR_read    3
  #define __NR_close   6
  #define __NR_socket  359
  #define __NR_connect 362
  #define __NR_ptrace  26
  #define __NR_getpid  20
#else
  #error "Unsupported architecture for syscall anti-debug"
#endif

/* ── Raw syscall wrappers ── */

static inline long ad_sys_open(const char* path) {
    return syscall(__NR_openat, AT_FDCWD, path, O_RDONLY);
}

static inline long ad_sys_read(int fd, void* buf, size_t count) {
    return syscall(__NR_read, fd, buf, count);
}

static inline long ad_sys_close(int fd) {
    return syscall(__NR_close, fd);
}

static inline long ad_sys_socket(int domain, int type, int protocol) {
    return syscall(__NR_socket, domain, type, protocol);
}

static inline long ad_sys_connect(int fd, const void* addr, size_t addrlen) {
    return syscall(__NR_connect, fd, addr, addrlen);
}

static inline long ad_sys_fcntl(int fd, int cmd, long arg) {
    return syscall(__NR_fcntl, fd, cmd, arg);
}

/* ── TracerPid check via syscall ── */

static int ad_check_tracerpid_syscall(void) {
    int fd = (int)ad_sys_open("/proc/self/status");
    if (fd < 0) return 0;

    char buf[512];
    long n = ad_sys_read(fd, buf, sizeof(buf) - 1);
    ad_sys_close(fd);
    if (n <= 0) return 0;
    buf[n] = 0;

    // Find "TracerPid:" (XOR-obfuscated comparison to avoid string in .rodata)
    // Obfuscated: each byte ^ 0x5A
    static const uint8_t obs[] = {0x1E,0x2B,0x3E,0x28,0x3E,0x2B,0x29,0x3C,0x36,0x17};
    const char* p = buf;
    while (*p) {
        int match = 1;
        for (int i = 0; i < 9; i++) {
            if (((uint8_t)p[i] ^ 0x5A) != obs[i]) { match = 0; break; }
        }
        if (match) {
            p += 9;
            while (*p == ' ' || *p == '\t') p++;
            int pid = 0;
            while (*p >= '0' && *p <= '9') { pid = pid * 10 + (*p - '0'); p++; }
            return pid > 0;
        }
        p++;
    }
    return 0;
}

/* ── Frida port scan via syscall ── */

struct ad_sockaddr_in {
    uint16_t sin_family;
    uint16_t sin_port;
    uint32_t sin_addr;
    uint8_t  sin_zero[8];
};

static int ad_check_frida_port_syscall(void) {
    // Check ports 27042-27055 (Frida default range)
    // Use non-blocking connect to avoid hanging on Huawei kernels
    // where localhost SYN packets may be silently dropped during startup.
    for (int port = 27042; port <= 27055; port++) {
        long sock = ad_sys_socket(2/*AF_INET*/, 1/*SOCK_STREAM*/, 0);
        if (sock < 0) continue;

        // Set non-blocking to avoid indefinite hang on connect()
        int flags = ad_sys_fcntl((int)sock, 3/*F_GETFL*/, 0);
        if (flags >= 0) ad_sys_fcntl((int)sock, 4/*F_SETFL*/, flags | 0x800/*O_NONBLOCK*/);

        struct ad_sockaddr_in addr = {0};
        addr.sin_family = 2;  // AF_INET
        addr.sin_port = ((port & 0xFF) << 8) | ((port >> 8) & 0xFF);  // htons
        addr.sin_addr = 0x0100007F;  // 127.0.0.1 LE

        long rc = ad_sys_connect((int)sock, &addr, sizeof(addr));
        ad_sys_close((int)sock);
        if (rc == 0) return 1;
    }
    return 0;
}

/* ── Combined check ── */

static int ad_full_check_syscall(void) {
    if (ad_check_tracerpid_syscall()) return 1;
    if (ad_check_frida_port_syscall()) return 1;
    return 0;
}

#endif // ANTI_DEBUG_SYSCALL_H
