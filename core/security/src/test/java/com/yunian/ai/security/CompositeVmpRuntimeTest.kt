package com.yunian.ai.security

import org.junit.Assert.*
import org.junit.Test

class CompositeVmpRuntimeTest {

    @Test
    fun executeThrowsOnUnknownOpcode() {
        try {
            CompositeVmpRuntime.execute(0xFF)
            fail("expected IllegalArgumentException for unknown opcode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown composite VMP opcode"))
        }
    }

    @Test
    fun knownOpcodesAreDefinedAndDistinct() {
        val opcodes = setOf(
            CompositeVmpRuntime.OP_SHELL_RECORD_STARTUP_PREFLIGHT,
            CompositeVmpRuntime.OP_SHELL_VERIFY_BEFORE_PAYLOAD,
            CompositeVmpRuntime.OP_SHELL_CREATE_PAYLOAD_LOADER,
            CompositeVmpRuntime.OP_API_SECRET_ENCRYPT,
            CompositeVmpRuntime.OP_API_SECRET_DECRYPT
        )
        assertEquals("All opcodes must be distinct", 5, opcodes.size)
        opcodes.forEach { opcode ->
            assertTrue("Opcode must be in valid VM range", opcode in 0x01..0xFF)
        }
    }

    @Test
    fun pkcs7PadProducesBlockAlignedOutput() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 255)) {
            val data = ByteArray(size) { it.toByte() }
            val padded = invokePkcs7Pad(data)
            assertEquals("Padded size must be multiple of 16 for input size $size",
                0, padded.size % 16)
            assertTrue("Padded size >= original", padded.size >= data.size)

            for (i in 0 until size) {
                assertEquals("Original byte $i preserved for size $size",
                    data[i].toInt(), padded[i].toInt())
            }
        }
    }

    @Test
    fun pkcs7PadThenUnpadRoundTrip() {
        for (size in listOf(0, 1, 15, 16, 17, 31, 32, 100, 255)) {
            val original = ByteArray(size) { ((it * 37 + 13) % 256).toByte() }
            val padded = invokePkcs7Pad(original)
            val restored = invokePkcs7Unpad(padded)
            assertNotNull("Unpad must succeed for size $size", restored)
            assertArrayEquals("Round-trip must preserve original for size $size",
                original, restored!!)
        }
    }

    @Test
    fun pkcs7UnpadRejectsCorruptPadding() {

        val valid = ByteArray(16) { 1 }
        assertNotNull("Valid PKCS7 padding should pass", invokePkcs7Unpad(valid))

        val zeroPad = ByteArray(16) { 0 }
        assertNull("Padding byte 0 must be rejected", invokePkcs7Unpad(zeroPad))

        val tooLarge = ByteArray(16) { 17 }
        assertNull("Padding byte > 16 must be rejected", invokePkcs7Unpad(tooLarge))

        val inconsistent = ByteArray(16).apply {
            this[15] = 3; this[14] = 1
        }
        assertNull("Inconsistent padding must be rejected", invokePkcs7Unpad(inconsistent))

        assertNull("Empty array must be rejected", invokePkcs7Unpad(ByteArray(0)))
    }

    @Test
    fun sha256HexProducesCorrectDigest() {

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            invokeSha256Hex(ByteArray(0))
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            invokeSha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun extractPayloadMetadataReturnsFirst16Bytes() {
        val payload = ByteArray(48) { it.toByte() }
        val metadata = invokeExtractPayloadMetadata(payload)
        assertEquals(16, metadata.size)
        assertArrayEquals(ByteArray(16) { it.toByte() }, metadata)

        assertEquals(0, payload[0].toInt())
        assertEquals(15, payload[15].toInt())
    }

    companion object {
        private val pkcs7PadMethod by lazy {
            CompositeVmpRuntime::class.java
                .getDeclaredMethod("pkcs7Pad", ByteArray::class.java)
                .apply { isAccessible = true }
        }
        private val pkcs7UnpadMethod by lazy {
            CompositeVmpRuntime::class.java
                .getDeclaredMethod("pkcs7Unpad", ByteArray::class.java)
                .apply { isAccessible = true }
        }
        private val sha256HexMethod by lazy {
            CompositeVmpRuntime::class.java
                .getDeclaredMethod("sha256Hex", ByteArray::class.java)
                .apply { isAccessible = true }
        }
        private val extractPayloadMetadataMethod by lazy {
            CompositeVmpRuntime::class.java
                .getDeclaredMethod("extractPayloadMetadata", ByteArray::class.java)
                .apply { isAccessible = true }
        }

        fun invokePkcs7Pad(data: ByteArray): ByteArray =
            pkcs7PadMethod.invoke(CompositeVmpRuntime, data) as ByteArray

        fun invokePkcs7Unpad(data: ByteArray): ByteArray? =
            pkcs7UnpadMethod.invoke(CompositeVmpRuntime, data) as ByteArray?

        fun invokeSha256Hex(data: ByteArray): String =
            sha256HexMethod.invoke(CompositeVmpRuntime, data) as String

        fun invokeExtractPayloadMetadata(payload: ByteArray): ByteArray =
            extractPayloadMetadataMethod.invoke(CompositeVmpRuntime, payload) as ByteArray
    }
}
