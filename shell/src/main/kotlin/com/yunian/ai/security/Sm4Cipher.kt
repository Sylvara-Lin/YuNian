package com.yunian.ai.security

object Sm4Cipher {

    private val SBOX = intArrayOf(
        0xD6, 0x90, 0xE9, 0xFE, 0xCC, 0xE1, 0x3D, 0xB7, 0x16, 0xB6, 0x14, 0xC2, 0x28, 0xFB, 0x2C, 0x05,
        0x2B, 0x67, 0x9A, 0x76, 0x2A, 0xBE, 0x04, 0xC3, 0xAA, 0x44, 0x13, 0x26, 0x49, 0x86, 0x06, 0x99,
        0x9C, 0x42, 0x50, 0xF4, 0x91, 0xEF, 0x98, 0x7A, 0x33, 0x54, 0x0B, 0x43, 0xED, 0xCF, 0xAC, 0x62,
        0xE4, 0xB3, 0x1C, 0xA9, 0xC9, 0x08, 0xE8, 0x95, 0x80, 0xDF, 0x94, 0xFA, 0x75, 0x8F, 0x3F, 0xA6,
        0x47, 0x07, 0xA7, 0xFC, 0xF3, 0x73, 0x17, 0xBA, 0x83, 0x59, 0x3C, 0x19, 0xE6, 0x85, 0x4F, 0xA8,
        0x68, 0x6B, 0x81, 0xB2, 0x71, 0x64, 0xDA, 0x8B, 0xF8, 0xEB, 0x0F, 0x4B, 0x70, 0x56, 0x9D, 0x35,
        0x1E, 0x24, 0x0E, 0x5E, 0x63, 0x58, 0xD1, 0xA2, 0x25, 0x22, 0x7C, 0x3B, 0x01, 0x21, 0x78, 0x87,
        0xD4, 0x00, 0x46, 0x57, 0x9F, 0xD3, 0x27, 0x52, 0x4C, 0x36, 0x02, 0xE7, 0xA0, 0xC4, 0xC8, 0x9E,
        0xEA, 0xBF, 0x8A, 0xD2, 0x40, 0xC7, 0x38, 0xB5, 0xA3, 0xF7, 0xF2, 0xCE, 0xF9, 0x61, 0x15, 0xA1,
        0xE0, 0xAE, 0x5D, 0xA4, 0x9B, 0x34, 0x1A, 0x55, 0xAD, 0x93, 0x32, 0x30, 0xF5, 0x8C, 0xB1, 0xE3,
        0x1D, 0xF6, 0xE2, 0x2E, 0x82, 0x66, 0xCA, 0x60, 0xC0, 0x29, 0x23, 0xAB, 0x0D, 0x53, 0x4E, 0x6F,
        0xD5, 0xDB, 0x37, 0x45, 0xDE, 0xFD, 0x8E, 0x2F, 0x03, 0xFF, 0x6A, 0x72, 0x6D, 0x6C, 0x5B, 0x51,
        0x8D, 0x1B, 0xAF, 0x92, 0xBB, 0xDD, 0xBC, 0x7F, 0x11, 0xD9, 0x5C, 0x41, 0x1F, 0x10, 0x5A, 0xD8,
        0x0A, 0xC1, 0x31, 0x88, 0xA5, 0xCD, 0x7B, 0xBD, 0x2D, 0x74, 0xD0, 0x12, 0xB8, 0xE5, 0xB4, 0xB0,
        0x89, 0x69, 0x97, 0x4A, 0x0C, 0x96, 0x77, 0x7E, 0x65, 0xB9, 0xF1, 0x09, 0xC5, 0x6E, 0xC6, 0x84,
        0x18, 0xF0, 0x7D, 0xEC, 0x3A, 0xDC, 0x4D, 0x20, 0x79, 0xEE, 0x5F, 0x3E, 0xD7, 0xCB, 0x39, 0x48
    )

    private val FK = intArrayOf(
        0xA3B1BAC6.toInt(), 0x56AA3350.toInt(),
        0x677D9197.toInt(), 0xB27022DC.toInt()
    )

    private val CK = intArrayOf(
        0x00070E15, 0x1C232A31, 0x383F464D, 0x545B6269,
        0x70777E85, 0x8C939AA1, 0xA8AFB6BD, 0xC4CBD2D9,
        0xE0E7EEF5, 0xFC030A11, 0x181F262D, 0x343B4249,
        0x50575E65, 0x6C737A81, 0x888F969D, 0xA4ABB2B9,
        0xC0C7CED5, 0xDCE3EAF1, 0xF8FF060D, 0x141B2229,
        0x30373E45, 0x4C535A61, 0x686F767D, 0x848B9299,
        0xA0A7AEB5, 0xBCC3CAD1, 0xD8DFE6ED, 0xF4FB0209,
        0x10171E25, 0x2C333A41, 0x484F565D, 0x646B7279
    )

    private fun sm4Sbox(inch: Int): Int = SBOX[inch and 0xFF]

    private fun sm4Lt(ka: Int): Int {
        var bb = 0
        bb = bb xor sm4Rotl(ka, 2)
        bb = bb xor sm4Rotl(ka, 10)
        bb = bb xor sm4Rotl(ka, 18)
        bb = bb xor sm4Rotl(ka, 24)
        return bb xor ka
    }

