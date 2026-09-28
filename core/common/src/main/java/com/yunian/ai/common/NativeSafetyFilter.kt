package com.yunian.ai.common

import android.util.Log

object NativeSafetyFilter {

    private const val TAG = "NativeSafetyFilter"

    val isNativeAvailable: Boolean

    init {
        var available = false
        try {
            System.loadLibrary("lianyu_security")

            available = try {
                probeNativeSymbol()
                true
            } catch (_: UnsatisfiedLinkError) {
                Log.w(TAG, "Native symbols not resolved, falling back to Kotlin")
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "liblianyu_security.so not loaded: ${e.message}")

            available = try { probeNativeSymbol(); true }
            catch (_: UnsatisfiedLinkError) { false }
        }
        isNativeAvailable = available
        Log.i(TAG, "Native mode=${available}")
    }

    data class AcMatch(
        val level: Int,
        val keyword: String,
        val endPos: Int
    )

    @JvmStatic external fun nativeAcBuild(keywords: Array<String>, levels: IntArray): Long

    @JvmStatic external fun nativeAcSearch(text: String, acPtr: Long): Array<AcMatch>?

    @JvmStatic external fun nativeAcFree(acPtr: Long)

    @JvmStatic external fun nativeBayesianPredict(
        features: FloatArray,
        priors: FloatArray,
        likelihoods: FloatArray
    ): Float

    private fun probeNativeSymbol() {

        nativeBayesianPredict(floatArrayOf(0f), floatArrayOf(0.5f, 0.5f), floatArrayOf(0f, 0f))
    }

    private var acPtr: Long = 0
    private val acLock = Any()

    private val kotlinAcNode = mutableMapOf<Int, MutableMap<Char, Int>>()
    private val kotlinAcFail = mutableMapOf<Int, Int>()
    private val kotlinAcOutput = mutableMapOf<Int, Pair<Int, String>>()
    private var kotlinAcBuilt = false

    fun initAc(keywords: Map<Int, List<String>>) {
        synchronized(acLock) {
            if (isNativeAvailable) {
                if (acPtr != 0L) nativeAcFree(acPtr)
                val flat = mutableListOf<String>()
                val levels = mutableListOf<Int>()
                for ((level, kws) in keywords) {
                    for (kw in kws) {
                        flat.add(kw)
                        levels.add(level)
                    }
                }
                acPtr = nativeAcBuild(flat.toTypedArray(), levels.toIntArray())
            } else {
                buildKotlinAc(keywords)
            }
        }
    }

    fun searchAc(text: String): List<AcMatch> {
        synchronized(acLock) {
            return if (isNativeAvailable) {
                if (acPtr == 0L) return emptyList()
                nativeAcSearch(text, acPtr)?.toList() ?: emptyList()
            } else {
                searchKotlinAc(text)
            }
        }
    }

    fun destroy() {
        synchronized(acLock) {
            if (isNativeAvailable && acPtr != 0L) {
                nativeAcFree(acPtr)
                acPtr = 0
            }
            kotlinAcNode.clear()
            kotlinAcFail.clear()
            kotlinAcOutput.clear()
            kotlinAcBuilt = false
        }
    }

    fun bayesianPredict(
        features: FloatArray,
        priors: FloatArray,
        likelihoods: FloatArray
    ): Float {
        return if (isNativeAvailable) {
            nativeBayesianPredict(features, priors, likelihoods)
        } else {
            bayesianPredictKotlin(features, priors, likelihoods)
        }
    }

    private fun buildKotlinAc(keywords: Map<Int, List<String>>) {
        kotlinAcNode.clear()
        kotlinAcFail.clear()
        kotlinAcOutput.clear()

        val nodes = listOf(mutableMapOf<Char, Int>())

        val next = mutableListOf(mutableMapOf<Char, Int>())
        val fail = mutableListOf(0)
        val output = mutableListOf<Pair<Int, String>?>(null)

        for ((level, kws) in keywords) {
            for (kw in kws) {
                var state = 0
                for (ch in kw.lowercase()) {
                    if (!next[state].containsKey(ch)) {
                        next[state][ch] = next.size
                        next.add(mutableMapOf())
                        fail.add(0)
                        output.add(null)
                    }
                    state = next[state][ch]!!
                }
                output[state] = level to kw
            }
        }

        val queue = ArrayDeque<Int>()
        for ((ch, s) in next[0]) {
            fail[s] = 0
            queue.addLast(s)
        }
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for ((ch, s) in next[r]) {
                queue.addLast(s)
                var f = fail[r]
                while (f != 0 && !next[f].containsKey(ch)) f = fail[f]
                fail[s] = next[f].getOrDefault(ch, 0)
                if (output[s] == null && output[fail[s]] != null) {
                    output[s] = output[fail[s]]
                }
            }
        }

        kotlinAcNode.clear()
        next.forEachIndexed { i, map -> kotlinAcNode[i] = map }
        kotlinAcFail.clear()
        kotlinAcFail.putAll(fail.withIndex().associate { it.index to it.value })
        kotlinAcOutput.clear()
        output.forEachIndexed { i, v -> if (v != null) kotlinAcOutput[i] = v }
        kotlinAcBuilt = true
    }

    private fun searchKotlinAc(text: String): List<AcMatch> {
        if (!kotlinAcBuilt) return emptyList()
        val results = mutableListOf<AcMatch>()
        var state = 0
        for ((i, ch) in text.withIndex()) {
            val lc = ch.lowercaseChar()

            while (state != 0 && !kotlinAcNode.getOrDefault(state, emptyMap()).containsKey(lc)) {
                state = kotlinAcFail.getOrDefault(state, 0)
            }
            state = kotlinAcNode.getOrDefault(state, emptyMap()).getOrDefault(lc, 0)

            var temp = state
            while (temp != 0) {
                kotlinAcOutput[temp]?.let { (level, keyword) ->
                    results.add(AcMatch(level, keyword, i))
                }
                temp = kotlinAcFail.getOrDefault(temp, 0)
            }
        }
        return results
    }

    private fun bayesianPredictKotlin(
        features: FloatArray,
        priors: FloatArray,
        likelihoods: FloatArray
    ): Float {
        val n = features.size
        var scoreSafe = Math.log((priors.getOrElse(0) { 0.5f } + 1e-10f).toDouble())
        var scoreDanger = Math.log((priors.getOrElse(1) { 0.5f } + 1e-10f).toDouble())

        for (i in 0 until n) {
            val safeLike = Math.log((likelihoods.getOrElse(i * 2) { 0.5f } + 1e-10f).toDouble())
            val dangerLike = Math.log((likelihoods.getOrElse(i * 2 + 1) { 0.5f } + 1e-10f).toDouble())
            scoreSafe += safeLike * features[i]
            scoreDanger += dangerLike * features[i]
        }

        val maxScore = maxOf(scoreSafe, scoreDanger)
        val expSafe = Math.exp(scoreSafe - maxScore)
        val expDanger = Math.exp(scoreDanger - maxScore)
        return (expDanger / (expSafe + expDanger)).toFloat()
    }
}
