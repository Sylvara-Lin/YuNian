package com.yunian.ai.security

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap

class DynamicClassLoader(
    private val context: Context,
    parent: ClassLoader
) : ClassLoader(parent) {

    data class ClassEntry(
        val fragmentIndex: Int,
        val offsetInFragment: Int,
        val length: Int,
        val iv: ByteArray
    )

    private val mappingIndex = LinkedHashMap<String, ClassEntry>()

    private val classCache = object : LinkedHashMap<String, Class<*>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Class<*>>?): Boolean {
            return size > 128
        }
    }

    @Volatile
    private var indexLoaded = false

    override fun findClass(name: String): Class<*> {

        synchronized(classCache) {
            classCache[name]?.let { return it }
        }

        try {
            return super.findClass(name)
        } catch (_: ClassNotFoundException) {

        }

        ensureIndexLoaded()

        val entry = mappingIndex[name]
            ?: throw ClassNotFoundException(name)

        val dexBytes = DexFragmentLoader.loadSingleDex(
            context,
            entry.fragmentIndex,
            entry.offsetInFragment,
            entry.length,
            entry.iv
        ) ?: throw ClassNotFoundException("$name (decryption failed)")

        val clazz = defineClass(name, dexBytes, 0, dexBytes.size)

        synchronized(classCache) {
            classCache[name] = clazz
        }

        return clazz
    }

    @Synchronized
    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        try {
            val raw = context.assets.open("yunian_shell/class_map.bin").use { it.readBytes() }

            if (raw.size < 20) {
                android.util.Log.w("DynamicClassLoader", "class_map.bin too small (${raw.size} bytes), skipping")
                indexLoaded = true
                return
            }

            val metadata = raw.copyOfRange(0, 16)
            val encryptedBody = raw.copyOfRange(16, raw.size)

            val decrypted = KmsProvider.decryptWithMetadata(encryptedBody, metadata)
                ?: throw SecurityException("Failed to decrypt class_map.bin")

            parseMappingIndex(decrypted)
            indexLoaded = true

            android.util.Log.i("DynamicClassLoader",
                "Mapping index loaded: ${mappingIndex.size} classes")
        } catch (e: Exception) {
            android.util.Log.e("DynamicClassLoader", "Failed to load class map", e)
            indexLoaded = true
        }
    }

    private fun parseMappingIndex(data: ByteArray) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        if (buf.remaining() < 4) return
        val classCount = buf.int

        for (i in 0 until classCount) {
            if (buf.remaining() < 2) break
            val nameLen = buf.short.toInt() and 0xFFFF

            if (buf.remaining() < nameLen + 1 + 4 + 4 + 16) break
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            val className = String(nameBytes, Charsets.UTF_8)

            val fragIndex = buf.get().toInt() and 0xFF
            val offset = buf.int
            val length = buf.int
            val iv = ByteArray(16)
            buf.get(iv)

            mappingIndex[className] = ClassEntry(fragIndex, offset, length, iv)
        }
    }
}
