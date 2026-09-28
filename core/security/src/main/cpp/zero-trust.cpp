/*
 * zero-trust.cpp — YuNian Zero Trust Framework Implementation
 *
 * Implements the Policy Decision Point (PDP), Policy Enforcement
 * Point (PEP), trust scoring, continuous evaluation loop, and
 * incident response actions.
 *
 * Integrates with:
 *   - native-bridge.cpp (detection chain functions via extern decls)
 *   - AuditLogger.kt (via JNI env bridge)
 *   - SecurityGuard.kt (via JNI exposed functions)
 *
 * Architecture:
 *   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
 *   │ Detection   │────►│ PDP (score  │────►│ PEP (access │
 *   │ Chain (C)   │     │ + state)    │     │ control)    │
 *   └─────────────┘     └──────┬──────┘     └──────┬──────┘
 *                              │                   │
 *                              ▼                   ▼
 *                       ┌─────────────┐     ┌─────────────┐
 *                       │ Response    │     │ Module ACL  │
 *                       │ Executor    │     │ Matrix      │
 *                       └─────────────┘     └─────────────┘
 *
 * Thread-safety: Atomic operations for fast path.
 * Background thread for continuous 100ms evaluation.
 */

/* ==================================================================
 * Includes
 * ================================================================== */

#include "zero-trust.h"

#include <jni.h>
#include <pthread.h>
#include <atomic>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "obfuscate.h"

/* ==================================================================
 * Detection Chain Forward Declarations
 *
 * These functions are defined in native-bridge.cpp.
 * They perform the actual system-level detection (root, hook,
 * emulator, debug, MITM, etc.) and return 1 if detected, 0 if not.
 * ================================================================== */

#ifdef __cplusplus
extern "C" {
#endif

/* Root detection */
int check_root(void);
int check_magisk_props(void);

/* Hook detection */
int check_hook_enhanced(void);

/* Emulator detection */
int check_emulator(void);

/* Debug detection */
int check_debug_enhanced(void);
int tp(void);  /* TracerPid check */

/* MITM detection */
int scan_proc_detect_mitm(void);
int check_user_ca_certs(void);
int check_proxy_port(void);

/* VPN detection */
int detect_vpn_tun(void);

/* Task 2.1: Expanded detection (20 new checks from native-bridge.cpp) */
int check_frida_files(void);
int check_magisk_mounts(void);
int check_selinux_permissive(void);
int check_ptrace_scope(void);
int check_debuggable_props(void);
int check_library_injection(void);
int check_virtual_env(void);
int check_tampered_time(void);
int check_zygisk_modules(void);
int check_kernel_modules(void);
int check_native_bridge(void);
int check_bootloader(void);
int check_emulator_sensors(void);
int check_proc_net_tcp_conn(void);
int check_overlay_attack(void);
int check_system_fingerprint(void);
int check_telephony_emulator(void);
int check_ring0_maps(void);

/* APK integrity checks (native-bridge.cpp) */
extern "C" int check_dex_integrity(void);
extern "C" int check_so_integrity(void);
extern "C" int check_resources_integrity(void);

/* Signature verification (returns 1 if valid, 0 if invalid) */
/* Defined in native-bridge.cpp as do_check_sig() — we expose wrapper */
extern int g_sig_ok;

#ifdef __cplusplus
}
#endif

/* ==================================================================
 * Constants
 * ================================================================== */

/** Continuous evaluation interval: 100ms as per spec */
#define ZT_EVAL_INTERVAL_MS     100ULL

/** Maximum stale time before on-demand re-evaluation: 100ms */
#define ZT_MAX_CACHE_AGE_MS     100ULL

/** Error threshold: 3 consecutive detection failures → BREACH escalation */
#define ZT_ERROR_THRESHOLD       3

/**
 * MITM user-CA cert threshold.
 *
 * check_user_ca_certs() returns the NUMBER of user-added CA certs.
 * A single leftover dev-tool root CA (Reqable/Charles/Fiddler) on a
 * development device is not an interception indicator by itself, so we
 * only score when the count reaches this threshold.  Real MITM setups
 * typically also run a proxy or a sniffing process, which are scored
 * separately (mitm_proc / mitm_port).
 */
#define ZT_MITM_CA_THRESHOLD     2

/** Default threat score weights */
#define ZT_WEIGHT_ROOT           3
#define ZT_WEIGHT_MAGISK         3
#define ZT_WEIGHT_HOOK           3
#define ZT_WEIGHT_SIGNATURE      3
#define ZT_WEIGHT_EMULATOR       2
#define ZT_WEIGHT_DEBUG          2
#define ZT_WEIGHT_MITM_PROC      2
#define ZT_WEIGHT_MITM_CA        2
#define ZT_WEIGHT_MITM_PORT      2
#define ZT_WEIGHT_VPN            1
#define ZT_WEIGHT_FRIDA_FILES    3
#define ZT_WEIGHT_MAGISK_MOUNTS  2
#define ZT_WEIGHT_SELINUX        2
#define ZT_WEIGHT_PTRACE         1
#define ZT_WEIGHT_DEBUGGABLE     2
#define ZT_WEIGHT_LIB_INJECT     3
#define ZT_WEIGHT_VIRTUAL        2
#define ZT_WEIGHT_TIME_TAMPER    1
#define ZT_WEIGHT_ZYGISK         3
#define ZT_WEIGHT_KERNEL_MOD     2
#define ZT_WEIGHT_NATIVE_BRIDGE  1
#define ZT_WEIGHT_BOOTLOADER     2
#define ZT_WEIGHT_EMU_SENSORS    2
#define ZT_WEIGHT_TCP_CONN       2
#define ZT_WEIGHT_OVERLAY        1
#define ZT_WEIGHT_SYS_FP         2
#define ZT_WEIGHT_TELEPHONY      2
#define ZT_WEIGHT_RING0          1
#define ZT_WEIGHT_ERROR_PER_3    1

