/**
 * trampoline_table.h — 360-style indirect call trampoline.
 *
 * 将所有外部函数调用统一为 ADRP + LDR + ADD + BR 四指令序列，
 * 使静态分析无法区分 open()、mmap()、decrypt()——它们看起来完全一样。
 *
 * 用法:
 *   #include "trampoline_table.h"
 *   // 替换: fd = open(path, O_RDONLY);
 *   // 为:    fd = TR_CALL(OPEN)(path, O_RDONLY);
 *
 * 实现:
 *   1. 构建期生成跳转表 g_trampoline_table[N]
 *   2. N 个 TRAMP_ENTRY 宏生成 N 个跳板函数
 *   3. 初始化时填充 g_trampoline_table[i] = dlsym(RTLD_DEFAULT, name)
 *   4. 调用 TR_CALL(NAME)(args) → 跳板 → 跳转表 → 真实函数
 */

#ifndef TRAMPOLINE_TABLE_H
#define TRAMPOLINE_TABLE_H

#include <dlfcn.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// 跳转表配置
// ============================================================

#define TRAMP_COUNT 64  // 跳转表槽位数（可根据需要扩展）

// 跳转表 — 放在专用 section 使所有跳板地址相邻
extern void* g_trampoline_table[TRAMP_COUNT]
    __attribute__((section(".trampoline_data"), visibility("hidden")));

// ============================================================
// 槽位枚举（每个外部函数一个槽位）
// ============================================================

enum {
    TR_SLOT_OPEN = 0,
    TR_SLOT_READ,
    TR_SLOT_WRITE,
    TR_SLOT_CLOSE,
    TR_SLOT_MMAP,
    TR_SLOT_MUNMAP,
    TR_SLOT_MPROTECT,
    TR_SLOT_MEMCPY,
    TR_SLOT_MEMSET,
    TR_SLOT_STRLEN,
    TR_SLOT_DLOPEN,
    TR_SLOT_DLSYM,
    TR_SLOT_SNPRINTF,
    TR_SLOT_MALLOC,
    TR_SLOT_FREE,
    TR_SLOT_PTHREAD_CREATE,
    TR_SLOT_GETENV,
    TR_SLOT_ACCESS,
    TR_SLOT_STAT,
    TR_SLOT_SYSLOG,
    // 更多槽位...
    TR_SLOT_MAX
};

// ============================================================
// 跳板宏 — 每个生成一个四指令跳板
// ============================================================

// 跳板汇编（内联 asm 版本 — 确保指令顺序和地址连续性）
#define TRAMP_ASM(func_name, slot_id)                                         \
    __asm__ volatile (                                                        \
        ".global _tramp_" #func_name "\n"                                     \
        ".hidden _tramp_" #func_name "\n"                                     \
        ".section .trampoline,\"ax\",@progbits\n"                             \
        "_tramp_" #func_name ":\n"                                            \
        "    adrp x16, g_trampoline_table\n"                                  \
        "    ldr  x17, [x16, #" #slot_id "*8]\n"                             \
        "    add  x16, x16, #" #slot_id "*8\n"                               \
        "    br   x17\n"                                                      \
        ".text\n"                                                             \
    )

// ============================================================
// 初始化 — 填充跳转表
// ============================================================

static inline void trampoline_init(void) {
    // 每个槽位填充真实函数地址
    g_trampoline_table[TR_SLOT_OPEN]    = (void*)open;
    g_trampoline_table[TR_SLOT_READ]    = (void*)read;
    g_trampoline_table[TR_SLOT_WRITE]   = (void*)write;
    g_trampoline_table[TR_SLOT_CLOSE]   = (void*)close;
    g_trampoline_table[TR_SLOT_MMAP]    = (void*)mmap;
    g_trampoline_table[TR_SLOT_MUNMAP]  = (void*)munmap;
    g_trampoline_table[TR_SLOT_MPROTECT] = (void*)mprotect;
    g_trampoline_table[TR_SLOT_MEMCPY]  = (void*)memcpy;
    g_trampoline_table[TR_SLOT_MEMSET]  = (void*)memset;
    g_trampoline_table[TR_SLOT_STRLEN]  = (void*)strlen;
    g_trampoline_table[TR_SLOT_DLOPEN]  = (void*)dlopen;
    g_trampoline_table[TR_SLOT_DLSYM]   = (void*)dlsym;
    g_trampoline_table[TR_SLOT_SNPRINTF] = (void*)snprintf;
    g_trampoline_table[TR_SLOT_MALLOC]  = (void*)malloc;
    g_trampoline_table[TR_SLOT_FREE]    = (void*)free;
    g_trampoline_table[TR_SLOT_PTHREAD_CREATE] = (void*)pthread_create;
    g_trampoline_table[TR_SLOT_GETENV]  = (void*)getenv;
    g_trampoline_table[TR_SLOT_ACCESS]  = (void*)access;
    g_trampoline_table[TR_SLOT_STAT]    = (void*)stat;
    g_trampoline_table[TR_SLOT_SYSLOG]  = (void*)syslog;
}

// ============================================================
// 调用宏
// ============================================================

// 声明跳板（使用前先声明）
#define TRAMP_DECL(func_name) \
    extern typeof(&func_name) _tramp_##func_name

// 通过跳板调用
#define TR_CALL(func_name) _tramp_##func_name

#ifdef __cplusplus
}
#endif

#endif  // TRAMPOLINE_TABLE_H
