package com.yunian.ai.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKeys
import com.yunian.ai.common.SecureLog
import java.io.File

object EncryptedDatabaseWrapper {

    private const val DB_NAME = "yunian.db"

    fun prepareDatabase(context: Context): Boolean {
        val dbPath = context.applicationContext.getDatabasePath(DB_NAME)
        val dbDir = dbPath.parentFile ?: context.applicationContext.filesDir
        dbDir.mkdirs()
        val encryptedFile = File(dbDir, "$DB_NAME.enc")
        val plaintextFile = File(dbDir, DB_NAME)

        if (!encryptedFile.exists()) {

            if (plaintextFile.exists() && plaintextFile.length() > 0) {

            }
            return true
        }

        if (plaintextFile.exists()) {
            deleteRoomAuxFiles(plaintextFile)
        }

        return runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedFile.Builder(
                encryptedFile,
                context.applicationContext,
                masterKeyAlias,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build().openFileInput().use { input ->
                plaintextFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrElse { e ->
            SecureLog.e("EncryptedDB", "Failed to decrypt database", e)
            encryptedFile.delete()
            plaintextFile.delete()
            deleteRoomAuxFiles(plaintextFile)
            false
        }
    }

    fun sealDatabase(context: Context) {
        val dbPath = context.applicationContext.getDatabasePath(DB_NAME)
        val dbDir = dbPath.parentFile ?: context.applicationContext.filesDir
        val plaintextFile = File(dbDir, DB_NAME)
        val encryptedFile = File(dbDir, "$DB_NAME.enc")

        if (!plaintextFile.exists() || plaintextFile.length() == 0L) return

        runCatching {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedFile.Builder(
                encryptedFile,
                context.applicationContext,
                masterKeyAlias,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build().openFileOutput().use { output ->
                plaintextFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            plaintextFile.delete()
            deleteRoomAuxFiles(plaintextFile)
        }.onFailure { e ->
            SecureLog.e("EncryptedDB", "Failed to seal database", e)
        }
    }

    private fun deleteRoomAuxFiles(dbFile: File) {
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
    }
}