/* ==================================================================
 * Internal State (Thread-safe via atomics)
 * ================================================================== */

static struct {
    /** Current trust state: TRUST, SUSPICIOUS, or BREACH */
    std::atomic<int>      state;

    /** Raw threat score (0-30) */
    std::atomic<int>      score;

    /** Monotonic timestamp of last evaluation (ms) */
    std::atomic<uint64_t> last_eval_ms;

    /** Consecutive error counter */
    std::atomic<int>      error_count;

    /** Flags for response actions taken */
    std::atomic<int>      degraded;       /**< 1 if degradation active */
    std::atomic<int>      locked;         /**< 1 if lock active */
    std::atomic<int>      keys_wiped;     /**< 1 if keys have been wiped */

    /** Continuous evaluation thread control */
    std::atomic<int>      eval_running;   /**< 1 if background thread active */
    std::atomic<int>      initial_eval_complete;
    pthread_t       eval_thread;    /**< Background thread handle */
} g_zt = {
    .state        = ZT_BREACH,  /* default deny */
    .score        = 0,
    .last_eval_ms = 0,
    .error_count  = 0,
    .degraded     = 0,
    .locked       = 0,
    .keys_wiped   = 0,
    .eval_running = 0,
    .initial_eval_complete = 0,
};

static pthread_mutex_t g_zt_initial_eval_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_zt_initial_eval_cond = PTHREAD_COND_INITIALIZER;

/* ==================================================================
 * Score Breakdown Diagnostics
 *
 * Records which detection-chain items contributed to the threat
 * score. Exposed via JNI zeroTrustGetScoreBreakdown() so the app
 * can dump the exact score sources into the diag file.
 * ================================================================== */

#define ZT_BREAKDOWN_MAX 96
static char       g_zt_breakdown_buf[2048];
static int        g_zt_breakdown_count = 0;
static pthread_mutex_t g_zt_breakdown_mutex = PTHREAD_MUTEX_INITIALIZER;

static void zt_breakdown_reset(void)
{
    pthread_mutex_lock(&g_zt_breakdown_mutex);
    g_zt_breakdown_buf[0] = '\0';
    g_zt_breakdown_count = 0;
    pthread_mutex_unlock(&g_zt_breakdown_mutex);
}

static void zt_breakdown_add(const char* name)
{
    pthread_mutex_lock(&g_zt_breakdown_mutex);
    if (g_zt_breakdown_count < ZT_BREAKDOWN_MAX) {
        size_t len = strlen(g_zt_breakdown_buf);
        size_t nl  = strlen(name);
        if (len + nl + 2 < sizeof(g_zt_breakdown_buf)) {
            if (len > 0) {
                g_zt_breakdown_buf[len++] = ',';
            }
            memcpy(g_zt_breakdown_buf + len, name, nl);
            g_zt_breakdown_buf[len + nl] = '\0';
            g_zt_breakdown_count++;
        }
    }
    pthread_mutex_unlock(&g_zt_breakdown_mutex);
}

static void zt_mark_initial_eval_complete(void)
{
    if (g_zt.initial_eval_complete.exchange(1, std::memory_order_acq_rel) == 0) {
        pthread_mutex_lock(&g_zt_initial_eval_mutex);
        pthread_cond_broadcast(&g_zt_initial_eval_cond);
        pthread_mutex_unlock(&g_zt_initial_eval_mutex);
    }
}

/* ==================================================================
 * Monotonic Clock Helper
 * ================================================================== */

/**
 * Get monotonic time in milliseconds.
 * Uses CLOCK_MONOTONIC for reliable interval measurement.
 */
static uint64_t zt_now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL
         + (uint64_t)ts.tv_nsec / 1000000ULL;
}

/* ==================================================================
 * Memory Wipe (Compiler-Barrier Safe)
 * ================================================================== */

void zero_trust_wipe_memory(void* ptr, size_t n)
{
    if (!ptr || n == 0) return;
    volatile unsigned char* v = (volatile unsigned char*)ptr;
    for (size_t i = 0; i < n; i++) {
        v[i] = 0;
    }
    /* Compiler barrier: prevents optimizer from removing the loop */
    __asm__ __volatile__("" ::: "memory");
}

/* ==================================================================
 * Trust State → String
 * ================================================================== */

const char* zero_trust_state_name(zt_trust_state_t state)
{
    switch (state) {
        case ZT_TRUST:      return "TRUST";
        case ZT_SUSPICIOUS: return "SUSPICIOUS";
        case ZT_BREACH:     return "BREACH";
        default:            return "UNKNOWN";
    }
}

int zero_trust_is_locked(void) {
    return g_zt.locked.load(std::memory_order_acquire);
}

int zero_trust_is_degraded(void) {
    return g_zt.degraded.load(std::memory_order_acquire);
}

/* ==================================================================
 * Client Risk Level — Threshold-Based Risk Assessment
 * ================================================================== */

/**
 * Map the current raw threat score to a 5-tier risk level using the
 * configurable ZT_RISK_THRESHOLD_* knobs.
 *
 *   score <  ZT_RISK_THRESHOLD_MEDIUM   → ZT_RISK_SAFE
 *   score <  ZT_RISK_THRESHOLD_HIGH     → ZT_RISK_LOW
 *   score <  ZT_RISK_THRESHOLD_CRITICAL → ZT_RISK_MEDIUM
 *   score <  ZT_RISK_THRESHOLD_ABSOLUTE → ZT_RISK_HIGH
 *   score >= ZT_RISK_THRESHOLD_ABSOLUTE → ZT_RISK_CRITICAL
 *
 * If no evaluation has run yet, the score is 0 → SAFE (fail-open for
 * the risk tier; the state machine still starts BREACH/fail-closed).
 */
