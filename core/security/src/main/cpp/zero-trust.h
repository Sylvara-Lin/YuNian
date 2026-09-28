/*
 * zero-trust.h — YuNian Zero Trust Framework
 *
 * Policy Decision Point (PDP) and Policy Enforcement Point (PEP)
 * for the YuNian Android security subsystem.
 *
 * This header defines the interface for:
 *   - Trust score evaluation
 *   - State machine (TRUST / SUSPICIOUS / BREACH)
 *   - Inter-module access control
 *   - Incident response actions (degrade, lock, wipe)
 *
 * Integrates with native-bridge.cpp detection functions and
 * AuditLogger.kt (via JNI bridge).
 *
 * Thread-safety: all public functions are atomic-operation safe.
 * No mutexes on the fast path.
 */

#ifndef YUNIAN_ZERO_TRUST_H
#define YUNIAN_ZERO_TRUST_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ==================================================================
 * Trust State Enumeration
 * ================================================================== */

/**
 * Trust state machine states.
 * Maps directly to the 3-tier scoring model:
 *   0       → ZT_TRUST
 *   1–2     → ZT_SUSPICIOUS
 *   3+      → ZT_BREACH
 */
typedef enum {
    ZT_TRUST       = 0,  /**< Score=0:  full access, all features */
    ZT_SUSPICIOUS  = 1,  /**< Score=1-2: degraded, warnings shown */
    ZT_BREACH      = 2,  /**< Score>=3:  locked, keys wiped, silent fail */
} zt_trust_state_t;

/* ==================================================================
 * Client Risk Level — Threshold-Based Risk Assessment
 * ================================================================== */

/**
 * Fine-grained risk tier derived from the raw threat score.
 *
 * This is the primary "how risky is this client right now" signal
 * consumed by sensitive-operation gates.  The raw score is bucketed
 * into 5 tiers using the configurable thresholds below, so policy
 * decisions (cloud access, secret decrypt, session use) can be made
 * against a risk LEVEL instead of an opaque integer.
 */
typedef enum {
    ZT_RISK_SAFE      = 0,  /**< score == 0: clean device, full trust */
    ZT_RISK_LOW       = 1,  /**< score 1-2: minor flags (ptrace_scope, vpn) */
    ZT_RISK_MEDIUM    = 2,  /**< score 3-5: suspicious, degrade features */
    ZT_RISK_HIGH      = 3,  /**< score 6-9: likely compromised, lock sensitive */
    ZT_RISK_CRITICAL  = 4,  /**< score >= 10: definite breach, full lock */
} zt_risk_level_t;

/**
 * Risk thresholds — configurable knobs for the scoring model.
 * Lowering them makes the system more conservative (more clients
 * flagged), raising them makes it more permissive.
 *
 *   score <  ZT_RISK_THRESHOLD_MEDIUM  → SAFE
 *   score <  ZT_RISK_THRESHOLD_HIGH    → LOW (if > 0) / MEDIUM
 *   score <  ZT_RISK_THRESHOLD_CRITICAL→ HIGH
 *   score >= ZT_RISK_THRESHOLD_CRITICAL→ CRITICAL
 */
#define ZT_RISK_THRESHOLD_MEDIUM   1   /* score >= 1  → at least LOW      */
#define ZT_RISK_THRESHOLD_HIGH     3   /* score >= 3  → MEDIUM            */
#define ZT_RISK_THRESHOLD_CRITICAL 6   /* score >= 6  → HIGH              */
#define ZT_RISK_THRESHOLD_ABSOLUTE 10  /* score >= 10 → CRITICAL          */

/* ==================================================================
 * Access Decision Enumeration
 * ================================================================== */

/**
 * Result of a Policy Enforcement Point (PEP) access check.
 * Returned by zero_trust_check_access().
 */
typedef enum {
    ZT_ACCESS_DENIED     = 0,  /**< Block access (BREACH or unknown module) */
    ZT_ACCESS_GRANTED    = 1,  /**< Full access (TRUST state) */
    ZT_ACCESS_DEGRADED   = 2,  /**< Partial/read-only access (SUSPICIOUS state) */
} zt_access_decision_t;

/* ==================================================================
 * Response Action Enumeration
 * ================================================================== */

/**
 * Actions that the incident response system can execute.
 */
typedef enum {
    ZT_ACTION_NONE    = 0,  /**< No action (TRUST state) */
    ZT_ACTION_DEGRADE = 1,  /**< Degrade features, limit to read-only (SUSPICIOUS) */
    ZT_ACTION_LOCK    = 2,  /**< Lock all access, deny everything (BREACH) */
    ZT_ACTION_WIPE    = 3,  /**< Wipe cryptographic keys + lock (BREACH) */
} zt_response_action_t;

/* ==================================================================
 * Module Identifier
 * ================================================================== */

/**
 * YuNian module identifiers for micro-segmentation.
 * Used by PEP to determine inter-module access policies.
 */
