/**
 * obfuscated_string_builder.h — 360-style stack-based string obfuscation.
 *
 * 360 的做法：关键字符串不在 .rodata 存储，而是逐字节 STRB 到栈上，
 * 每个字节是 (真实值 + offset)，运行时减去 offset 还原。
 *
 * 优势：
 *   1. 字符串不存于 .rodata — strings 命令搜不到
 *   2. 字节分散在代码段 — 无连续 pattern
 *   3. 字节顺序可任意打乱 — 无字典序
 *   4. SUB 操作可与算术指令混排 — 无特征模式
 *
 * 用法:
 *   #include "obfuscated_string_builder.h"
 *
 *   STACK_STRING_BEGIN(name, 32);
 *   STACK_PUT(name, 0, 'g', 4);  // 存 'k' (= 'g' + 4)
 *   STACK_PUT(name, 1, 'e', 4);  // 存 'i'
 *   STACK_PUT(name, 2, 't', 4);  // 存 'x'
 *   // ... 可以乱序 ...
 *   STACK_PUT(name, 8, 'e', 5);  // 存 'j' (= 'e' + 5), offset 可不同
 *   STACK_FINALIZE(name, 9, 4);  // 还原: 每个字节 -4
 *   // name 现在包含 "getSoName"
 *   env->GetStaticMethodID(cls, (const char*)name, sig);
 */

#ifndef OBFUSCATED_STRING_BUILDER_H
#define OBFUSCATED_STRING_BUILDER_H

#include <stdint.h>
#include <string.h>

// ============================================================
// 基础宏
// ============================================================

// 分配栈缓冲区
#define STACK_STRING_BEGIN(var, size)   \
    uint8_t var[size];                  \
    memset(var, 0, size)

// 存一个编码字节到栈（字节顺序可任意）
// idx:  目标位置
// chr:  真实字符 ('g')
// off:  编码偏移（真实值 + off = 存储值）
#define STACK_PUT(var, idx, chr, off)   \
    var[idx] = (uint8_t)((chr) + (off))

// 还原：每个字节减去 offset
#define STACK_FINALIZE(var, len, off)   \
    do {                                \
        for (int _i = 0; _i < (len); _i++) \
            var[_i] -= (off);           \
    } while(0)

// ============================================================
// 高级宏 — 常用字符串
// ============================================================

// JNI 方法名
#define BUILD_GET_STATIC_METHOD_ID(buf)         \
    STACK_STRING_BEGIN(buf, 32);                \
    STACK_PUT(buf,  0, 'g', 4);                 \
    STACK_PUT(buf,  1, 'e', 4);                 \
    STACK_PUT(buf,  2, 't', 4);                 \
    STACK_PUT(buf,  3, 'S', 4);                 \
    STACK_PUT(buf,  4, 't', 4);                 \
    STACK_PUT(buf,  5, 'a', 4);                 \
    STACK_PUT(buf,  6, 't', 4);                 \
    STACK_PUT(buf,  7, 'i', 4);                 \
    STACK_PUT(buf,  8, 'c', 4);                 \
    STACK_PUT(buf,  9, 'M', 4);                 \
    STACK_PUT(buf, 10, 'e', 4);                 \
    STACK_PUT(buf, 11, 't', 4);                 \
    STACK_PUT(buf, 12, 'h', 4);                 \
    STACK_PUT(buf, 13, 'o', 4);                 \
    STACK_PUT(buf, 14, 'd', 4);                 \
    STACK_PUT(buf, 15, 'I', 4);                 \
    STACK_PUT(buf, 16, 'D', 4);                 \
    STACK_PUT(buf, 17, '\0', 4);               \
    STACK_FINALIZE(buf, 17, 4)

// findClass
#define BUILD_FIND_CLASS(buf)                   \
    STACK_STRING_BEGIN(buf, 16);                \
    STACK_PUT(buf,  0, 'f', 3);                 \
    STACK_PUT(buf,  1, 'i', 3);                 \
    STACK_PUT(buf,  2, 'n', 3);                 \
    STACK_PUT(buf,  3, 'd', 3);                 \
    STACK_PUT(buf,  4, 'C', 3);                 \
    STACK_PUT(buf,  5, 'l', 3);                 \
    STACK_PUT(buf,  6, 'a', 3);                 \
    STACK_PUT(buf,  7, 's', 3);                 \
    STACK_PUT(buf,  8, 's', 3);                 \
    STACK_PUT(buf,  9, '\0', 3);               \
    STACK_FINALIZE(buf, 9, 3)