zt_risk_level_t zero_trust_get_risk_level(void)
{
    int score = g_zt.score.load(std::memory_order_acquire);

    if (score < ZT_RISK_THRESHOLD_MEDIUM) {
        return ZT_RISK_SAFE;
    } else if (score < ZT_RISK_THRESHOLD_HIGH) {
        return ZT_RISK_LOW;
    } else if (score < ZT_RISK_THRESHOLD_CRITICAL) {
        return ZT_RISK_MEDIUM;
    } else if (score < ZT_RISK_THRESHOLD_ABSOLUTE) {
        return ZT_RISK_HIGH;
    } else {
        return ZT_RISK_CRITICAL;
    }
}

const char* zero_trust_risk_level_name(zt_risk_level_t level)
{
    switch (level) {
        case ZT_RISK_SAFE:      return "SAFE";
        case ZT_RISK_LOW:       return "LOW";
        case ZT_RISK_MEDIUM:    return "MEDIUM";
        case ZT_RISK_HIGH:      return "HIGH";
        case ZT_RISK_CRITICAL:  return "CRITICAL";
        default:                return "UNKNOWN";
    }
}

const char* zero_trust_module_name(zt_module_id_t mod)
{
    switch (mod) {
        case ZT_MODULE_UNKNOWN:      return "unknown";
        case ZT_MODULE_APP:          return "app";
        case ZT_MODULE_SECURITY:     return "security";
        case ZT_MODULE_DATABASE:     return "database";
        case ZT_MODULE_NETWORK:      return "network";
        case ZT_MODULE_UI_COMMON:    return "ui-common";
        case ZT_MODULE_COMMON:       return "common";
        case ZT_MODULE_CHAT:         return "chat";
        case ZT_MODULE_COMPANION:    return "companion";
        case ZT_MODULE_GROUPCHAT:    return "groupchat";
        case ZT_MODULE_MEMORY:       return "memory";
        case ZT_MODULE_NOTIFICATION: return "notification";
        case ZT_MODULE_PROFILE:      return "profile";
        case ZT_MODULE_SETTINGS:     return "settings";
        case ZT_MODULE_UPDATE:       return "update";
        case ZT_MODULE_LOCALMODEL:   return "localmodel";
        case ZT_MODULE_NATIVE:       return "native";
        default:                     return "unknown";
    }
}

/* ==================================================================
 * JNI Audit Log Bridge
 *
 * Calls into AuditLogger.kt via JNI.
 * We store a cached JVM pointer from initialization.
 * ================================================================== */

/* JNI environment cache — set during zero_trust_init() */
static JavaVM*    g_zt_jvm    = NULL;
static jobject    g_zt_ctx    = NULL;
static jmethodID  g_zt_log_mid = NULL;

void zero_trust_audit_log(const char* level_str, int event_code, const char* message)
{
    if (!g_zt_jvm) return;

    JNIEnv* env = NULL;
    int get_env_result = g_zt_jvm->GetEnv(
        (void**)&env, JNI_VERSION_1_6);

    if (get_env_result == JNI_EDETACHED) {
        /* Thread not attached — attach temporarily */
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name    = "ZeroTrustAudit";
        args.group   = NULL;
        if (g_zt_jvm->AttachCurrentThread(&env, &args) != JNI_OK) {
            return;
        }
    } else if (get_env_result != JNI_OK || !env) {
        return;
    }

    if (!g_zt_log_mid) {
        /* Cache method ID on first call */
        jclass cls = env->FindClass(
            "com/yunian/ai/security/AuditLogger");
        if (!cls) {
            /* Class not found — native-only mode */
            g_zt_jvm->DetachCurrentThread();
            return;
        }
        jclass level_cls = env->FindClass(
            "com/yunian/ai/security/AuditLogger$Level");
        jclass event_cls = env->FindClass(
            "com/yunian/ai/security/AuditLogger$Event");

        if (level_cls && event_cls) {
            /* Find enum valueOf methods */
            jmethodID level_value_of = env->GetStaticMethodID(level_cls,
                "valueOf", "(Ljava/lang/String;)Lcom/yunian/ai/security/AuditLogger$Level;");
            jmethodID event_from_code = env->GetStaticMethodID(event_cls,
                "fromCode", "(I)Lcom/yunian/ai/security/AuditLogger$Event;");

            if (level_value_of && event_from_code) {
                g_zt_log_mid = env->GetStaticMethodID(cls, "log",
                    "(Landroid/content/Context;Lcom/yunian/ai/security/AuditLogger$Level;"
                    "Lcom/yunian/ai/security/AuditLogger$Event;"
                    "Ljava/lang/String;Ljava/lang/String;)"
                    "Lcom/yunian/ai/security/AuditLogger$Entry;");
            }
        }

        env->DeleteLocalRef(cls);
        if (level_cls) env->DeleteLocalRef(level_cls);
        if (event_cls) env->DeleteLocalRef(event_cls);
    }

    if (g_zt_log_mid && g_zt_ctx) {
        jstring level_js   = env->NewStringUTF(level_str);
        jstring message_js = env->NewStringUTF(message);

        /* Construct Event enum from code — use findClass + valueOf for int */
        jclass event_cls = env->FindClass(
            "com/yunian/ai/security/AuditLogger$Event");

        if (level_js && message_js && event_cls) {
            /* Use the Event.values() and index into it */
            jmethodID values_mid = env->GetStaticMethodID(event_cls,
                "values", "()[Lcom/yunian/ai/security/AuditLogger$Event;");
            if (values_mid) {
                jobjectArray events = (jobjectArray)env->CallStaticObjectMethod(
                    event_cls, values_mid);
                jsize event_count = env->GetArrayLength(events);

                /* Find matching event by code — iterate */
                jmethodID code_mid = env->GetMethodID(event_cls,
                    "getCode", "()I");
                jobject target_event = NULL;
                for (jsize i = 0; i < event_count; i++) {
                    jobject ev = env->GetObjectArrayElement(events, i);
                    jint ev_code = env->CallIntMethod(ev, code_mid);
                    if (ev_code == event_code) {
                        target_event = ev;
                        break;
                    }
                    env->DeleteLocalRef(ev);
                }

                if (target_event) {
                    jstring extra = env->NewStringUTF("");
                    env->CallStaticObjectMethod(
                        env->FindClass("com/yunian/ai/security/AuditLogger"),
                        g_zt_log_mid, g_zt_ctx, level_js, target_event,
                        message_js, extra);
                    env->DeleteLocalRef(extra);
                }
                env->DeleteLocalRef(events);
            }
            env->DeleteLocalRef(event_cls);
        }
        if (level_js)   env->DeleteLocalRef(level_js);
        if (message_js) env->DeleteLocalRef(message_js);
    }

    if (get_env_result == JNI_EDETACHED) {
        g_zt_jvm->DetachCurrentThread();
    }
}

