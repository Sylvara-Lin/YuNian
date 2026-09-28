// native_safety.cpp — 内容安全检查 C++ 实现
#include "native_safety.h"
#include <cstring>
#include <vector>
#include <string>
#include <unordered_map>
#include <queue>
#include <android/log.h>

#define TAG "NativeSafety"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============================================================
// AC 自动机 (确定性)
// ============================================================
struct ACNode {
    std::unordered_map<char, int> next;
    int fail = 0;
    int level = -1;  // -1 = no match
    std::string keyword;
};

struct ACAutomaton {
    std::vector<ACNode> nodes;
    std::vector<std::unordered_map<char, int>> fullNext;  // 确定化转移表
};

static ACAutomaton* ac_from_java(JNIEnv* env, jobjectArray keywords, jintArray levels) {
    auto* ac = new ACAutomaton();
    ac->nodes.emplace_back();  // root

    jsize n = env->GetArrayLength(keywords);
    for (jsize i = 0; i < n; i++) {
        auto kw = (jstring)env->GetObjectArrayElement(keywords, i);
        const char* str = env->GetStringUTFChars(kw, nullptr);
        std::string word(str);
        env->ReleaseStringUTFChars(kw, str);

        int state = 0;
        for (char ch : word) {
            char lc = (ch >= 'A' && ch <= 'Z') ? ch + 32 : ch;
            if (ac->nodes[state].next.find(lc) == ac->nodes[state].next.end()) {
                ac->nodes[state].next[lc] = ac->nodes.size();
                ac->nodes.emplace_back();
            }
            state = ac->nodes[state].next[lc];
        }
        ac->nodes[state].keyword = word;
    }

    // Set levels
    jint* levelArr = env->GetIntArrayElements(levels, nullptr);
    for (jsize i = 0; i < n; i++) {
        // Re-traverse to find the terminal state for each keyword
        auto kw = (jstring)env->GetObjectArrayElement(keywords, i);
        const char* str = env->GetStringUTFChars(kw, nullptr);
        std::string word(str);
        env->ReleaseStringUTFChars(kw, str);

        int state = 0;
        for (char ch : word) {
            char lc = (ch >= 'A' && ch <= 'Z') ? ch + 32 : ch;
            state = ac->nodes[state].next[lc];
        }
        ac->nodes[state].level = levelArr[i];
    }
    env->ReleaseIntArrayElements(levels, levelArr, JNI_ABORT);

    // BFS build fail links
    std::queue<int> q;
    for (auto& [ch, next] : ac->nodes[0].next) {
        ac->nodes[next].fail = 0;
        q.push(next);
    }
    while (!q.empty()) {
        int r = q.front(); q.pop();
        for (auto& [ch, next] : ac->nodes[r].next) {
            q.push(next);
            int f = ac->nodes[r].fail;
            while (f != 0 && ac->nodes[f].next.find(ch) == ac->nodes[f].next.end()) {
                f = ac->nodes[f].fail;
            }
            auto it = ac->nodes[f].next.find(ch);
            ac->nodes[next].fail = (it != ac->nodes[f].next.end()) ? it->second : 0;
            // Merge output: inherit level from fail chain
            if (ac->nodes[next].level < 0 && ac->nodes[ac->nodes[next].fail].level >= 0) {
                ac->nodes[next].level = ac->nodes[ac->nodes[next].fail].level;
                ac->nodes[next].keyword = ac->nodes[ac->nodes[next].fail].keyword;
            }
        }
    }

    // Determinize: pre-compute full next table for all 128 ASCII chars
    size_t size = ac->nodes.size();
    ac->fullNext.resize(size);
    for (size_t s = 0; s < size; s++) {
        ac->fullNext[s].reserve(128);
    }
    // Populate with BFS closure (simplified: only pre-compute states reachable from root)
    for (size_t s = 0; s < size; s++) {
        for (int c = 0; c < 128; c++) {
            int state = s;
            while (state != 0 && ac->nodes[state].next.find((char)c) == ac->nodes[state].next.end()) {
                state = ac->nodes[state].fail;
            }
            auto it = ac->nodes[state].next.find((char)c);
            ac->fullNext[s][(char)c] = (it != ac->nodes[state].next.end()) ? it->second : 0;
        }
    }

    return ac;
}

static void ac_free(ACAutomaton* ac) { delete ac; }

struct ACMatch {
    int level;
    std::string keyword;
    int endPos;
};

