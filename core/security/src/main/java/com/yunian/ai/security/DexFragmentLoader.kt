package com.yunian.ai.security

import android.content.Context
import dalvik.system.DexFile
import dalvik.system.InMemoryDexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

object DexFragmentLoader {
    private const val FRAGMENT_COUNT = 4
    private const val PAYLOAD_ASSET_BASE = "yunian_shell/shell_payload"
    private const val METADATA_SIZE = 16

    private val loaded = BooleanArray(FRAGMENT_COUNT)
    private val decryptedPayloads = arrayOfNulls<ByteArray>(FRAGMENT_COUNT)
    private val fragmentKeys = LongArray(FRAGMENT_COUNT)
    private val fragmentIVs = arrayOfNulls<ByteArray>(FRAGMENT_COUNT)

    private var bootClassLoader: ClassLoader? = null

    fun loadShellFragment(context: Context, parent: ClassLoader): ClassLoader {
        if (loaded[0]) return bootClassLoader ?: parent
        deriveFragmentKeys(context)
        val payload = readAndDecrypt(context, 0)
        decryptedPayloads[0] = payload
        val loader = InMemoryDexClassLoader(ByteBuffer.wrap(payload), parent)
        loaded[0] = true
        bootClassLoader = loader
        return loader
    }

    fun loadNetworkFragment(context: Context): Boolean {
        if (loaded[1]) return true
        val appDir = File(context.filesDir, "dex_fragments")
        appDir.mkdirs()
        val payload = readAndDecrypt(context, 1)
        decryptedPayloads[1] = payload
        return loadDexFragmentViaReflection(context, payload, appDir, "fragment_1.dex").also { ok ->
            if (ok) loaded[1] = true
        }
    }

    fun loadUiFragment(context: Context): Boolean {
        if (loaded[2]) return true
        val appDir = File(context.filesDir, "dex_fragments")
        appDir.mkdirs()
        val payload = readAndDecrypt(context, 2)
        decryptedPayloads[2] = payload
        return loadDexFragmentViaReflection(context, payload, appDir, "fragment_2.dex").also { ok ->
            if (ok) loaded[2] = true
        }
    }

    fun loadChatFragment(context: Context): Boolean {
        if (loaded[3]) return true
        val appDir = File(context.filesDir, "dex_fragments")
        appDir.mkdirs()
        val payload = readAndDecrypt(context, 3)
        decryptedPayloads[3] = payload
        return loadDexFragmentViaReflection(context, payload, appDir, "fragment_3.dex").also { ok ->
            if (ok) loaded[3] = true
        }
    }

    fun unloadUiFragment(context: Context) {
        clearFragment(context, 2)
    }

    fun unloadChatFragment(context: Context) {
        clearFragment(context, 3)
    }

    fun isFragmentLoaded(index: Int): Boolean = index in 0 until FRAGMENT_COUNT && loaded[index]

    fun loadSingleDex(
        context: Context,
        fragmentIndex: Int,
        offset: Int,
        length: Int,
        iv: ByteArray
    ): ByteArray? {
        val assetName = "${PAYLOAD_ASSET_BASE}_${fragmentIndex}.bin"
        return try {
            val encryptedPayload = context.assets.open(assetName).use { it.readBytes() }

            if (offset < 0 || length <= 0 || offset + length > encryptedPayload.size) {
                android.util.Log.e("DexFragment",
                    "loadSingleDex: invalid range offset=$offset length=$length payloadSize=${encryptedPayload.size}")
                return null
            }

            val encryptedClassData = encryptedPayload.copyOfRange(offset, offset + length)

            val decrypted = KmsProvider.decryptWithMetadata(encryptedClassData, iv)

            if (decrypted != null && decrypted.size >= 4) {
                val magic = String(decrypted.copyOfRange(0, 4), Charsets.UTF_8)
                if (magic != "dex\n" && magic != "PK\u0003\u0004") {
                    android.util.Log.w("DexFragment",
                        "loadSingleDex: decrypted class data has unexpected magic: $magic")
                }
            }

            decrypted
        } catch (e: Exception) {
            android.util.Log.e("DexFragment",
                "loadSingleDex failed for fragment $fragmentIndex range [$offset, ${offset + length})", e)
            null
        }
    }