/* ==================================================================
 * Detection Chain — Run All Checks
 * ================================================================== */

int zero_trust_run_detection_chain(void)
{
    int score = 0;
    int errors __attribute__((unused)) = 0;

    zt_breakdown_reset();

    /* 1. Root detection (weight: 3) */
    if (check_root()) {
        score += ZT_WEIGHT_ROOT;
        zt_breakdown_add("root:3");
    }
    if (check_magisk_props()) {
        score += ZT_WEIGHT_MAGISK;
        zt_breakdown_add("magisk_props:3");
    }

    /* 2. Hook detection (weight: 3) */
    if (check_hook_enhanced()) {
        score += ZT_WEIGHT_HOOK;
        zt_breakdown_add("hook:3");
    }

    /* 3. Signature verification (weight: 3)
     * g_sig_ok is set by JNI verifySignature() in native-bridge.cpp.
     * If signature is invalid, add full weight. */
    if (!g_sig_ok) {
        score += ZT_WEIGHT_SIGNATURE;
        zt_breakdown_add("signature:3");
    }

    /* 4. Emulator detection (weight: 2) */
    if (check_emulator()) {
        score += ZT_WEIGHT_EMULATOR;
        zt_breakdown_add("emulator:2");
    }

    /* 5. Debug detection (weight: 2) */
    if (check_debug_enhanced() || tp() > 0) {
        score += ZT_WEIGHT_DEBUG;
        zt_breakdown_add("debug:2");
    }

    /* 6. MITM detection (weight: 2 each, 3 checks)
     *
     * The user-CA check now returns a cert COUNT.  We score it only when
     * the count reaches ZT_MITM_CA_THRESHOLD (>= 2 user certs) OR when a
     * single cert coexists with an ACTIVE interception channel (proxy
     * port open or sniffing process) — a lone leftover dev-tool CA is
     * treated as benign so development devices are not false-flagged.
     */
    int mitm_proc = scan_proc_detect_mitm();
    int mitm_port = check_proxy_port();
    int mitm_ca   = check_user_ca_certs();

    if (mitm_proc) {
        score += ZT_WEIGHT_MITM_PROC;
        zt_breakdown_add("mitm_proc:2");
    }
    if (mitm_port) {
        score += ZT_WEIGHT_MITM_PORT;
        zt_breakdown_add("mitm_port:2");
    }
    if (mitm_ca >= ZT_MITM_CA_THRESHOLD) {
        score += ZT_WEIGHT_MITM_CA;
        zt_breakdown_add("mitm_ca:2");
    } else if (mitm_ca > 0 && (mitm_proc || mitm_port)) {
        score += ZT_WEIGHT_MITM_CA;
        zt_breakdown_add("mitm_ca:2(chan)");
    }

    /* 7. VPN/TUN detection (weight: 1) */
    if (detect_vpn_tun()) {
        score += ZT_WEIGHT_VPN;
        zt_breakdown_add("vpn_tun:1");
    }

    /* 8. Frida files (weight: 3) */
    if (check_frida_files()) {
        score += ZT_WEIGHT_FRIDA_FILES;
        zt_breakdown_add("frida_files:3");
    }

    /* 9. Magisk mounts (weight: 2) */
    if (check_magisk_mounts()) {
        score += ZT_WEIGHT_MAGISK_MOUNTS;
        zt_breakdown_add("magisk_mounts:2");
    }

    /* 10. SELinux permissive (weight: 2) */
    if (check_selinux_permissive()) {
        score += ZT_WEIGHT_SELINUX;
        zt_breakdown_add("selinux:2");
    }

    /* 11. Ptrace scope (weight: 1) */
    if (check_ptrace_scope()) {
        score += ZT_WEIGHT_PTRACE;
        zt_breakdown_add("ptrace:1");
    }

    /* 12. Debuggable build props (weight: 2) */
    if (check_debuggable_props()) {
        score += ZT_WEIGHT_DEBUGGABLE;
        zt_breakdown_add("debuggable:2");
    }

    /* 13. Library injection (weight: 3) */
    if (check_library_injection()) {
        score += ZT_WEIGHT_LIB_INJECT;
        zt_breakdown_add("lib_inject:3");
    }

    /* 14. Virtual environment (weight: 2) */
    if (check_virtual_env()) {
        score += ZT_WEIGHT_VIRTUAL;
        zt_breakdown_add("virtual_env:2");
    }

    /* 15. Tampered time (weight: 1) */
    if (check_tampered_time()) {
        score += ZT_WEIGHT_TIME_TAMPER;
        zt_breakdown_add("time_tamper:1");
    }

    /* 16. Zygisk modules (weight: 3) */
    if (check_zygisk_modules()) {
        score += ZT_WEIGHT_ZYGISK;
        zt_breakdown_add("zygisk:3");
    }

    /* 17. Kernel modules (weight: 2) */
    if (check_kernel_modules()) {
        score += ZT_WEIGHT_KERNEL_MOD;
        zt_breakdown_add("kernel_mod:2");
    }

    /* 18. Native bridge (weight: 1) */
    if (check_native_bridge()) {
        score += ZT_WEIGHT_NATIVE_BRIDGE;
        zt_breakdown_add("native_bridge:1");
    }

    /* 19. Bootloader state (weight: 2) */
    if (check_bootloader()) {
        score += ZT_WEIGHT_BOOTLOADER;
        zt_breakdown_add("bootloader:2");
    }

    /* 20. Emulator sensors (weight: 2) */
    if (check_emulator_sensors()) {
        score += ZT_WEIGHT_EMU_SENSORS;
        zt_breakdown_add("emu_sensors:2");
    }

    /* 21. TCP connections — frida ports (weight: 2) */
    if (check_proc_net_tcp_conn()) {
        score += ZT_WEIGHT_TCP_CONN;
        zt_breakdown_add("tcp_conn:2");
    }

    /* 22. Overlay attack (weight: 1) */
    if (check_overlay_attack()) {
        score += ZT_WEIGHT_OVERLAY;
        zt_breakdown_add("overlay:1");
    }

    /* 23. System fingerprint (weight: 2) */
    if (check_system_fingerprint()) {
        score += ZT_WEIGHT_SYS_FP;
        zt_breakdown_add("sys_fp:2");
    }

    /* 24. Telephony emulator (weight: 2) */
    if (check_telephony_emulator()) {
        score += ZT_WEIGHT_TELEPHONY;
        zt_breakdown_add("telephony:2");
    }

    /* 25. Ring-0 maps (weight: 1) */
    if (check_ring0_maps()) {
        score += ZT_WEIGHT_RING0;
        zt_breakdown_add("ring0:1");
    }

    /* 26. Error counter escalation */
    int prev_errors = atomic_load_explicit(&g_zt.error_count,
        std::memory_order_relaxed);
    if (prev_errors >= ZT_ERROR_THRESHOLD) {
        score += (prev_errors / ZT_ERROR_THRESHOLD) * ZT_WEIGHT_ERROR_PER_3;
        zt_breakdown_add("error_count:+");
    }

    /* 27. DEX integrity (weight: 3) */
    if (check_dex_integrity() == 0) {
        score += 3;
        zt_breakdown_add("dex_integrity:3");
    }

    /* 28. SO .text section integrity (weight: 3) */
    if (check_so_integrity() == 0) {
        score += 3;
        zt_breakdown_add("so_integrity:3");
    }

    /* 29. Resources integrity (weight: 2) */
    if (check_resources_integrity() == 0) {
        score += 2;
        zt_breakdown_add("res_integrity:2");
    }

    return score;
}

