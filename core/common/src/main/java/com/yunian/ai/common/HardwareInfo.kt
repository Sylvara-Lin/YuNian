package com.yunian.ai.common

import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object HardwareInfo {

    enum class Tier {
        LOW,
        MEDIUM,
        HIGH,
        ULTRA
    }

    private val tierLock = Any()

    /**
     * 档位缓存：`@Volatile` + 双重检查加锁（T02）。
     *
     * 冷探测 `detectTier()` 会 fork `getprop` 子进程并读取 sysfs，**绝不能有双写竞态**
     * （否则会重复跑重活），因此这里用「volatile 快路径 + synchronized 慢路径」保证：
     * 1. 同一档位只会被探测一次，结果缓存在 [_tier]；
     * 2. 预热（[warmUp]，在后台线程）与主线程读取竞争时，只会有一次真正探测；
     * 3. 预热前后同一设备档位**完全一致** —— [detectTier] 是设备的纯函数，缓存不改变其结果。
     */
    @Volatile
    private var _tier: Tier? = null

    val tier: Tier
        get() {
            _tier?.let { return it }
            synchronized(tierLock) {
                _tier?.let { return it }
                val detected = detectTier()
                _tier = detected
                return detected
            }
        }

    /**
     * 预热档位：在**调用线程**上完成一次冷探测并缓存。
     *
     * 应用启动时应在后台线程（如 `bgScope` / `Dispatchers.IO`）调用本方法，
     * 使首次进入聊天页时 `PageTransitions` / `ChatScreen` 读取 [tier] 只命中缓存、
     * 不再在转场起帧那一帧触发 `Runtime.exec("getprop")` + sysfs 读（R2）。
     *
     * 幂等：已缓存时直接返回；并发调用由 [tier] 的双重检查加锁去重。
     */
    fun warmUp() {
        if (_tier != null) return
        PerformanceTrace.markTierDetectStart()
        tier
        PerformanceTrace.markTierDetectDone()
    }

    val maxFps: Int
        get() = CpuInfo.maxRefreshRate

    val isHighPerf: Boolean
        get() = tier == Tier.HIGH || tier == Tier.ULTRA

    val isMidPerf: Boolean
        get() = tier == Tier.MEDIUM

    val isGpuBackdropSafe: Boolean
        get() = CpuInfo.isSnapdragon

    private fun detectTier(): Tier {
        return when {
            CpuInfo.isSnapdragon8GenSeries -> Tier.ULTRA
            CpuInfo.isDimensity9000Series -> Tier.ULTRA
            CpuInfo.isKirin9000Series -> Tier.ULTRA

            CpuInfo.isSnapdragon8xx -> Tier.HIGH
            CpuInfo.isDimensity8000Up -> Tier.HIGH
            CpuInfo.isKirin8xxUp -> Tier.HIGH
            CpuInfo.isTensor -> Tier.HIGH
            CpuInfo.isExynos2000Up -> Tier.HIGH

            CpuInfo.isSnapdragon7xx -> Tier.MEDIUM
            CpuInfo.isDimensity7000 -> Tier.MEDIUM
            CpuInfo.isSnapdragon6xx -> Tier.MEDIUM
            CpuInfo.isDimensity6000 -> Tier.MEDIUM

            else -> {
                if (CpuInfo.coreCount >= 8 && CpuInfo.maxFreqMhz >= 2200) Tier.MEDIUM
                else Tier.LOW
            }
        }
    }

    object CpuInfo {
        val hardware: String by lazy { safeReadProp("ro.hardware", "").lowercase() }
        val chipset: String by lazy { safeReadProp("ro.board.platform", "").lowercase() }
        val socModel: String by lazy { safeReadProp("ro.soc.model", "").lowercase() }
        val cpuAbiList: String by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS.joinToString(",")
            } else {
                @Suppress("DEPRECATION") Build.CPU_ABI
            }
        }
        val coreCount: Int by lazy { Runtime.getRuntime().availableProcessors() }
        val maxFreqMhz: Int by lazy { readCpuMaxFreq() }
        val maxRefreshRate: Int by lazy {
            try {
                val rate = Build::class.java.getField("REFRESH_RATE").get(null) as? Float
                rate?.toInt() ?: 60
            } catch (e: Exception) { 60 }
        }

        val isSnapdragon: Boolean get() = hardware.contains("qcom") || chipset.contains("msm") || chipset.contains("sm")
        val isMediatek: Boolean get() = hardware.contains("mt") || chipset.contains("mt") || socModel.contains("mediatek") || socModel.contains("dimensity")
        val isHisilicon: Boolean get() = hardware.contains("kirin") || chipset.contains("kirin") || socModel.contains("kirin") || chipset.contains("hi3650") || chipset.contains("hi3660") || chipset.contains("hi3670")
        val isExynos: Boolean get() = hardware.contains("exynos") || chipset.contains("exynos") || socModel.contains("exynos")
        val isTensor: Boolean get() = hardware.contains("gs") || chipset.contains("gs") || socModel.contains("tensor")

        val isSnapdragon8GenSeries: Boolean get() =
            isSnapdragon && (chipset.startsWith("sm8") || chipset in listOf("pineapple", "sun", "tuna") || chipset.contains("gen"))
        val isSnapdragon8xx: Boolean get() =
            isSnapdragon && (chipset.startsWith("sm8") || chipset.startsWith("msm8") || chipset.contains("8"))
        val isSnapdragon7xx: Boolean get() =
            isSnapdragon && (chipset.startsWith("sm7") || chipset.contains("7"))
        val isSnapdragon6xx: Boolean get() =
            isSnapdragon && (chipset.startsWith("sm6") || chipset.contains("6"))

        val isDimensity9000Series: Boolean get() =
            isMediatek && (chipset.contains("9000") || chipset.contains("9400") || chipset.contains("dimensity9"))
        val isDimensity8000Up: Boolean get() =
            isMediatek && (chipset.contains("8000") || chipset.contains("8100") || chipset.contains("8200") || chipset.contains("8300") || chipset.contains("8400"))
        val isDimensity7000: Boolean get() =
            isMediatek && chipset.contains("7000")
        val isDimensity6000: Boolean get() =
            isMediatek && chipset.contains("6000")

        val isKirin9000Series: Boolean get() =
            isHisilicon && (chipset.contains("9000") || chipset.contains("9010") || chipset.contains("9020"))
        val isKirin8xxUp: Boolean get() =
            isHisilicon && (chipset.contains("8000") || chipset.contains("810") || chipset.contains("820") || chipset.contains("830") || chipset.contains("900") || chipset.contains("990"))

        val isExynos2000Up: Boolean get() =
            isExynos && (chipset.contains("2") || chipset.contains("2200") || chipset.contains("2400"))
    }

    fun getFullInfo(): String {
        val files = listOf(
            "/proc/cpuinfo" to "cpuinfo",
            "/sys/devices/soc0/soc_id" to "soc_id",
            "/sys/devices/soc0/machine" to "machine"
        )
        val sb = StringBuilder()
        sb.appendLine("=== Hardware Info ===")
        sb.appendLine("Tier: $tier")
        sb.appendLine("Hardware: ${CpuInfo.hardware}")
        sb.appendLine("Chipset: ${CpuInfo.chipset}")
        sb.appendLine("SoC Model: ${CpuInfo.socModel}")
        sb.appendLine("Cores: ${CpuInfo.coreCount}")
        sb.appendLine("Max Freq: ${CpuInfo.maxFreqMhz}MHz")
        sb.appendLine("Max Refresh: ${CpuInfo.maxRefreshRate}Hz")
        sb.appendLine("ABI: ${CpuInfo.cpuAbiList}")
        sb.appendLine("Snapdragon: ${CpuInfo.isSnapdragon}")
        sb.appendLine("MediaTek: ${CpuInfo.isMediatek}")
        sb.appendLine("Kirin: ${CpuInfo.isHisilicon}")
        files.forEach { (path, name) ->
            try {
                sb.appendLine("$name: ${File(path).readText().trim()}")
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    private fun safeReadProp(key: String, default: String): String {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val value = reader.readLine()?.trim() ?: default
            reader.close()
            process.destroy()
            return value
        } catch (e: Exception) {
            return default
        }
    }

    private fun readCpuMaxFreq(): Int {
        var maxFreq = 0
        for (i in 0 until 16) {
            try {
                val path = "/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq"
                val freq = File(path).readText().trim().toIntOrNull() ?: 0
                if (freq > maxFreq) maxFreq = freq
            } catch (_: Exception) {
                break
            }
        }
        return if (maxFreq > 0) maxFreq / 1000 else 2000
    }
}