    private fun sm4F(x0: Int, x1: Int, x2: Int, x3: Int, rk: Int): Int {
        return x0 xor sm4Lt(
            sm4Sbox((x1 xor x2 xor x3 xor rk) shr 24) shl 24 or
            (sm4Sbox(((x1 xor x2 xor x3 xor rk) shr 16) and 0xFF) shl 16) or
            (sm4Sbox(((x1 xor x2 xor x3 xor rk) shr 8) and 0xFF) shl 8) or
            sm4Sbox((x1 xor x2 xor x3 xor rk) and 0xFF)
        )
    }

    private fun sm4Rotl(ka: Int, n: Int): Int {
        return ((ka shl n) or (ka ushr (32 - n)))
    }

    private fun sm4SetKey(key: ByteArray): IntArray {
        val rk = IntArray(32)
        val mk = IntArray(4)

        for (i in 0..3) {
            mk[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or
                    ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                    ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
                    (key[4 * i + 3].toInt() and 0xFF)
        }

        val k = IntArray(36)
        for (i in 0..3) {
            k[i] = mk[i] xor FK[i]
        }
        for (i in 0..31) {
            val v = k[i + 1] xor k[i + 2] xor k[i + 3] xor CK[i]
            k[i + 4] = k[i] xor sm4Lt(
                sm4Sbox(v shr 24) shl 24 or
                (sm4Sbox((v shr 16) and 0xFF) shl 16) or
                (sm4Sbox((v shr 8) and 0xFF) shl 8) or
                sm4Sbox(v and 0xFF)
            )
            rk[i] = k[i + 4]
        }
        return rk
    }

    private fun sm4EncryptBlock(input: ByteArray, inputOffset: Int, output: ByteArray, outputOffset: Int, rk: IntArray) {
        var x0 = ((input[inputOffset].toInt() and 0xFF) shl 24) or
                ((input[inputOffset + 1].toInt() and 0xFF) shl 16) or
                ((input[inputOffset + 2].toInt() and 0xFF) shl 8) or
                (input[inputOffset + 3].toInt() and 0xFF)
        var x1 = ((input[inputOffset + 4].toInt() and 0xFF) shl 24) or
                ((input[inputOffset + 5].toInt() and 0xFF) shl 16) or
                ((input[inputOffset + 6].toInt() and 0xFF) shl 8) or
                (input[inputOffset + 7].toInt() and 0xFF)
        var x2 = ((input[inputOffset + 8].toInt() and 0xFF) shl 24) or
                ((input[inputOffset + 9].toInt() and 0xFF) shl 16) or
                ((input[inputOffset + 10].toInt() and 0xFF) shl 8) or
                (input[inputOffset + 11].toInt() and 0xFF)
        var x3 = ((input[inputOffset + 12].toInt() and 0xFF) shl 24) or
                ((input[inputOffset + 13].toInt() and 0xFF) shl 16) or
                ((input[inputOffset + 14].toInt() and 0xFF) shl 8) or
                (input[inputOffset + 15].toInt() and 0xFF)

        for (i in 0..31) {
            val tmp = sm4F(x0, x1, x2, x3, rk[i])
            x0 = x1
            x1 = x2
            x2 = x3
            x3 = tmp
        }

        output[outputOffset] = (x3 shr 24 and 0xFF).toByte()
        output[outputOffset + 1] = (x3 shr 16 and 0xFF).toByte()
        output[outputOffset + 2] = (x3 shr 8 and 0xFF).toByte()
        output[outputOffset + 3] = (x3 and 0xFF).toByte()
        output[outputOffset + 4] = (x2 shr 24 and 0xFF).toByte()
        output[outputOffset + 5] = (x2 shr 16 and 0xFF).toByte()
        output[outputOffset + 6] = (x2 shr 8 and 0xFF).toByte()
        output[outputOffset + 7] = (x2 and 0xFF).toByte()
        output[outputOffset + 8] = (x1 shr 24 and 0xFF).toByte()
        output[outputOffset + 9] = (x1 shr 16 and 0xFF).toByte()
        output[outputOffset + 10] = (x1 shr 8 and 0xFF).toByte()
        output[outputOffset + 11] = (x1 and 0xFF).toByte()
        output[outputOffset + 12] = (x0 shr 24 and 0xFF).toByte()
        output[outputOffset + 13] = (x0 shr 16 and 0xFF).toByte()
        output[outputOffset + 14] = (x0 shr 8 and 0xFF).toByte()
        output[outputOffset + 15] = (x0 and 0xFF).toByte()
    }

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (key.size != 16) throw IllegalArgumentException("SM4 key must be 16 bytes")
        if (data.size % 16 != 0) throw IllegalArgumentException("SM4-ECB data must be multiple of 16 bytes")

        val rk = sm4SetKey(key)

        val rkDecrypt = IntArray(32)
        for (i in 0..31) {
            rkDecrypt[i] = rk[31 - i]
        }

        val result = ByteArray(data.size)
        for (i in data.indices step 16) {
            sm4EncryptBlock(data, i, result, i, rkDecrypt)
        }
        return result
    }

    fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
        if (key.size != 16) throw IllegalArgumentException("SM4 key must be 16 bytes")
        if (data.size % 16 != 0) throw IllegalArgumentException("SM4-ECB data must be multiple of 16 bytes")

        val rk = sm4SetKey(key)
        val result = ByteArray(data.size)
        for (i in data.indices step 16) {
            sm4EncryptBlock(data, i, result, i, rk)
        }
        return result
    }
}