    private fun readAndDecrypt(context: Context, index: Int): ByteArray {
        val assetName = "${PAYLOAD_ASSET_BASE}_${index}.bin"
        val encryptedPayload = context.assets.open(assetName).use { it.readBytes() }

        if (encryptedPayload.size <= METADATA_SIZE || encryptedPayload.size % 16 != 0) {
            throw SecurityException("invalid fragment $index payload size: ${encryptedPayload.size}")
        }

        val metadata = encryptedPayload.copyOfRange(0, METADATA_SIZE)
        val ciphertext = encryptedPayload.copyOfRange(METADATA_SIZE, encryptedPayload.size)

        val decrypted = KmsProvider.decryptWithMetadata(ciphertext, metadata)
            ?: throw SecurityException("failed to decrypt fragment $index")

        verifyFragmentIntegrity(decrypted, index)

        try {
            return stripPkcs7Padding(decrypted)
        } finally {
            metadata.fill(0)
            ciphertext.fill(0)
            encryptedPayload.fill(0)
            decrypted.fill(0)
        }
    }

    private fun verifyFragmentIntegrity(decryptedPadded: ByteArray, index: Int) {

        if (decryptedPadded.size < 4) throw SecurityException("fragment $index too small")
        val magic = String(decryptedPadded.copyOfRange(0, 4), Charsets.UTF_8)
        if (magic != "dex\n" && magic != "PK\u0003\u0004") {
            throw SecurityException("fragment $index integrity check failed: bad magic")
        }
    }

    private fun stripPkcs7Padding(value: ByteArray): ByteArray {
        if (value.isEmpty()) throw SecurityException("empty fragment payload")
        val padding = value.last().toInt() and 0xff
        if (padding !in 1..16 || padding > value.size) {
            throw SecurityException("invalid fragment padding")
        }
        for (i in value.size - padding until value.size) {
            if ((value[i].toInt() and 0xff) != padding) {
                throw SecurityException("corrupt fragment padding")
            }
        }
        return value.copyOfRange(0, value.size - padding)
    }

    private fun loadDexFragmentViaReflection(
        context: Context,
        payload: ByteArray,
        appDir: File,
        fileName: String
    ): Boolean {
        val dexFile = File(appDir, fileName)
        return try {

            FileOutputStream(dexFile).use { it.write(payload) }

            val dex = DexFile.loadDex(dexFile.absolutePath, null, 0)
            injectDexIntoPathClassLoader(context, dex)

            dexFile.delete()
            true
        } catch (e: Exception) {
            android.util.Log.e("DexFragment", "Failed to load $fileName", e)
            runCatching { dexFile.delete() }
            false
        } finally {

            payload.fill(0)
        }
    }

    private fun injectDexIntoPathClassLoader(context: Context, dexFile: Any) {
        val pathClassLoader = context.classLoader
        val pathListField = pathClassLoader.javaClass.superclass?.getDeclaredField("pathList")
            ?: throw Exception("Cannot find pathList field")
        pathListField.isAccessible = true
        val pathList = pathListField.get(pathClassLoader)

        val dexElementsField = pathList.javaClass.getDeclaredField("dexElements")
        dexElementsField.isAccessible = true
        val existingElements = dexElementsField.get(pathList) as Array<*>

        val elementClass = existingElements.javaClass.componentType
        val constructor = elementClass?.declaredConstructors?.firstOrNull { ctor ->
            ctor.parameterTypes.size == 4 &&
                ctor.parameterTypes[0] == java.io.File::class.java &&
                ctor.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }
        if (constructor == null || elementClass == null) {
            throw Exception("Cannot find DexFile element constructor")
        }
        constructor.isAccessible = true

        val dummyZip = File.createTempFile("dummy", ".zip", context.cacheDir)
        dummyZip.writeBytes(ByteArray(22))
        dummyZip.deleteOnExit()

        val newElement = constructor.newInstance(dummyZip, false, dexFile, null)
        @Suppress("UNCHECKED_CAST")
        val newElements = java.lang.reflect.Array.newInstance(elementClass, existingElements.size + 1) as Array<Any>
        newElements[0] = newElement
        System.arraycopy(existingElements, 0, newElements, 1, existingElements.size)
        dexElementsField.set(pathList, newElements)
    }

    private fun clearFragment(context: Context, index: Int) {
        if (!loaded[index]) return
        decryptedPayloads[index]?.fill(0)
        decryptedPayloads[index] = null
        loaded[index] = false

        val appDir = File(context.filesDir, "dex_fragments")
        File(appDir, "fragment_${index}.dex").delete()
    }

    private fun deriveFragmentKeys(context: Context) {

        for (i in 0 until FRAGMENT_COUNT) {
            fragmentKeys[i] = (0x4C69616E59754B4DL xor (i.toLong() + 1))
            val iv = ByteArray(16)
            SecureRandom.getInstanceStrong().nextBytes(iv)
            fragmentIVs[i] = iv
        }
    }
}