// getPackageManager
#define BUILD_GET_PACKAGE_MANAGER(buf)          \
    STACK_STRING_BEGIN(buf, 32);                \
    STACK_PUT(buf,  0, 'g', 5);                 \
    STACK_PUT(buf,  1, 'e', 5);                 \
    STACK_PUT(buf,  2, 't', 5);                 \
    STACK_PUT(buf,  3, 'P', 5);                 \
    STACK_PUT(buf,  4, 'a', 5);                 \
    STACK_PUT(buf,  5, 'c', 5);                 \
    STACK_PUT(buf,  6, 'k', 5);                 \
    STACK_PUT(buf,  7, 'a', 5);                 \
    STACK_PUT(buf,  8, 'g', 5);                 \
    STACK_PUT(buf,  9, 'e', 5);                 \
    STACK_PUT(buf, 10, 'M', 5);                 \
    STACK_PUT(buf, 11, 'a', 5);                 \
    STACK_PUT(buf, 12, 'n', 5);                 \
    STACK_PUT(buf, 13, 'a', 5);                 \
    STACK_PUT(buf, 14, 'g', 5);                 \
    STACK_PUT(buf, 15, 'e', 5);                 \
    STACK_PUT(buf, 16, 'r', 5);                 \
    STACK_PUT(buf, 17, '\0', 5);               \
    STACK_FINALIZE(buf, 17, 5)

// getPackageInfo
#define BUILD_GET_PACKAGE_INFO(buf)             \
    STACK_STRING_BEGIN(buf, 24);                \
    STACK_PUT(buf,  0, 'g', 2);                 \
    STACK_PUT(buf,  1, 'e', 2);                 \
    STACK_PUT(buf,  2, 't', 2);                 \
    STACK_PUT(buf,  3, 'P', 2);                 \
    STACK_PUT(buf,  4, 'a', 2);                 \
    STACK_PUT(buf,  5, 'c', 2);                 \
    STACK_PUT(buf,  6, 'k', 2);                 \
    STACK_PUT(buf,  7, 'a', 2);                 \
    STACK_PUT(buf,  8, 'g', 2);                 \
    STACK_PUT(buf,  9, 'e', 2);                 \
    STACK_PUT(buf, 10, 'I', 2);                 \
    STACK_PUT(buf, 11, 'n', 2);                 \
    STACK_PUT(buf, 12, 'f', 2);                 \
    STACK_PUT(buf, 13, 'o', 2);                 \
    STACK_PUT(buf, 14, '\0', 2);               \
    STACK_FINALIZE(buf, 14, 2)

// toByteArray
#define BUILD_TO_BYTE_ARRAY(buf)                \
    STACK_STRING_BEGIN(buf, 16);                \
    STACK_PUT(buf,  0, 't', 7);                 \
    STACK_PUT(buf,  1, 'o', 7);                 \
    STACK_PUT(buf,  2, 'B', 7);                 \
    STACK_PUT(buf,  3, 'y', 7);                 \
    STACK_PUT(buf,  4, 't', 7);                 \
    STACK_PUT(buf,  5, 'e', 7);                 \
    STACK_PUT(buf,  6, 'A', 7);                 \
    STACK_PUT(buf,  7, 'r', 7);                 \
    STACK_PUT(buf,  8, 'r', 7);                 \
    STACK_PUT(buf,  9, 'a', 7);                 \
    STACK_PUT(buf, 10, 'y', 7);                 \
    STACK_PUT(buf, 11, '\0', 7);               \
    STACK_FINALIZE(buf, 11, 7)

// ============================================================
// 使用示例（替换直接字符串）
// ============================================================

#if 0
// === 替换前（字符串在 .rodata，可直接搜索）===
jmethodID mid = (*env)->GetStaticMethodID(env, cls, "getPackageManager",
    "()Landroid/content/pm/PackageManager;");

// === 替换后（字符串在栈上，运行时构造）===
STACK_STRING_BEGIN(method_name, 32);
STACK_PUT(method_name,  0, 'g', 4);
STACK_PUT(method_name,  1, 'e', 4);
STACK_PUT(method_name,  2, 't', 4);
STACK_PUT(method_name,  3, 'P', 4);
STACK_PUT(method_name,  4, 'a', 4);
STACK_PUT(method_name,  5, 'c', 4);
STACK_PUT(method_name,  6, 'k', 4);
STACK_PUT(method_name,  7, 'a', 4);
STACK_PUT(method_name,  8, 'g', 4);
STACK_PUT(method_name,  9, 'e', 4);
STACK_PUT(method_name, 10, 'M', 4);
STACK_PUT(method_name, 11, 'a', 4);
STACK_PUT(method_name, 12, 'n', 4);
STACK_PUT(method_name, 13, 'a', 4);
STACK_PUT(method_name, 14, 'g', 4);
STACK_PUT(method_name, 15, 'e', 4);
STACK_PUT(method_name, 16, 'r', 4);
STACK_PUT(method_name, 17, '\0', 4);
STACK_FINALIZE(method_name, 17, 4);
jmethodID mid = (*env)->GetStaticMethodID(env, cls,
    (const char*)method_name,
    "()Landroid/content/pm/PackageManager;");
#endif

#endif  // OBFUSCATED_STRING_BUILDER_H
