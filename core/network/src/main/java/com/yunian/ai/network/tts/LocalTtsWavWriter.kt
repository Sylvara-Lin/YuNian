package com.yunian.ai.network.tts

import java.io.File
import java.io.RandomAccessFile

object LocalTtsWavWriter {

    fun writePcmToWav(samples: FloatArray, sampleRate: Int, out: File) {
        val numChannels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val dataSize = samples.size * 2
        val chunkSize = 36 + dataSize

        out.parentFile?.mkdirs()
        val raf = RandomAccessFile(out, "rw")
        try {
            raf.setLength(0)

            raf.writeBytes("RIFF")
            raf.writeLittleEndianInt(chunkSize)
            raf.writeBytes("WAVE")

            raf.writeBytes("fmt ")
            raf.writeLittleEndianInt(16)
            raf.writeLittleEndianShort(1)
            raf.writeLittleEndianShort(numChannels.toShort())
            raf.writeLittleEndianInt(sampleRate)
            raf.writeLittleEndianInt(byteRate)
            raf.writeLittleEndianShort(blockAlign.toShort())
            raf.writeLittleEndianShort(bitsPerSample.toShort())

            raf.writeBytes("data")
            raf.writeLittleEndianInt(dataSize)

            val buffer = ByteArray(8192)
            var bufferPos = 0
            for (sample in samples) {

                val clamped = sample.coerceIn(-1.0f, 1.0f)
                val intSample = (clamped * 32767.0f).toInt()
                if (bufferPos + 2 > buffer.size) {
                    raf.write(buffer, 0, bufferPos)
                    bufferPos = 0
                }
                buffer[bufferPos++] = (intSample and 0xFF).toByte()
                buffer[bufferPos++] = ((intSample shr 8) and 0xFF).toByte()
            }
            if (bufferPos > 0) raf.write(buffer, 0, bufferPos)
        } finally {
            raf.close()
        }
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Short) {
        write(value.toInt() and 0xFF)
        write((value.toInt() shr 8) and 0xFF)
    }
}