static std::vector<ACMatch> ac_search(const ACAutomaton* ac, const std::string& text) {
    std::vector<ACMatch> matches;
    int state = 0;
    for (size_t i = 0; i < text.size(); i++) {
        char ch = text[i];
        char lc = (ch >= 'A' && ch <= 'Z') ? ch + 32 : ch;
        if ((unsigned char)lc >= 128) { state = 0; continue; }
        state = ac->fullNext[state].at(lc);
        if (ac->nodes[state].level >= 0) {
            matches.push_back({ac->nodes[state].level, ac->nodes[state].keyword, (int)i});
        }
    }
    return matches;
}

// ============================================================
// 贝叶斯预测 (多项式朴素贝叶斯)
// ============================================================
static float bayesian_predict(
    const float* features, int numFeatures,
    const float* priors,    // [2]: P(safe), P(dangerous)
    const float* likelihoods // [numFeatures * 2]: row-major
) {
    // log P(safe | features) = log P(safe) + sum(log P(feature_i | safe))
    // log P(dangerous | features) = log P(dangerous) + sum(log P(feature_i | dangerous))
    float scoreSafe = logf(priors[0] + 1e-10f);
    float scoreDanger = logf(priors[1] + 1e-10f);

    for (int i = 0; i < numFeatures; i++) {
        scoreSafe += logf(likelihoods[i * 2] + 1e-10f) * features[i];
        scoreDanger += logf(likelihoods[i * 2 + 1] + 1e-10f) * features[i];
    }

    // Normalize to probability [0, 1]: P(dangerous)
    float maxScore = scoreSafe > scoreDanger ? scoreSafe : scoreDanger;
    float expSafe = expf(scoreSafe - maxScore);
    float expDanger = expf(scoreDanger - maxScore);
    return expDanger / (expSafe + expDanger);
}

// ============================================================
// JNI 实现
// ============================================================
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yunian_ai_common_NativeSafetyFilter_nativeAcBuild(
    JNIEnv* env, jclass, jobjectArray keywords, jintArray levels) {
    auto* ac = ac_from_java(env, keywords, levels);
    return reinterpret_cast<jlong>(ac);
}

JNIEXPORT jobjectArray JNICALL
Java_com_yunian_ai_common_NativeSafetyFilter_nativeAcSearch(
    JNIEnv* env, jclass, jstring text, jlong acPtr) {
    auto* ac = reinterpret_cast<ACAutomaton*>(acPtr);
    if (!ac) return nullptr;

    const char* str = env->GetStringUTFChars(text, nullptr);
    std::string textStr(str);
    env->ReleaseStringUTFChars(text, str);

    auto matches = ac_search(ac, textStr);

    jclass matchCls = env->FindClass("com/yunian/ai/common/NativeSafetyFilter$AcMatch");
    jmethodID ctor = env->GetMethodID(matchCls, "<init>", "(ILjava/lang/String;I)V");
    jobjectArray result = env->NewObjectArray(matches.size(), matchCls, nullptr);

    for (size_t i = 0; i < matches.size(); i++) {
        jstring kw = env->NewStringUTF(matches[i].keyword.c_str());
        jobject obj = env->NewObject(matchCls, ctor, matches[i].level, kw, matches[i].endPos);
        env->SetObjectArrayElement(result, i, obj);
        env->DeleteLocalRef(kw);
        env->DeleteLocalRef(obj);
    }

    return result;
}

JNIEXPORT void JNICALL
Java_com_yunian_ai_common_NativeSafetyFilter_nativeAcFree(
    JNIEnv*, jclass, jlong acPtr) {
    delete reinterpret_cast<ACAutomaton*>(acPtr);
}

JNIEXPORT jfloat JNICALL
Java_com_yunian_ai_common_NativeSafetyFilter_nativeBayesianPredict(
    JNIEnv* env, jclass,
    jfloatArray features, jfloatArray priors, jfloatArray likelihoods) {
    jsize numFeatures = env->GetArrayLength(features);
    jsize numPriors = env->GetArrayLength(priors);
    jsize numLikes = env->GetArrayLength(likelihoods);

    /* Defensive dimension check — mirror the Kotlin fallback semantics
     * (getOrElse(0.5f)) instead of OOB-reading likelihoods[i*2]. */
    if (numFeatures <= 0 || numPriors < 2 || numLikes < numFeatures * 2) {
        return 0.5f;
    }

    jfloat* featArr = env->GetFloatArrayElements(features, nullptr);
    jfloat* priorArr = env->GetFloatArrayElements(priors, nullptr);
    jfloat* likeArr = env->GetFloatArrayElements(likelihoods, nullptr);

    float result = bayesian_predict(featArr, numFeatures, priorArr, likeArr);

    env->ReleaseFloatArrayElements(features, featArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(priors, priorArr, JNI_ABORT);
    env->ReleaseFloatArrayElements(likelihoods, likeArr, JNI_ABORT);

    return result;
}

} // extern "C"