typedef enum {
    ZT_MODULE_UNKNOWN      = 0,
    ZT_MODULE_APP          = 1,   /**< :app entry point */
    ZT_MODULE_SECURITY     = 2,   /**< core:security */
    ZT_MODULE_DATABASE     = 3,   /**< core:database */
    ZT_MODULE_NETWORK      = 4,   /**< core:network */
    ZT_MODULE_UI_COMMON    = 5,   /**< core:ui-common */
    ZT_MODULE_COMMON       = 6,   /**< core:common */
    ZT_MODULE_CHAT         = 7,   /**< feature:chat */
    ZT_MODULE_COMPANION    = 8,   /**< feature:companion */
    ZT_MODULE_GROUPCHAT    = 9,   /**< feature:groupchat */
    ZT_MODULE_MEMORY       = 10,  /**< feature:memory */
    ZT_MODULE_NOTIFICATION = 11,  /**< feature:notification */
    ZT_MODULE_PROFILE      = 12,  /**< feature:profile */
    ZT_MODULE_SETTINGS     = 13,  /**< feature:settings */
    ZT_MODULE_UPDATE       = 14,  /**< feature:update */
    ZT_MODULE_LOCALMODEL   = 15,  /**< feature:localmodel */
    ZT_MODULE_NATIVE       = 16,  /**< native liblianyu_security.so internal */
    _ZT_MODULE_COUNT,
} zt_module_id_t;

/* ==================================================================
 * Operation Types (for PEP granularity)
 * ================================================================== */

typedef enum {
    ZT_OP_READ      = 0,  /**< Read operation */
    ZT_OP_WRITE     = 1,  /**< Write/create/update/delete operation */
    ZT_OP_EXECUTE   = 2,  /**< Execute/run operation */
    ZT_OP_ADMIN     = 3,  /**< Administrative/configuration operation */
} zt_operation_t;

/* ==================================================================
 * Policy Decision Point (PDP) — Public Interface
 * ================================================================== */

/**
 * Initialize the zero-trust system.
 * Must be called once at app startup (YuNianApplication.onCreate).
 * Sets initial state to ZT_BREACH (default deny) until evaluate() passes.
 *
 * Thread-safety: NOT thread-safe. Call once from main thread.
 */
void zero_trust_init(void);

/**
 * Run the full detection chain and update the trust score.
 * Called every 100ms by the continuous evaluation loop, or
 * on-demand before any sensitive operation.
 *
 * This function:
 *   1. Runs all detection checks (root, hook, emulator, debug, MITM, etc.)
 *   2. Applies weighted scoring matrix
 *   3. Updates internal trust state
 *   4. Logs state transitions via the JNI audit bridge
 *   5. Returns the current trust state
 *
 * Thread-safety: Thread-safe. Uses atomic loads/stores.
 *
 * @return Current trust state after evaluation.
 */
zt_trust_state_t zero_trust_evaluate(void);

/**
 * Get the current trust state without re-evaluating.
 * Uses cached score if evaluated within the last 100ms.
 * If the cache is stale, triggers an on-demand evaluate().
 *
 * Thread-safety: Thread-safe (atomic read).
 *
 * @return Current trust state.
 */
zt_trust_state_t zero_trust_get_state(void);

/**
 * Get the raw threat score (0-30).
 * Useful for diagnostic/debug UIs.
 *
 * @return Latest calculated threat score.
 */
int zero_trust_get_score(void);

/**
 * Get the last evaluation timestamp (monotonic clock, milliseconds).
 *
 * @return Timestamp of last evaluate() call.
 */
uint64_t zero_trust_last_eval_ms(void);

/* ==================================================================
 * Policy Enforcement Point (PEP) — Access Control
 * ================================================================== */

/**
 * Check whether a source module can access a target module
 * for a given operation type.
 *
 * The PEP uses:
 *   1. Current trust state (from zero_trust_get_state())
 *   2. Module-level ACL matrix (defined in zero-trust.cpp)
 *   3. Operation type granularity
 *
 * Thread-safety: Thread-safe.
 *
 * @param source   Calling module identifier.
 * @param target   Target module identifier.
 * @param op       Operation type (read/write/execute/admin).
 * @return Access decision: GRANTED, DEGRADED, or DENIED.
 */
zt_access_decision_t zero_trust_check_access(
    zt_module_id_t source,
    zt_module_id_t target,
    zt_operation_t op);

/**
 * Convenience: check if the current trust state allows
 * a specific feature to operate.
 *
 * @param module   The feature module requesting action.
 * @param op       The operation type.
 * @return 1 if allowed, 0 if denied or degraded.
 */
int zero_trust_is_allowed(zt_module_id_t module, zt_operation_t op);

/**
 * Convenience: check if the current trust state allows
 * write operations for a module.
 *
 * @param module   The feature module requesting write.
 * @return 1 if write allowed (TRUST), 0 if degraded or denied.
 */
int zero_trust_is_write_allowed(zt_module_id_t module);