/* ==================================================================
 * State Machine — Score → Trust State
 * ================================================================== */

/**
 * Map a raw threat score to a trust state.
 *
 *   0       → ZT_TRUST     (clean device)
 *   1–5     → ZT_SUSPICIOUS (minor flags: e.g. ptrace_scope=1, native_bridge, etc.)
 *   6+      → ZT_BREACH     (serious compromise detected)
 *
 * If already in BREACH state, requires explicit reset() to leave.
 * Max theoretical score: ~74 (32 checks × weighted)
 */
static zt_trust_state_t zt_score_to_state(int score,
    zt_trust_state_t current_state, bool has_completed_evaluation)
{
    /* An observed breach is sticky. The initial fail-closed BREACH state is
     * intentionally not sticky so the first completed evaluation can establish
     * the device's actual trust state. */
    if (current_state == ZT_BREACH && has_completed_evaluation) {
        return ZT_BREACH;
    }

    if (score == 0) {
        return ZT_TRUST;
    } else if (score < ZT_RISK_THRESHOLD_CRITICAL) {
        return ZT_SUSPICIOUS;
    } else {
        return ZT_BREACH;
    }
}

/* ==================================================================
 * PDP — Evaluate
 * ================================================================== */

zt_trust_state_t zero_trust_evaluate(void)
{
    uint64_t now = zt_now_ms();
    int score = 0;
    zt_trust_state_t old_state, new_state;
    bool has_completed_evaluation;

    /* 1. Run detection chain */
    score = zero_trust_run_detection_chain();

    /* 2. Store score */
    g_zt.score.store(score, std::memory_order_release);

    /* 3. Update state */
    old_state = (zt_trust_state_t)atomic_load_explicit(
        &g_zt.state, std::memory_order_acquire);
    has_completed_evaluation = g_zt.last_eval_ms.load(std::memory_order_acquire) != 0;
    new_state = zt_score_to_state(score, old_state, has_completed_evaluation);
    g_zt.state.store((int)new_state, std::memory_order_release);

    /* 4. Update timestamp */
    g_zt.last_eval_ms.store(now, std::memory_order_release);

    /* 5. Execute incident response on state change */
    if (new_state != old_state) {
        switch (new_state) {
            case ZT_TRUST:
                /* Transition to trust — reset all flags */
                g_zt.degraded.store(0, std::memory_order_release);
                g_zt.locked.store(0, std::memory_order_release);
                g_zt.error_count.store(0, std::memory_order_release);
                zero_trust_audit_log("INFO", 100, /* APP_START */
                    "Zero Trust: state=TRUST, all clear");
                break;

            case ZT_SUSPICIOUS:
                g_zt.degraded.store(1, std::memory_order_release);
                g_zt.locked.store(0, std::memory_order_release);
                zero_trust_incident_response(ZT_ACTION_DEGRADE);
                zero_trust_audit_log("WARNING", 206, /* THREAT_HIGH */
                    "Zero Trust: state=SUSPICIOUS, degrading features");
                break;

            case ZT_BREACH:
                g_zt.degraded.store(0, std::memory_order_release);
                g_zt.locked.store(1, std::memory_order_release);
                zero_trust_incident_response(ZT_ACTION_WIPE);
                zero_trust_silent_fail();
                zero_trust_audit_log("CRITICAL", 502, /* TAMPER_DETECTED */
                    "Zero Trust: state=BREACH, locked and wiped");
                break;
        }
    }

    zt_mark_initial_eval_complete();

    return new_state;
}

