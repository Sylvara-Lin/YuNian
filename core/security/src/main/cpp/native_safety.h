// native_safety.h — JNI bridge for content safety checks
#pragma once
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 统一安全检查 — 一条消息一次 JNI 调用。
 *
 * @param text 用户输入文本
 * @param vectorResult pre-computed vector match result (from Kotlin)
 * @param priors 贝叶斯先验概率 [2]: P(safe), P(dangerous)
 * @param likelihoods 贝叶斯似然 [numFeatures][2]
 * @param numFeatures 特征数
 * @return jobject SafetyScore (Java class)
 */
jobject native_safety_check(
    JNIEnv* env,
    jstring text,
    jobject vectorResult,
    jfloatArray priors,
    jfloatArray likelihoods,
    jint numFeatures
);

/**
 * 仅 AC 自动机 — 返回匹配结果
 */
jobjectArray native_ac_search(
    JNIEnv* env,
    jstring text,
    jlong acPtr  // AC automaton native pointer
);

/**
 * 构建 AC 自动机 — 返回 native pointer
 */
jlong native_ac_build(
    JNIEnv* env,
    jobjectArray keywords,  // String[]
    jintArray levels        // int[] (ViolationLevel ordinal)
);

/**
 * 释放 AC 自动机
 */
void native_ac_free(jlong ptr);

#ifdef __cplusplus
}
#endif