/* ==================================================================
 * Continuous Evaluation Control
 * ================================================================== */

/**
 * Start the continuous evaluation loop.
 * Spawns a background thread that calls zero_trust_evaluate()
 * every 100ms.
 *
 * @return 0 on success, -1 if already running.
 */
int zero_trust_start_continuous_eval(void);

/**
 * Wait for the first background evaluation to establish the initial state.
 * Returns 0 on completion and -1 on timeout.
 */
int zero_trust_wait_for_initial_evaluation(uint32_t timeout_ms);

/**
 * Stop the continuous evaluation loop.
 * Signals the background thread to exit and joins it.
 */
void zero_trust_stop_continuous_eval(void);

/**
 * Check if continuous evaluation is currently running.
 *
 * @return 1 if running, 0 if stopped.
 */
int zero_trust_is_continuous_eval_running(void);

/* ==================================================================
 * Incident Response
 * ================================================================== */

/**
 * Execute an incident response action based on the current trust state.
 * This is called automatically by zero_trust_evaluate() when
 * the trust state changes, but can also be invoked manually.
 *
 * @param action  The response action to execute.
 *                ZT_ACTION_NONE    → no-op
 *                ZT_ACTION_DEGRADE → set degraded mode flags
 *                ZT_ACTION_LOCK    → set lock flags, deny all PEP checks
 *                ZT_ACTION_WIPE    → lock + zero cryptographic key memory
 */
void zero_trust_incident_response(zt_response_action_t action);

/**
 * Execute silent fail mode.
 * The app continues rendering UI but all operations return
 * empty/no-op results. The attacker cannot distinguish between
 * "app is working but network is down" and "app is locked."
 *
 * Called automatically on BREACH state entry.
 */
void zero_trust_silent_fail(void);

/* ==================================================================
 * Key Management (Wipe)
 * ================================================================== */

/**
 * Safely zero a memory region.
 * Uses volatile pointer + compiler barrier to prevent optimizer
 * from eliding the clear.
 *
 * @param ptr  Pointer to memory to wipe.
 * @param n    Number of bytes to zero.
 */
void zero_trust_wipe_memory(void* ptr, size_t n);

/**
 * Wipe all in-memory session keys and cryptographic material.
 * Called automatically during ZT_ACTION_WIPE.
 * Must be implemented by the key management module.
 */
void zero_trust_wipe_all_keys(void);

/* ==================================================================
 * Debug / Diagnostic
 * ================================================================== */

/**
 * Get a human-readable string for a trust state.
 *
 * @param state  The trust state.
 * @return String constant: "TRUST", "SUSPICIOUS", or "BREACH".
 */
const char* zero_trust_state_name(zt_trust_state_t state);

/**
 * Map the current raw threat score to a risk level.
 *
 * Thresholds are configurable via ZT_RISK_THRESHOLD_* macros in
 * zero-trust.h.  This is the canonical "client risk" signal for
 * sensitive-operation policy decisions.
 *
 * @return Current zt_risk_level_t tier (0-4).
 */
zt_risk_level_t zero_trust_get_risk_level(void);

/**
 * Get a human-readable string for a risk level.
 *
 * @param level  The risk level.
 * @return String constant: "SAFE", "LOW", "MEDIUM", "HIGH", "CRITICAL".
 */
const char* zero_trust_risk_level_name(zt_risk_level_t level);

/** Convenience: check if system is in locked (BREACH) state. */
int zero_trust_is_locked(void);

/** Convenience: check if system is in degraded (SUSPICIOUS) state. */
int zero_trust_is_degraded(void);

/**
 * Get a human-readable string for a module ID.
 *
 * @param mod  The module identifier.
 * @return String constant: module name (e.g., "app", "chat", "database").
 */
const char* zero_trust_module_name(zt_module_id_t mod);

/**
 * Reset the zero-trust system.
 * Sets state back to ZT_BREACH (default deny).
 * Trust must be re-earned through zero_trust_evaluate().
 *
 * Thread-safety: NOT thread-safe for concurrent eval loop.
 * Call after stopping continuous eval, or at app shutdown.
 */
void zero_trust_reset(void);

/* ==================================================================
 * Integration with native-bridge.cpp
 * ================================================================== */

/**
 * Run all detection checks (bridges to native-bridge.cpp functions).
 * Called internally by zero_trust_evaluate().
 *
 * @return Weighted threat score (0-30).
 */
int zero_trust_run_detection_chain(void);

/* ==================================================================
 * JNI Audit Bridge
 * ================================================================== */

/**
 * Log a security event via the JNI bridge to AuditLogger.kt.
 * Called internally on trust state transitions.
 *
 * @param level_str   Severity: "DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"
 * @param event_code  Audit event code (matches AuditLogger.Event codes).
 * @param message     Human-readable log message.
 */
void zero_trust_audit_log(const char* level_str, int event_code, const char* message);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* YUNIAN_ZERO_TRUST_H */