/* ==================================================================
 * PDP — Get State (Cached or On-Demand)
 * ================================================================== */

zt_trust_state_t zero_trust_get_state(void)
{
    uint64_t last = atomic_load_explicit(&g_zt.last_eval_ms,
        std::memory_order_acquire);
    uint64_t now  = zt_now_ms();

    /* If cache is stale (older than 100ms), re-evaluate */
    if (now - last > ZT_MAX_CACHE_AGE_MS) {
        return zero_trust_evaluate();
    }

    return (zt_trust_state_t)atomic_load_explicit(
        &g_zt.state, std::memory_order_acquire);
}

int zero_trust_get_score(void)
{
    return g_zt.score.load(std::memory_order_acquire);
}

/* C interface for the score breakdown string (used by native-bridge.cpp
 * RN wrapper and by the JNI zeroTrustGetScoreBreakdown export). Returns a
 * pointer to a static buffer; caller must copy it immediately. */
extern "C" const char* zero_trust_get_score_breakdown_str(void)
{
    pthread_mutex_lock(&g_zt_breakdown_mutex);
    const char* p = g_zt_breakdown_buf;
    pthread_mutex_unlock(&g_zt_breakdown_mutex);
    return p;
}

uint64_t zero_trust_last_eval_ms(void)
{
    return g_zt.last_eval_ms.load(std::memory_order_acquire);
}

/* ==================================================================
 * Module Access Control Matrix (PEP)
 *
 * This 2D array defines which source modules can access which target
 * modules, and under what trust conditions.
 *
 * For each (source, target) pair:
 *   0 = no access (denied always)
 *   1 = full access when TRUST, degraded when SUSPICIOUS, denied when BREACH
 *   2 = full access regardless of trust state (critical infra only)
 * ================================================================== */

/*
 * Access matrix: rows = source modules, cols = target modules.
 * Index into the matrix using the zt_module_id_t values directly.
 *
 * For simplicity, we define a compact policy per (source, target) pair.
 * The matrix is _ZT_MODULE_COUNT x _ZT_MODULE_COUNT.
 *
 * 0 = DENIED (no dependency exists or forbidden)
 * 1 = ALLOWED (respects trust state: TRUST=full, SUSPICIOUS=degraded, BREACH=denied)
 * 2 = ALWAYS (bypasses PEP — only for security module self-access)
 */

/* We only define rules for valid combinations. All other entries default to 0. */

/** Check if module pair (source, target) is a valid dependency */
static int zt_is_valid_dependency(zt_module_id_t source, zt_module_id_t target)
{
    /* --- app module --- */
    if (source == ZT_MODULE_APP) {
        return (target >= ZT_MODULE_CHAT && target <= ZT_MODULE_LOCALMODEL) ||
               (target == ZT_MODULE_SECURITY) ||
               (target == ZT_MODULE_DATABASE) ||
               (target == ZT_MODULE_NETWORK) ||
               (target == ZT_MODULE_UI_COMMON);
    }

    /* --- feature modules --- */
    if (source >= ZT_MODULE_CHAT && source <= ZT_MODULE_LOCALMODEL) {
        return (target == ZT_MODULE_DATABASE) ||
               (target == ZT_MODULE_NETWORK) ||
               (target == ZT_MODULE_SECURITY) ||
               (target == ZT_MODULE_UI_COMMON) ||
               (target == ZT_MODULE_COMMON);
    }

    /* --- core modules --- */
    if (source == ZT_MODULE_NETWORK) {
        return (target == ZT_MODULE_DATABASE) ||
               (target == ZT_MODULE_COMMON);
    }
    if (source == ZT_MODULE_SECURITY) {
        return (target == ZT_MODULE_COMMON) ||
               (target == ZT_MODULE_NATIVE) ||
               (target == ZT_MODULE_SECURITY);  /* self-access */
    }
    if (source == ZT_MODULE_DATABASE) {
        return (target == ZT_MODULE_COMMON);
    }
    if (source == ZT_MODULE_UI_COMMON) {
        return (target == ZT_MODULE_COMMON);
    }

    return 0;
}

/* ==================================================================
 * PEP — Check Access
 * ================================================================== */

zt_access_decision_t zero_trust_check_access(
    zt_module_id_t source,
    zt_module_id_t target,
    zt_operation_t op)
{
    /* 1. Check if the dependency is valid */
    if (!zt_is_valid_dependency(source, target)) {
        return ZT_ACCESS_DENIED;
    }

    /* 2. Security module self-access is always allowed */
    if (source == ZT_MODULE_SECURITY && target == ZT_MODULE_SECURITY) {
        return ZT_ACCESS_GRANTED;
    }

    /* Security module accessing native is always allowed */
    if (source == ZT_MODULE_SECURITY && target == ZT_MODULE_NATIVE) {
        return ZT_ACCESS_GRANTED;
    }

    /* 3. Check trust state */
    zt_trust_state_t state = zero_trust_get_state();

    switch (state) {
        case ZT_TRUST:
            return ZT_ACCESS_GRANTED;

        case ZT_SUSPICIOUS:
            /* In suspicious state:
             *   - Read operations are degraded (allowed with limits)
             *   - Write/Execute/Admin operations are denied */
            if (op == ZT_OP_READ) {
                return ZT_ACCESS_DEGRADED;
            }
            return ZT_ACCESS_DENIED;

        case ZT_BREACH:
        default:
            return ZT_ACCESS_DENIED;
    }
}

