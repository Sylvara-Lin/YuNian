package com.yunian.ai.database.repository

import java.util.Locale

object MessageSearchTokenizer {
    fun indexTokens(content: String): String {
        val codePoints = normalizedCodePoints(content)
        if (codePoints.isEmpty()) return ""
        return buildList {
            codePoints.forEach { add(unigram(it)) }
            codePoints.zipWithNext().forEach { (first, second) -> add(bigram(first, second)) }
        }.joinToString(" ")
    }

    fun matchQuery(query: String): String? {
        val codePoints = normalizedCodePoints(query)
        if (codePoints.isEmpty()) return null
        val tokens = if (codePoints.size == 1) {
            listOf(unigram(codePoints.single()))
        } else {
            codePoints.zipWithNext().map { (first, second) -> bigram(first, second) }
        }
        return tokens.joinToString(separator = " ", prefix = "\"", postfix = "\"")
    }

    private fun normalizedCodePoints(value: String): List<Int> =
        value.lowercase(Locale.ROOT).codePoints().toArray().toList()

    private fun unigram(codePoint: Int): String = "u${codePoint.toString(16)}z"

    private fun bigram(first: Int, second: Int): String =
        "b${first.toString(16)}x${second.toString(16)}z"
}