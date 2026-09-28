package com.yunian.ai.security

import java.nio.charset.StandardCharsets

object AttestationDataParser {

    private const val TAG_OCTET_STRING = 0x04.toByte()
    private const val TAG_SEQUENCE = 0x30.toByte()
    private const val TAG_SET = 0x31.toByte()

    private const val TAG_ROOT_OF_TRUST = 704
    private const val TAG_ATTEST_APP_ID = 709

    fun parseAttestationData(extensionValue: ByteArray): AttestationData? {

        val inner = stripOctetString(extensionValue) ?: return null

        val fields = parseSequenceFields(inner) ?: return null

        return buildResult(fields, inner)
    }

    private fun stripOctetString(data: ByteArray): ByteArray? {
        if (data.size < 2) return null
        var idx = 0
        if (data[idx] != TAG_OCTET_STRING) return null
        idx++
        val (len, nextIdx) = readLength(data, idx)
        idx = nextIdx
        return if (idx + len <= data.size) data.copyOfRange(idx, idx + len) else null
    }

    private fun parseSequenceFields(data: ByteArray): List<Pair<Int, ByteArray>>? {
        if (data.size < 2) return null
        var idx = 0
        if (data[idx] != TAG_SEQUENCE) return null
        idx++
        val (len, start) = readLength(data, idx)
        idx = start
        val end = (idx + len).coerceAtMost(data.size)

        val fields = mutableListOf<Pair<Int, ByteArray>>()
        var fieldIndex = 0
        while (idx < end) {
            val (fieldTag, fieldVal, nextIdx) = readTlv(data, idx) ?: break
            fields.add(fieldTag to fieldVal)
            idx = nextIdx
            fieldIndex++
        }
        return fields
    }

    private fun readTlv(data: ByteArray, start: Int): Triple<Int, ByteArray, Int>? {
        if (start >= data.size) return null
        var idx = start
        val tag = readTag(data, idx)
        idx = tag.second
        val (len, nextIdx) = readLength(data, idx)
        idx = nextIdx
        val value = data.copyOfRange(idx, (idx + len).coerceAtMost(data.size))
        idx += len

        return Triple(tag.first, value, idx)
    }

    private fun readTag(data: ByteArray, start: Int): Pair<Int, Int> {
        var idx = start
        val first = data[idx].toInt() and 0xFF
        idx++
        return if ((first and 0x1F) < 0x1F) {
            Pair(first, idx)
        } else {

            var tag = 0
            while (idx < data.size) {
                val b = data[idx].toInt() and 0xFF
                idx++
                tag = (tag shl 7) or (b and 0x7F)
                if ((b and 0x80) == 0) break
            }
            Pair(tag, idx)
        }
    }

    private fun readLength(data: ByteArray, start: Int): Pair<Int, Int> {
        if (start >= data.size) return Pair(0, start)
        val first = data[start].toInt() and 0xFF
        return if (first < 0x80) {
            Pair(first, start + 1)
        } else {
            val numBytes = first and 0x7F
            var length = 0
            for (i in 1..numBytes) {
                if (start + i >= data.size) break
                length = (length shl 8) or (data[start + i].toInt() and 0xFF)
            }
            Pair(length, start + 1 + numBytes)
        }
    }

    private fun buildResult(fields: List<Pair<Int, ByteArray>>, raw: ByteArray): AttestationData {

        val asl = if (fields.size > 1) decodeEnum(fields[1].second) else 0
        val challenge = if (fields.size > 4) fields[4].second else ByteArray(0)

        var bootState = "Unknown"
        var bootLocked = false
        var packageName = "unknown"

        for (authIdx in listOf(6, 7)) {
            if (authIdx >= fields.size) continue
            val subFields = parseSequenceFields(fields[authIdx].second) ?: continue
            for ((subTag, subVal) in subFields) {
                when (subTag) {
                    TAG_ROOT_OF_TRUST -> {
                        val rotFields = parseSequenceFields(subVal)
                        if (rotFields != null && rotFields.size >= 4) {
                            bootState = decodeString(rotFields[3].second)

                            for (field in rotFields) {
                                val fv = field.second
                                if (fv.size in 4..20) {
                                    val s = decodeString(fv)
                                    if (s in listOf("Verified", "SelfSigned", "Unverified")) {
                                        bootState = s
                                    }
                                }
                            }

                            for (field in rotFields) {
                                val fv = field.second
                                if (fv.size == 1 && fv[0] == 0xFF.toByte()) {
                                    bootLocked = true
                                }
                            }
                        }
                    }
                    TAG_ATTEST_APP_ID -> {
                        val appFields = parseSequenceFields(subVal)
                        if (appFields != null) {
                            for ((_, av) in appFields) {
                                val s = decodeString(av)
                                if (s.contains(".") && s.length > 5) {
                                    packageName = s
                                }
                            }
                        }
                    }
                }
            }
        }

        if (packageName == "unknown") {
            val rawStr = String(raw, 0, raw.size.coerceAtMost(1024), StandardCharsets.UTF_8)
            val pattern = Regex("""(com\.[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,})""")
            packageName = pattern.find(rawStr)?.value ?: "unknown"
        }

        return AttestationData(
            asl = asl,
            bootloaderLocked = bootLocked,
            verifiedBootState = bootState,
            packageName = packageName,
            challenge = challenge
        )
    }

    private fun decodeEnum(data: ByteArray): Int =
        if (data.size >= 1) data[0].toInt() and 0xFF else 0

    private fun decodeString(data: ByteArray): String =
        String(data, StandardCharsets.UTF_8).trimEnd('\u0000')
}

data class AttestationData(
    val asl: Int,
    val bootloaderLocked: Boolean,
    val verifiedBootState: String,
    val packageName: String,
    val challenge: ByteArray
) {
    val isKeyStoreBacked: Boolean get() = asl >= 1
    val isStrongBoxBacked: Boolean get() = asl >= 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttestationData) return false
        return asl == other.asl &&
                bootloaderLocked == other.bootloaderLocked &&
                verifiedBootState == other.verifiedBootState &&
                packageName == other.packageName &&
                challenge.contentEquals(other.challenge)
    }

    override fun hashCode(): Int {
        var result = asl
        result = 31 * result + bootloaderLocked.hashCode()
        result = 31 * result + verifiedBootState.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + challenge.contentHashCode()
        return result
    }
}