int zero_trust_is_allowed(zt_module_id_t module, zt_operation_t op)
{
    zt_access_decision_t decision = zero_trust_check_access(
        module, ZT_MODULE_DATABASE, op);
    return (decision == ZT_ACCESS_GRANTED) ? 1 : 0;
}

int zero_trust_is_write_allowed(zt_module_id_t module)
{
    return zero_trust_is_allowed(module, ZT_OP_WRITE);
}

/* ==================================================================
 * Incident Response
 * ================================================================== */

void zero_trust_incident_response(zt_response_action_t action)
{
    switch (action) {
        case ZT_ACTION_NONE:
            /* No-op — already in trust state */
            break;

        case ZT_ACTION_DEGRADE:
            /* Set degraded mode flags.
             * The Kotlin layer reads these via JNI to disable features. */
            g_zt.degraded.store(1, std::memory_order_release);
            break;

        case ZT_ACTION_LOCK:
            /* Lock all access.
             * All PEP checks will now return DENIED.
             * The lock flag prevents any bypass. */
            g_zt.locked.store(1, std::memory_order_release);
            g_zt.degraded.store(0, std::memory_order_release);
            break;

        case ZT_ACTION_WIPE:
            /* Lock + wipe all cryptographic keys.
             * This is the nuclear option — zeroes all key material. */
            g_zt.locked.store(1, std::memory_order_release);
            g_zt.degraded.store(0, std::memory_order_release);
            zero_trust_wipe_all_keys();
            g_zt.keys_wiped.store(1, std::memory_order_release);
            break;
    }
}

void zero_trust_silent_fail(void)
{
    /* Silent fail: the system is locked but appears functional.
     *
     * Implementation notes:
     *   1. All PEP checks return DENIED.
     *   2. The Kotlin SecurityGuard will return false for isSafe().
     *   3. No crash, no dialog, no error toast.
     *   4. Network interceptor returns 403-looking empty responses.
     *   5. The attacker cannot distinguish "locked" from "network error."
     *
     * This function mainly ensures the lock flags are set.
     * Silent behavior is enforced by the Kotlin side reading
     * zero_trust_get_state() and checking g_zt.locked.
     */
    g_zt.locked.store(1, std::memory_order_release);
    g_zt.degraded.store(0, std::memory_order_release);
}

/* ==================================================================
 * Key Wipe
 * ================================================================== */

/**
 * Wipe all in-memory keys.
 *
 * This function zeroes:
 *   1. Any cached key material in this module's static buffers
 *   2. Calls into whitebox-aes.cpp to wipe its key tables
 *   3. Calls into vm-engine.cpp to wipe its key cache
 *
 * The whitebox-aes and vm-engine wipe functions are declared here
 * as extern and implemented in their respective modules.
 */
extern "C" void wb_aes_wipe_keys(void);
extern "C" void vm_engine_wipe_cache(void);

void zero_trust_wipe_all_keys(void)
{
    /* Wipe our own static buffers (if any key material was stored) */
    /* (Currently no key buffers in zero-trust.cpp itself) */

    /* Wipe white-box AES key tables */
    if (&wb_aes_wipe_keys) {
        wb_aes_wipe_keys();
    }

    /* Wipe VM engine key cache */
    if (&vm_engine_wipe_cache) {
        vm_engine_wipe_cache();
    }

    /* Memory barrier to ensure all wipes complete */
#if defined(__arm__) || defined(__aarch64__)
    __asm__ __volatile__("dmb sy" ::: "memory");
#else
    __sync_synchronize();
#endif
}

/* ==================================================================
 * Continuous Evaluation Loop
 * ================================================================== */

/**
 * Background thread function.
 * Runs zero_trust_evaluate() every 100ms.
 */
static void* zt_eval_loop(void* arg)
{
    (void)arg;

    while (g_zt.eval_running.load(std::memory_order_acquire)) {
        uint64_t start = zt_now_ms();

        /* Run evaluation */
        zero_trust_evaluate();

        /* Calculate sleep time to maintain 100ms interval */
        uint64_t elapsed = zt_now_ms() - start;
        if (elapsed < ZT_EVAL_INTERVAL_MS) {
            struct timespec ts;
            ts.tv_sec  = 0;
            ts.tv_nsec = (long)((ZT_EVAL_INTERVAL_MS - elapsed) * 1000000ULL);
            nanosleep(&ts, NULL);
        }
        /* If evaluation took >= 100ms, run again immediately */
    }

    return NULL;
}

int zero_trust_start_continuous_eval(void)
{
    int expected = 0;
    if (!atomic_compare_exchange_strong(
            &g_zt.eval_running, &expected, 1)) {
        return -1;  /* Already running */
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    int ret = pthread_create(&g_zt.eval_thread, &attr, zt_eval_loop, NULL);
    pthread_attr_destroy(&attr);

    if (ret != 0) {
        g_zt.eval_running.store(0, std::memory_order_release);
        return -1;
    }

    return 0;
}

int zero_trust_wait_for_initial_evaluation(uint32_t timeout_ms)
{
    if (g_zt.initial_eval_complete.load(std::memory_order_acquire)) {
        return 0;
    }

    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec += 1;
        deadline.tv_nsec -= 1000000000L;
    }

    pthread_mutex_lock(&g_zt_initial_eval_mutex);
    int result = 0;
    while (!g_zt.initial_eval_complete.load(std::memory_order_acquire) && result == 0) {
        result = pthread_cond_timedwait(
            &g_zt_initial_eval_cond,
            &g_zt_initial_eval_mutex,
            &deadline
        );
    }
    pthread_mutex_unlock(&g_zt_initial_eval_mutex);

    if (!g_zt.initial_eval_complete.load(std::memory_order_acquire)) {
        return -1;
    }
    return 0;
}

