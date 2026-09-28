package com.yunian.ai.common

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.Properties

object RomUtils {

    enum class RomType {
        COLOR_OS,
        ORIGIN_OS,
        FUNTOUCH_OS,
        MIUI,
        HYPER_OS,
        EMUI,
        HARMONY_OS,
        ONEPLUS,
        SAMSUNG,
        OTHER
    }

    private const val KEY_VERSION_OPPO = "ro.build.version.opporom"
    private const val KEY_VERSION_VIVO = "ro.vivo.os.version"
    private const val KEY_VERSION_VIVO_NAME = "ro.vivo.os.name"
    private const val KEY_VERSION_VIVO_SDK = "ro.vivo.os.version.sdk"

    private const val KEY_VERSION_VIVO_PRODUCT = "ro.vivo.product.version"
    private const val KEY_VERSION_VIVO_MODEL = "ro.vivo.hardware.subproduct"
    private const val KEY_VERSION_VIVO_DISPLAY = "ro.vivo.display.version"
    private const val KEY_VERSION_MIUI = "ro.miui.ui.version.name"
    private const val KEY_VERSION_MIUI_OS = "ro.miui.os.version.name"
    private const val KEY_VERSION_EMUI = "ro.build.version.emui"
    private const val KEY_VERSION_HARMONY = "hw_sc.build.platform.version"
    private const val KEY_VERSION_ONEPLUS = "ro.rom.version"

    private var cachedType: RomType? = null
    private var cachedVersion: String? = null
    private var cachedMajorVersion: Int? = null

    val romType: RomType
        get() {
            if (cachedType == null) {
                cachedType = detectRomType()
            }
            return cachedType!!
        }

    val romVersion: String
        get() {
            if (cachedVersion == null) {
                cachedVersion = detectRomVersion()
            }
            return cachedVersion ?: ""
        }

    private val majorVersion: Int
        get() {
            if (cachedMajorVersion == null) {
                cachedMajorVersion = parseMajorVersion(romVersion)
            }
            return cachedMajorVersion ?: 0
        }

    val isOppo: Boolean
        get() = romType == RomType.COLOR_OS

    val isVivo: Boolean
        get() = romType == RomType.ORIGIN_OS || romType == RomType.FUNTOUCH_OS

    val isXiaomi: Boolean
        get() = romType == RomType.MIUI || romType == RomType.HYPER_OS

    val isHuawei: Boolean
        get() = romType == RomType.EMUI || romType == RomType.HARMONY_OS

    fun isOppoOrVivo(): Boolean = isOppo || isVivo

    fun isColorOS12OrAbove(): Boolean {
        if (!isOppo) return false
        return majorVersion >= 12
    }

    fun isOriginOS3OrAbove(): Boolean {
        if (romType != RomType.ORIGIN_OS) return false
        return majorVersion >= 3
    }

    fun isOriginOS5OrAbove(): Boolean {
        if (romType != RomType.ORIGIN_OS) return false
        return majorVersion >= 5
    }

    fun isOriginOS6OrAbove(): Boolean {
        if (romType != RomType.ORIGIN_OS) return false
        return majorVersion >= 6
    }

