package com.yunian.ai.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AuditLogger {

    private const val TAG = "YuNian-Audit"
    private const val AUDIT_DIR = "lianyu_audit"
    private const val LOG_FILE = "audit.log"
    private const val CHAIN_FILE = "chain.dat"

    private const val CHAIN_PREFS = "lianyu_audit_chain_prefs"
    private const val KEY_CHAIN_NONCE = "chain_nonce_b64"
    private const val KEY_CHAIN_VERSION = "chain_version"

    private const val MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024L

    private const val MAX_ENTRIES = 100_000L

    private const val MIN_RETAIN_ENTRIES = 50_000L

    private var enabled = true
    private var auditDir: File? = null

    @Volatile
    private var chainNonce: ByteArray? = null

    @Volatile
    private var chainVersion: Long = -1L

    enum class Level { DEBUG, INFO, WARNING, ERROR, CRITICAL }

    enum class Event(val code: Int) {

        APP_START(100),
        APP_STOP(101),

        SIGNATURE_FAIL(200),
        ROOT_DETECTED(201),
        HOOK_DETECTED(202),
        EMULATOR_DETECTED(203),
        DEBUG_DETECTED(204),
        MITM_DETECTED(205),
        THREAT_HIGH(206),

        DETECT_ROOT(207),
        DETECT_FRIDA(208),
        DETECT_XPOSED(209),
        DETECT_MAGISK(210),
        DETECT_KERNELSU(211),
        BREACH_ESCALATED(212),

        DB_ENCRYPTED(300),
        KEYSTORE_ERROR(301),
        ENCRYPT_FAIL(302),
        DECRYPT_FAIL(303),

        DK_DERIVED(310),
        SK_DERIVED(311),
        BK_GENERATED(312),
        KEY_DESTROYED(313),

        API_SIGNED(400),
        TLS_FAIL(401),
        CERT_PIN_FAIL(402),

        GUARD_RESET(500),
        INTEGRITY_FAIL(501),
        TAMPER_DETECTED(502),

        AUDIT_CHAIN_VERIFIED(510),
        AUDIT_CHAIN_BREACH(511);
    }

    data class Entry(
        val sequence: Long,
        val timestamp: Long,
        val level: Level,
        val event: Event,
        val message: String,
        val prevHash: String,
        val hash: String,
        val extraData: String = ""
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun getChainNonce(context: Context): ByteArray {
        chainNonce?.let { return it }
        synchronized(this) {
            chainNonce?.let { return it }
            val prefs = getChainPrefs(context)
            val existing = prefs.getString(KEY_CHAIN_NONCE, null)
            if (existing != null) {
                chainNonce = android.util.Base64.decode(existing, android.util.Base64.NO_WRAP)
                chainVersion = prefs.getLong(KEY_CHAIN_VERSION, 0L)
                return chainNonce!!
            }

            val nonce = ByteArray(32)
            SecureRandom().nextBytes(nonce)
            prefs.edit()
                .putString(KEY_CHAIN_NONCE, android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
                .putLong(KEY_CHAIN_VERSION, 0L)
                .apply()
            chainNonce = nonce
            chainVersion = 0L
            return nonce
        }
    }

    private fun getChainPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, CHAIN_PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun chainHash(data: ByteArray, context: Context): String {
        val nonce = getChainNonce(context)
        val withNonce = data + nonce
        return sha256(withNonce)
    }

    private fun ensureDir(context: Context): Boolean {
        if (auditDir != null) return true
        val dir = File(context.filesDir, AUDIT_DIR)
        if (!dir.exists() && !dir.mkdirs()) return false
        auditDir = dir
        return true
    }

    fun log(context: Context?, level: Level, event: Event, message: String, extra: String = ""): Entry? {
        if (!enabled || context == null) return null
        if (!ensureDir(context)) return null

        return try {
            val timestamp = System.currentTimeMillis()
            val (prevHash, sequence) = readChainState(context)
            val serial = StringBuilder()
                .append(timestamp).append('|')
                .append(level.name).append('|')
                .append(event.code).append('|')
                .append(message).append('|')
                .append(prevHash).append('|')
                .append(extra)
                .toString()
            val hash = chainHash(serial.toByteArray(), context)

            val entry = Entry(sequence, timestamp, level, event, message, prevHash, hash, extra)

            val logFile = File(auditDir, LOG_FILE)
            val json = buildEntryJson(entry)
            FileWriter(logFile, true).use { writer ->
                writer.write(json + "\n")
                writer.flush()
            }

            writeChainState(context, hash, sequence + 1)

            if (logFile.length() > MAX_LOG_SIZE_BYTES) {
                rotateLog(context)
            }

            enforceRingBuffer(context)

            val logLine = buildLogLine(entry)
            when (level) {
                Level.ERROR, Level.CRITICAL -> Log.e(TAG, logLine)
                Level.WARNING -> Log.w(TAG, logLine)
                else -> Log.i(TAG, logLine)
            }

            entry
        } catch (e: Exception) {
            Log.w(TAG, "Audit log failed: ${e.message}")
            null
        }
    }

    fun verifyChain(context: Context): Boolean {
        if (!ensureDir(context)) return true
        val logFile = File(auditDir, LOG_FILE)
        if (!logFile.exists()) return true

        return try {
            val lines = logFile.readLines()
            if (lines.isEmpty()) return true

            var prevHash = "GENESIS"
            var expectedSeq = 1L

            for (line in lines) {
                val parts = line.split("|")
                if (parts.size < 7) return false

                val seq = parts[0].toLongOrNull() ?: return false
                if (seq != expectedSeq) return false

                val actualHash = parts[6]

                val hashInput = if (parts.size >= 8) {
                    "${parts[1]}|${parts[2]}|${parts[3]}|${parts[4]}|${parts[5]}|${parts[7]}"
                } else {
                    "${parts[1]}|${parts[2]}|${parts[3]}|${parts[4]}|${parts[5]}|"
                }
                val computedHash = chainHash(hashInput.toByteArray(), context)
                if (computedHash != actualHash) return false

                val actualPrevHash = parts[5]
                if (actualPrevHash != prevHash) return false

                prevHash = actualHash
                expectedSeq++
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeChainState(context: Context, hash: String, sequence: Long) {
        val file = File(auditDir, CHAIN_FILE)
        try {
            val hashBytes = hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val seqBytes = java.nio.ByteBuffer.allocate(8).putLong(sequence).array()
            val versionBytes = java.nio.ByteBuffer.allocate(8).putLong(chainVersion + 1).array()
            val plaintext = hashBytes + seqBytes + versionBytes

            chainVersion = chainVersion + 1
            getChainPrefs(context).edit().putLong(KEY_CHAIN_VERSION, chainVersion).apply()

            val data = encryptChainHead(plaintext)

            RandomAccessFile(file, "rw").use { raf ->
                raf.write(data)
                raf.fd.sync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write chain state: ${e.message}")
        }
    }

    private fun readChainState(context: Context): Pair<String, Long> {
        val file = File(auditDir, CHAIN_FILE)
        if (!file.exists()) return Pair("GENESIS", 1L)

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val raw = ByteArray(raf.length().toInt())
                raf.readFully(raw)

                val plaintext = decryptChainHead(raw) ?: raw

                if (plaintext.size < 40) return@use Pair("GENESIS", 1L)
                val hashBytes = plaintext.copyOfRange(0, 32)
                val seqBytes = plaintext.copyOfRange(32, 40)
                val hash = hashBytes.joinToString("") { "%02x".format(it) }
                val seq = java.nio.ByteBuffer.wrap(seqBytes).getLong()

                if (plaintext.size >= 48) {
                    val storedVersion = java.nio.ByteBuffer.wrap(plaintext.copyOfRange(40, 48)).getLong()
                    val expectedVersion = getChainPrefs(context).getLong(KEY_CHAIN_VERSION, 0L)
                    if (storedVersion != expectedVersion) {
                        Log.e(TAG, "Chain version mismatch: stored=$storedVersion, expected=$expectedVersion — rollback detected!")
                        return@use Pair("GENESIS", 1L)
                    }
                }
                Pair(hash, seq)
            }
        } catch (e: Exception) {
            Pair("GENESIS", 1L)
        }
    }

    private fun encryptChainHead(plaintext: ByteArray): ByteArray {
        return try {
            KmsProvider.encryptWithSession(plaintext) ?: plaintext
        } catch (_: Exception) {
            plaintext
        }
    }

    private fun decryptChainHead(ciphertext: ByteArray): ByteArray? {
        return try {
            KmsProvider.decryptWithSession(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    fun verifyAuditChain(context: Context): Boolean {
        val chainOk = verifyChain(context)
        if (chainOk) {
            AuditLogger.log(context, Level.INFO,
                Event.AUDIT_CHAIN_VERIFIED, "Audit chain verified at boot")
            return true
        }

        AuditLogger.log(context, Level.CRITICAL,
            Event.AUDIT_CHAIN_BREACH, "Audit chain verification FAILED — chain tampered!")

        try {
            NativeBridge.zeroTrustEvaluate()
            val state = NativeBridge.zeroTrustGetState()
            if (state != 2) {

                NativeBridge.zeroTrustEvaluate()
            }
        } catch (_: Exception) {

        }

        return false
    }

    private fun enforceRingBuffer(context: Context) {
        try {
            val logFile = File(auditDir, LOG_FILE)
            if (!logFile.exists()) return

            val lines = logFile.readLines()
            if (lines.size <= MAX_ENTRIES) return

            val trimmed = lines.takeLast(MIN_RETAIN_ENTRIES.toInt())

            val tempFile = File(auditDir, "${LOG_FILE}.tmp")
            tempFile.writeText(trimmed.joinToString("\n") + "\n")
            tempFile.renameTo(logFile)

            Log.i(TAG, "Audit ring-buffer trimmed: ${lines.size} → ${trimmed.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "Ring-buffer enforcement failed: ${e.message}")
        }
    }

    fun setEnabled(state: Boolean) {
        enabled = state
    }

    private fun buildEntryJson(entry: Entry): String {
        val ts = dateFormat.format(Date(entry.timestamp))

        return "${entry.sequence}|${entry.timestamp}|${entry.level.name}|${entry.event.code}|" +
               "${entry.message}|${entry.prevHash}|${entry.hash}|" +
               (if (entry.extraData.isNotBlank()) entry.extraData else "")
    }

    private fun buildLogLine(entry: Entry): String {
        val ts = dateFormat.format(Date(entry.timestamp))
        return "[#$entry.sequence][$ts][${entry.level}][${entry.event.name}] ${entry.message}" +
            if (entry.extraData.isNotBlank()) " | ${entry.extraData}" else ""
    }

    private fun rotateLog(context: Context) {
        try {
            val logFile = File(auditDir, LOG_FILE)
            val archiveFile = File(auditDir, "audit_${System.currentTimeMillis()}.log")
            logFile.renameTo(archiveFile)

        } catch (e: Exception) {
            Log.w(TAG, "Log rotation failed: ${e.message}")
        }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
