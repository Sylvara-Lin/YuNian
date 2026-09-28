package com.yunian.ai.security


object MethodRecoveryEngine {

    private var installed = false

    private val recoveryCache = object : LinkedHashMap<String, ByteArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > 128
        }
    }

    @JvmStatic
    @Synchronized
    fun install(parent: ClassLoader) {
        if (installed) return
        installed = true

        val wrapper = RecoveryClassLoader(parent)

        injectClassLoader(wrapper)
    }

    fun recoverMethods(className: String, classBytes: ByteArray): ByteArray {

        recoveryCache[className]?.let { return it }

        val recovered = nativeRecoverClassMethods(className, classBytes)
        if (recovered != null && recovered.isNotEmpty()) {
            recoveryCache[className] = recovered
            return recovered
        }
        return classBytes
    }

    private fun injectClassLoader(wrapper: ClassLoader) {
        try {

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAT = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val mBoundAppField = activityThreadClass.getDeclaredField("mBoundApplication")
            mBoundAppField.isAccessible = true
            val mBoundApp = mBoundAppField.get(currentAT)
            val infoField = mBoundApp.javaClass.getDeclaredField("info")
            infoField.isAccessible = true
            val loadedApk = infoField.get(mBoundApp)

            val mClassLoaderField = loadedApk.javaClass.getDeclaredField("mClassLoader")
            mClassLoaderField.isAccessible = true
            val appClassLoader = mClassLoaderField.get(loadedApk) as ClassLoader

            mClassLoaderField.set(loadedApk, wrapper)
            android.util.Log.i("MethodRecovery", "Injected RecoveryClassLoader")
        } catch (e: Exception) {
            android.util.Log.e("MethodRecovery", "Failed to inject ClassLoader", e)
        }
    }

    @JvmStatic external fun nativeRecoverClassMethods(className: String, classBytes: ByteArray): ByteArray?
}

internal class RecoveryClassLoader(parent: ClassLoader) : ClassLoader(parent) {

    override fun findClass(name: String): Class<*> {
        return try {
            super.findClass(name)
        } catch (e: ClassNotFoundException) {

            val bytes = loadClassBytes(name)
            if (bytes != null) {
                defineClass(name, bytes, 0, bytes.size)
            } else {
                throw e
            }
        }
    }

    private fun loadClassBytes(name: String): ByteArray? {

        return null
    }
}