void zero_trust_stop_continuous_eval(void)
{
    int was_running = atomic_exchange_explicit(
        &g_zt.eval_running, 0, std::memory_order_release);
    if (was_running) {
        pthread_join(g_zt.eval_thread, NULL);
    }
}

int zero_trust_is_continuous_eval_running(void)
{
    return g_zt.eval_running.load(std::memory_order_acquire);
}

/* ==================================================================
 * Init / Reset
 * ================================================================== */

void zero_trust_init(void)
{
    /* Set initial state to BREACH (default deny per Fail Secure tenet) */
    g_zt.state.store(ZT_BREACH, std::memory_order_release);
    g_zt.score.store(0, std::memory_order_release);
    g_zt.degraded.store(0, std::memory_order_release);
    g_zt.locked.store(1, std::memory_order_release);
    g_zt.keys_wiped.store(0, std::memory_order_release);
    g_zt.error_count.store(0, std::memory_order_release);
    g_zt.initial_eval_complete.store(0, std::memory_order_release);

    /* Evaluation starts only after APK signature verification succeeds.
     * Before that, a zero signature result means "not yet verified", not
     * "verification failed", and the default-deny lock remains in effect. */
}

void zero_trust_reset(void)
{
    /* Stop continuous eval first */
    zero_trust_stop_continuous_eval();

    /* Reset to BREACH (default deny) */
    g_zt.state.store(ZT_BREACH, std::memory_order_release);
    g_zt.score.store(0, std::memory_order_release);
    g_zt.last_eval_ms.store(0, std::memory_order_release);
    g_zt.degraded.store(0, std::memory_order_release);
    g_zt.locked.store(1, std::memory_order_release);
    g_zt.keys_wiped.store(0, std::memory_order_release);
    g_zt.error_count.store(0, std::memory_order_release);
    g_zt.initial_eval_complete.store(0, std::memory_order_release);
}

/* ==================================================================
 * JNI Integration Helper
 *
 * Called from native-bridge.cpp or the JNI_OnLoad to provide the
 * JavaVM and context for audit logging.
 * ================================================================== */

void zero_trust_set_jvm(JavaVM* jvm, jobject context)
{
    g_zt_jvm = jvm;
    if (context) {
        JNIEnv* env;
        if (jvm->GetEnv( (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
            g_zt_ctx = env->NewGlobalRef(context);
        }
    }
}

/* ==================================================================
 * JNI Exposed Functions
 *
 * These are called from SecurityGuard.kt via JNI.
 * They bridge the Kotlin security layer to the native zero-trust system.
 * ================================================================== */

/* JNI function registration is handled in native-bridge.cpp's
 * JNI_OnLoad or via @JvmStatic externals. The following functions
 * are the JNI entry points for the zero-trust system. */

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustEvaluate
 *
 * Called from SecurityGuard.kt when isSafe() is invoked.
 * Returns the current trust state as an integer:
 *   0 = TRUST, 1 = SUSPICIOUS, 2 = BREACH
 */
jint JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustEvaluate(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)zero_trust_evaluate();
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustGetState
 *
 * Returns current trust state without forcing re-evaluation.
 */
jint JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustGetState(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)zero_trust_get_state();
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScore
 *
 * Returns the raw threat score.
 */
jint JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScore(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)zero_trust_get_score();
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScoreBreakdown
 *
 * Returns a comma-separated list of detection items that contributed
 * to the current threat score (e.g. "root:3,emulator:2"). Used for
 * diagnostics to pinpoint the exact score source.
 */
jstring JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustGetScoreBreakdown(
    JNIEnv* env, jclass clazz)
{
    (void)clazz;
    pthread_mutex_lock(&g_zt_breakdown_mutex);
    jstring ret = env->NewStringUTF(g_zt_breakdown_buf);
    pthread_mutex_unlock(&g_zt_breakdown_mutex);
    return ret;
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustGetRiskLevel
 *
 * Returns the current client risk level derived from the raw threat
 * score using the configurable ZT_RISK_THRESHOLD_* knobs:
 *   0 = SAFE, 1 = LOW, 2 = MEDIUM, 3 = HIGH, 4 = CRITICAL
 *
 * Sensitive-operation gates compare this tier against an allowed
 * maximum risk threshold (see SecurityState.SENSITIVE_OPS_MAX_RISK).
 */
jint JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustGetRiskLevel(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)zero_trust_get_risk_level();
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustIsDegraded
 *
 * Returns 1 if the system is in degraded mode (SUSPICIOUS state).
 */
jint JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustIsDegraded(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)g_zt.degraded.load(std::memory_order_acquire);
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustIsLocked
 *
 * Returns 1 if the system is locked (BREACH state).
 */
jint JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustIsLocked(
    JNIEnv* env, jclass clazz)
{
    (void)env; (void)clazz;
    return (jint)g_zt.locked.load(std::memory_order_acquire);
}

/**
 * JNI: Java_com_yunian_ai_security_NativeBridge_zeroTrustInit
 *
 * Initialize the zero-trust system. Called at app startup.
 */
void JNICALL Java_com_yunian_ai_security_NativeBridge_zeroTrustInit(
    JNIEnv* env, jclass clazz)
{
    (void)clazz;
    zero_trust_set_jvm(NULL, NULL);
    /* Store JavaVM for audit logging */
    env->GetJavaVM(&g_zt_jvm);
    zero_trust_init();
}
