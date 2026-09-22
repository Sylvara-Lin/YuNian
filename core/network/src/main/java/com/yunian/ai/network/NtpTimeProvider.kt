package com.yunian.ai.network

import android.content.Context
import com.yunian.ai.common.ApplicationScopeProvider
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object NtpTimeProvider {

    private val NTP_HOSTS = listOf(
        "ntp.aliyun.com",
        "time1.cloud.tencent.com",
        "cn.ntp.org.cn",
        "pool.ntp.org",
    )

    private const val NTP_PORT = 123
    private const val TIMEOUT_MS = 5000
    private const val NTP_EPOCH_DIFF = 2208988800000L

    @Volatile
    private var cachedOffset: Long = 0L

    @Volatile
    private var synced: Boolean = false

    @Volatile
    private var lastSyncTime: Long = 0L

    private const val CACHE_VALIDITY_MS = 60_000L

    fun initialize(context: Context) {
        syncInBackground()
    }

    fun getCurrentTimeMs(): Long {
        if (!synced) return System.currentTimeMillis()

        if (System.currentTimeMillis() - lastSyncTime > CACHE_VALIDITY_MS) {
            syncInBackground()
        }
        return System.currentTimeMillis() + cachedOffset
    }

    fun isNtpSynced(): Boolean = synced

    fun syncInBackground() {
        ApplicationScopeProvider.scope.launch {
            performSync()
        }
    }

    private suspend fun performSync() {
        for (host in NTP_HOSTS) {
            try {
                val offset = queryNtp(host)
                if (offset != null) {
                    cachedOffset = offset
                    synced = true
                    lastSyncTime = System.currentTimeMillis()
                    SecureLog.i("NtpTimeProvider", "NTP synced with $host, offset=${offset}ms")
                    return
                }
            } catch (e: Exception) {
                SecureLog.w("NtpTimeProvider", "NTP sync failed for $host: ${e.message}")
            }
        }
        SecureLog.w("NtpTimeProvider", "All NTP servers unreachable, using device clock")
    }

    private fun queryNtp(host: String): Long? {
        val socket = DatagramSocket()
        return try {
            socket.soTimeout = TIMEOUT_MS
            val address = InetAddress.getByName(host)

            val buffer = ByteArray(48)
            buffer[0] = 0x1B.toByte()

            val requestTime = System.currentTimeMillis()
            val sendNtpTime = requestTime + NTP_EPOCH_DIFF
            writeTimestamp(buffer, 40, sendNtpTime)

            val sendPacket = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(48)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val responseTime = System.currentTimeMillis()

            val originateTimestamp = readTimestamp(receiveBuffer, 24)
            val receiveTimestamp = readTimestamp(receiveBuffer, 32)
            val transmitTimestamp = readTimestamp(receiveBuffer, 40)

            if (originateTimestamp == 0L || transmitTimestamp == 0L) return null

            val sendNtpMs = sendNtpTime
            if (kotlin.math.abs(originateTimestamp - sendNtpMs) > 1000) return null

            val t1 = sendNtpTime - NTP_EPOCH_DIFF
            val t2 = receiveTimestamp - NTP_EPOCH_DIFF
            val t3 = transmitTimestamp - NTP_EPOCH_DIFF
            val t4 = responseTime

            val offset = ((t2 - t1) + (t3 - t4)) / 2
            val rtt = (t4 - t1) - (t3 - t2)

            if (rtt > 1000 || rtt < 0) return null

            return offset
        } catch (e: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    private fun writeTimestamp(buffer: ByteArray, offset: Int, ntpTimeMs: Long) {
        val seconds = ntpTimeMs / 1000L
        val fraction = ((ntpTimeMs % 1000L) * 0x100000000L / 1000L).toInt()
        buffer[offset] = (seconds ushr 24).toByte()
        buffer[offset + 1] = (seconds ushr 16).toByte()
        buffer[offset + 2] = (seconds ushr 8).toByte()
        buffer[offset + 3] = seconds.toByte()
        buffer[offset + 4] = (fraction ushr 24).toByte()
        buffer[offset + 5] = (fraction ushr 16).toByte()
        buffer[offset + 6] = (fraction ushr 8).toByte()
        buffer[offset + 7] = fraction.toByte()
    }

    private fun readTimestamp(buffer: ByteArray, offset: Int): Long {
        val seconds = ((buffer[offset].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
                (buffer[offset + 3].toLong() and 0xFF)
        val fraction = ((buffer[offset + 4].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 5].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 6].toLong() and 0xFF) shl 8) or
                (buffer[offset + 7].toLong() and 0xFF)

        val fractionMs = (fraction.toDouble() / 4294967296.0 * 1000.0).toLong()
        return seconds * 1000L + fractionMs
    }
}