    private fun parseMajorVersion(version: String): Int {
        if (version.isBlank()) return 0
        val normalized = version
            .replace("OriginOS", "", ignoreCase = true)
            .replace("FuntouchOS", "", ignoreCase = true)
            .replace("origin", "", ignoreCase = true)
            .replace("funtouch", "", ignoreCase = true)
            .replace("OS", "", ignoreCase = true)
            .trim()
        return try {
            normalized.substringBefore(".").toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun detectRomType(): RomType {
        val props = readBuildProps()

        return when {
            !props.getProperty(KEY_VERSION_OPPO).isNullOrBlank() -> RomType.COLOR_OS
            !props.getProperty(KEY_VERSION_ONEPLUS).isNullOrBlank() -> RomType.ONEPLUS
            !props.getProperty(KEY_VERSION_HARMONY).isNullOrBlank() -> RomType.HARMONY_OS
            !props.getProperty(KEY_VERSION_EMUI).isNullOrBlank() -> RomType.EMUI
            isHyperOs(props) -> RomType.HYPER_OS
            !props.getProperty(KEY_VERSION_MIUI).isNullOrBlank() -> RomType.MIUI
            isVivoDevice(props) -> {
                if (isOriginOsByProps(props)) {
                    RomType.ORIGIN_OS
                } else {
                    RomType.FUNTOUCH_OS
                }
            }
            else -> matchByManufacturer()
        }
    }

    private fun isVivoDevice(props: Properties): Boolean {
        if (Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
            Build.MANUFACTURER.contains("iqoo", ignoreCase = true) ||
            Build.BRAND.contains("vivo", ignoreCase = true) ||
            Build.BRAND.contains("iqoo", ignoreCase = true)
        ) {
            return true
        }

        val hasVivoProp = listOf(
            KEY_VERSION_VIVO,
            KEY_VERSION_VIVO_NAME,
            KEY_VERSION_VIVO_SDK,
            KEY_VERSION_VIVO_PRODUCT,
            KEY_VERSION_VIVO_MODEL,
            KEY_VERSION_VIVO_DISPLAY
        ).any { !props.getProperty(it, "").isNullOrBlank() }

        return hasVivoProp
    }

    private fun isOriginOsByProps(props: Properties): Boolean {
        val vivoVersion = props.getProperty(KEY_VERSION_VIVO, "")
        val vivoName = props.getProperty(KEY_VERSION_VIVO_NAME, "")

        if (vivoVersion.startsWith("OriginOS", ignoreCase = true) ||
            vivoVersion.startsWith("origin", ignoreCase = true) ||
            vivoName.startsWith("OriginOS", ignoreCase = true) ||
            vivoName.startsWith("origin", ignoreCase = true)
        ) {
            return true
        }

        val rawVersion = vivoVersion.ifBlank { vivoName }
        val major = parseMajorVersion(rawVersion)
        if (major >= 4) {
            return true
        }

        val hasVivoProp = listOf(
            KEY_VERSION_VIVO,
            KEY_VERSION_VIVO_NAME,
            KEY_VERSION_VIVO_SDK,
            KEY_VERSION_VIVO_PRODUCT,
            KEY_VERSION_VIVO_MODEL,
            KEY_VERSION_VIVO_DISPLAY
        ).any { !props.getProperty(it, "").isNullOrBlank() }

        if (hasVivoProp || Build.MANUFACTURER.contains("vivo", ignoreCase = true)) {

            return major >= 3 || rawVersion.isBlank()
        }

        return false
    }

    private fun isHyperOs(props: Properties): Boolean {

        val miuiVersion = props.getProperty(KEY_VERSION_MIUI, "")
        val miuiOsVersion = props.getProperty(KEY_VERSION_MIUI_OS, "")
        return miuiOsVersion.contains("HyperOS", ignoreCase = true) ||
            miuiOsVersion.contains("hyperos", ignoreCase = true) ||
            miuiVersion.contains("HyperOS", ignoreCase = true)
    }

    private fun matchByManufacturer(): RomType {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        return when {
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> RomType.COLOR_OS
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> RomType.ORIGIN_OS
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> RomType.MIUI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> RomType.EMUI
            manufacturer.contains("oneplus") -> RomType.ONEPLUS
            manufacturer.contains("samsung") -> RomType.SAMSUNG
            else -> RomType.OTHER
        }
    }

    private fun detectRomVersion(): String {
        val props = readBuildProps()
        return when (romType) {
            RomType.COLOR_OS -> props.getProperty(KEY_VERSION_OPPO, "")
            RomType.ORIGIN_OS, RomType.FUNTOUCH_OS -> props.getProperty(KEY_VERSION_VIVO, "")
            RomType.MIUI, RomType.HYPER_OS -> props.getProperty(KEY_VERSION_MIUI, "")
            RomType.EMUI -> props.getProperty(KEY_VERSION_EMUI, "")
            RomType.HARMONY_OS -> props.getProperty(KEY_VERSION_HARMONY, "")
            RomType.ONEPLUS -> props.getProperty(KEY_VERSION_ONEPLUS, "")
            else -> ""
        }
    }

    private fun readBuildProps(): Properties {
        val props = Properties()
        try {
            val buildProp = File(Environment.getRootDirectory(), "build.prop")
            if (buildProp.canRead()) {
                FileInputStream(buildProp).use { props.load(it) }
            }
        } catch (_: Exception) {

        }

        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            arrayOf(
                KEY_VERSION_OPPO,
                KEY_VERSION_VIVO,
                KEY_VERSION_VIVO_NAME,
                KEY_VERSION_VIVO_SDK,
                KEY_VERSION_VIVO_PRODUCT,
                KEY_VERSION_VIVO_MODEL,
                KEY_VERSION_VIVO_DISPLAY,
                KEY_VERSION_MIUI,
                KEY_VERSION_MIUI_OS,
                KEY_VERSION_EMUI,
                KEY_VERSION_HARMONY,
                KEY_VERSION_ONEPLUS
            ).forEach { key ->
                if (props.getProperty(key).isNullOrBlank()) {
                    val value = getMethod.invoke(null, key, "") as? String
                    if (!value.isNullOrBlank()) {
                        props.setProperty(key, value)
                    }
                }
            }
        } catch (_: Exception) {

        }

        return props
    }

    fun isComponentAvailable(context: Context, packageName: String, className: String): Boolean {
        return try {
            val intent = android.content.Intent().setClassName(packageName, className)
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    fun findAvailableComponent(
        context: Context,
        candidates: List<Pair<String, String>>
    ): android.content.ComponentName? {
        for ((pkg, cls) in candidates) {
            if (isComponentAvailable(context, pkg, cls)) {
                return android.content.ComponentName(pkg, cls)
            }
        }
        return null
    }

    fun getRomDisplayName(): String {
        return when (romType) {
            RomType.COLOR_OS -> "ColorOS"
            RomType.ORIGIN_OS -> "OriginOS"
            RomType.FUNTOUCH_OS -> "FuntouchOS"
            RomType.MIUI -> "MIUI"
            RomType.HYPER_OS -> "HyperOS"
            RomType.EMUI -> "EMUI"
            RomType.HARMONY_OS -> "HarmonyOS"
            RomType.ONEPLUS -> "OxygenOS/ColorOS"
            RomType.SAMSUNG -> "OneUI"
            RomType.OTHER -> Build.MANUFACTURER
        }
    }

    private fun String.contains(other: String, ignoreCase: Boolean): Boolean {
        return if (ignoreCase) {
            this.lowercase(Locale.getDefault()).contains(other.lowercase(Locale.getDefault()))
        } else {
            this.contains(other)
        }
    }
}
