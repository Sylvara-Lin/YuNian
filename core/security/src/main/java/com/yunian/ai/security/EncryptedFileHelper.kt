package com.yunian.ai.security

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class EncryptedFileHelper(private val context: Context) {

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    fun getEncryptedFile(file: File): EncryptedFile {
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
    }

    fun openEncryptedInput(file: File): InputStream {
        return getEncryptedFile(file).openFileInput()
    }

    fun openEncryptedOutput(file: File): OutputStream {
        return getEncryptedFile(file).openFileOutput()
    }

    companion object {
        private const val TAG = "EncryptedFileHelper"

        fun create(context: Context): EncryptedFileHelper {
            return EncryptedFileHelper(context.applicationContext)
        }
    }
}
