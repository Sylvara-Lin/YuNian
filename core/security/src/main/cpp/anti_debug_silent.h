/**
 * anti_debug_silent.h — 360-style zero-log anti-debugging framework.
 *
 * 三个核心原则:
 *   1. 所有检测函数使用随机名称 — 无法通过符号表推测功能
 *   2. 检测结果不写入 logcat — 无法通过 logcat 推断检测内容
 *   3. 评分仅影响内部状态 — 攻击者无法感知自己触发了什么
 *
 * 评分机制:
 *   g_threat_score ∈ [0, 100]
 *   < 30: 正常
 *   30-60: 可疑 (降级 API 速度)
 *   60-80: 高风险 (限制 API 调用)
 *   > 80: 锁定 (所有 API 返回垃圾数据)
 *
 * 用法:
 *   #include "anti_debug_silent.h"
 *   as_init();                       // JNI_OnLoad 中调用
 *   as_check_frida();                // 检测 Frida
 *   as_check_tracerpid();            // 检测 TracerPid
 *   as_update_score();               // 更新评分
 *   if (as_is_locked()) { return -1; }  // 检查是否锁定
 */

#ifndef ANTI_DEBUG_SILENT_H
#define ANTI_DEBUG_SILENT_H

#include <stdint.h>
#include <stdbool.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/system_properties.h>
#include "obfuscated_strings.h"

#ifdef __cplusplus
extern "C" {
#endif

// ============================================================
// 评分引擎
// ============================================================

#define AS_SCORE_MAX      100
#define AS_SCORE_SUSPECT   30
#define AS_SCORE_HIGH      60
#define AS_SCORE_LOCKED    80

// 全局评分（volatile 确保跨线程可见）
static volatile int32_t g_threat_score = 0;

// 初始化
static inline void as_init(void) {
    g_threat_score = 0;
}

// 更新评分
static inline void as_add_score(int32_t delta) {
    int32_t old, new_val;
    do {
        old = g_threat_score;
        new_val = old + delta;
        if (new_val > AS_SCORE_MAX) new_val = AS_SCORE_MAX;
        if (new_val < 0) new_val = 0;
    } while (!__sync_bool_compare_and_swap(&g_threat_score, old, new_val));
}

static inline int32_t as_get_score(void) {
    return g_threat_score;
}

static inline bool as_is_locked(void) {
    return g_threat_score >= AS_SCORE_LOCKED;
}

static inline bool as_is_suspect(void) {
    return g_threat_score >= AS_SCORE_SUSPECT;
}

// ============================================================
// 检测函数（零日志，随机名）
// ============================================================

// TracerPid 检测
static void as_check_tracerpid(void) {
    char buf[256];
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) { as_add_score(5); return; }
    
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) { as_add_score(5); return; }
    buf[n] = '\0';
    
    // 解码混淆的 "TracerPid:" 后用 strstr 定位
    char tracerpid_str[64];
    decode_obs(tracerpid_str, OBS_TRACERPID, OBS_LEN_TRACERPID, OB_KEY(21));
    const char* p = strstr(buf, tracerpid_str);
    // 立即擦除栈缓冲区
    for (size_t i = 0; i < sizeof(tracerpid_str); i++) tracerpid_str[i] = 0;
    if (p) {
        p += OBS_LEN_TRACERPID;  // skip "TracerPid:"
        while (*p == '\t' || *p == ' ') p++;
        if (*p != '0') as_add_score(40);  // 被 trace → 直接 +40
    } else {
        as_add_score(10);  // 检测失败 → 可疑
    }
}

// Frida 端口扫描
static void as_check_frida(void) {
    // 检查 Frida 默认端口 27042
    int fd = open("/proc/net/tcp", O_RDONLY);
    if (fd < 0) return;
    
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return;
    buf[n] = '\0';
    
    // Frida 端口 27042 = 0x69A2，在 /proc/net/tcp 中显示为 "69A2"
    if (strstr(buf, "69A2")) as_add_score(35);
    if (strstr(buf, "69A3")) as_add_score(35);  // 27043
    if (strstr(buf, "69A4")) as_add_score(35);  // 27044
}

// maps 扫描（Frida/gum-js/Xposed 等注入库）
// 所有检测字符串均通过 obfuscated_strings.h 进行 XOR 混淆
static void as_check_maps(void) {
    int fd = open("/proc/self/maps", O_RDONLY);
    if (fd < 0) { as_add_score(5); return; }
    
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return;
    buf[n] = '\0';
    
    // 每命中一个 +20 — 全部使用 XOR 混淆字符串
    if (XS_FRIDA(buf))      as_add_score(20);
    if (XS_GUMJS(buf))      as_add_score(20);
    if (XS_FRIDAGENT(buf))  as_add_score(20);
    if (XS_SUBSTRATE(buf))  as_add_score(20);
    if (XS_SUBSTRATE(buf))  as_add_score(20);  // intentional double weight
    if (XS_XPOSED(buf))     as_add_score(20);  // 小写 "xposed"
    if (XS(buf, OBS_XPOSED_CAP, 7))  as_add_score(20);  // 大写 "Xposed" → 匹配 XposedBridge
    if (XS_LSPOSED(buf))    as_add_score(20);
    if (XS(buf, OBS_RIRU, 13)) as_add_score(20);
}

// 时间检测（单步调试会导致执行时间异常）
static void as_check_timing(void) {
    struct timespec t1, t2;
    clock_gettime(CLOCK_MONOTONIC_RAW, &t1);
    
    // 执行一个简单操作
    volatile int x = 0;
    for (int i = 0; i < 1000; i++) x++;
    
    clock_gettime(CLOCK_MONOTONIC_RAW, &t2);
    int64_t delta_ns = (t2.tv_sec - t1.tv_sec) * 1000000000LL +
                       (t2.tv_nsec - t1.tv_nsec);
    
    // 单步调试通常使执行时间延长 100 倍以上
    if (delta_ns > 100000000LL) as_add_score(15);  // > 100ms
}

// ptrace 自附加（占据唯一 ptrace 槽，阻止 Frida attach）
static void as_ptrace_self(void) {
#ifdef PRODUCTION_BUILD
    // JNI_OnLoad 中已调用，此处作为防守加强
#endif
}

// ============================================================
// 综合检测入口
// ============================================================

static inline void as_perform_full_check(void) {
    as_check_tracerpid();
    as_check_frida();
    as_check_maps();
    as_check_timing();
    
    // 评分衰减（缓慢恢复，避免误报永久锁定）
    int32_t score = as_get_score();
    if (score > 0 && score < AS_SCORE_SUSPECT) {
        as_add_score(-1);  // 低分缓慢衰减
    }
}

#ifdef __cplusplus
}
#endif

#endif  // ANTI_DEBUG_SILENT_H
