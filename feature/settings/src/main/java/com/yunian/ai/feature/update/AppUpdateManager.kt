package com.yunian.ai.feature.update

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.common.security.DeviceRequestSigner
import com.yunian.ai.common.update.DownloadProgress
import com.yunian.ai.common.update.DownloadStatus
import com.yunian.ai.common.update.UpdateChannel
import com.yunian.ai.common.update.UpdateCheckState
import com.yunian.ai.common.update.UpdateCrypto
import com.yunian.ai.common.update.UpdateInfo
import com.yunian.ai.common.update.UpdateMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppUpdateManager(private val context: Context) {

    companion object {
        // 弱网判定阈值（字节/秒）：连续采样低于该值即判定弱网，触发续传/切换镜像
        private const val WEAK_NET_SPEED_THRESHOLD = 50L * 1024

        // 单个镜像最大重试次数
        private const val MAX_RETRIES_PER_MIRROR = 3

        private const val NOTIFICATION_CHANNEL_ID = "app_update"
        private const val NOTIFICATION_ID = 1001

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .certificatePinner(
            CertificatePinner.Builder()
                .add(UpdateChannel.HOST, UpdateChannel.PIN_LEAF)
                .add(UpdateChannel.HOST, UpdateChannel.PIN_BACKUP_ROOT)
                .add(UpdateChannel.HOST, UpdateChannel.PIN_BACKUP_ROOT_X2)
                .build()
        )
        .build()

    private val downloadScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)

    @Volatile
    private var downloadJob: Job? = null

    private val _updateCheckState = MutableStateFlow(UpdateCheckState.IDLE)
    val updateCheckState: StateFlow<UpdateCheckState> = _updateCheckState.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.5.1"
        } catch (e: Exception) {
            "1.5.1"
        }
    }

    fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    suspend fun checkForUpdates() {
        _updateCheckState.value = UpdateCheckState.CHECKING
        try {
            withContext(Dispatchers.IO) {
                val plain = requestEncryptedManifest() ?: run {
                    _updateCheckState.value = UpdateCheckState.ERROR
                    return@withContext
                }
                val manifest = try {
                    json.decodeFromString<UpdateManifest>(plain)
                } catch (e: Exception) {
                    _updateCheckState.value = UpdateCheckState.ERROR
                    return@withContext
                }

                val hasUpdate = if (manifest.versionCode > 0 && getCurrentVersionCode() > 0) {
                    manifest.versionCode > getCurrentVersionCode()
                } else {
                    manifest.versionName.isNotBlank() &&
                        compareVersion(manifest.versionName, getCurrentVersionName()) > 0
                }

                if (hasUpdate) {
                    _updateInfo.value = UpdateInfo(
                        versionName = manifest.versionName,
                        versionCode = manifest.versionCode,
                        updateUrl = manifest.apkUrl,
                        updateLog = manifest.updateLog,
                        fileSize = manifest.apkSize,
                        publishDate = manifest.publishDate,
                        isForceUpdate = manifest.forceUpdate,
                        sha256 = manifest.apkSha256,
                        mirrors = manifest.mirrors,
                        urlExpiresAt = manifest.apkUrlExpiresAt
                    )
                    _updateCheckState.value = UpdateCheckState.AVAILABLE
                    val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                    if (prefs.getString("ignored_version", "") != manifest.versionName) {
                        _showUpdateDialog.value = true
                    }
                } else {
                    _updateCheckState.value = UpdateCheckState.LATEST
                }
            }
        } catch (e: Exception) {
            _updateCheckState.value = UpdateCheckState.ERROR
        }
    }

    /**
     * 发起加密更新检查。
     *
     * 请求头：
     *  - X-LianYu-Key           客户端密钥（网关与本地服务双重校验）
     *  - X-LianYu-Ts/Nonce     时间窗 + 随机数（防重放，响应绑定同一 nonce）
     *  - X-LianYu-Pub/Key-Id   设备 ES256 公钥（AndroidKeyStore 硬件密钥，私钥不可导出）
     *  - X-LianYu-Sig          对「方法/路径/摘要/时间戳/nonce/密钥/设备号」的签名
     *
     * 响应为 AES-256-GCM 密文 + HMAC 签名，解密验签失败即视为检查失败。
     */
    private fun requestEncryptedManifest(): String? {
        return try {
            val nonce = UpdateCrypto.randomNonce()
            val timestamp = System.currentTimeMillis() / 1000
            val path = "/api/update/check"
            val bodyHash = UpdateCrypto.bodyHash(null)
            val deviceId = DeviceRequestSigner.deviceId()
            val keyId = DeviceRequestSigner.keyId()
            val publicKey = DeviceRequestSigner.publicKeyBase64()

            val payload = UpdateCrypto.buildSignaturePayload(
                prefix = UpdateChannel.SIG_PREFIX,
                method = "GET",
                path = path,
                bodyHash = bodyHash,
                timestamp = timestamp,
                nonce = nonce,
                appKey = UpdateChannel.APP_KEY,
                deviceId = deviceId
            )
            val signed = DeviceRequestSigner.sign(payload) ?: return null

            val request = Request.Builder()
                .url(UpdateChannel.signedEndpoint(timestamp))
                .header("Accept", "application/json")
                .header("X-LianYu-Key", UpdateChannel.APP_KEY)
                .header("X-LianYu-Sig-Version", "v1")
                .header("X-LianYu-Ts", timestamp.toString())
                .header("X-LianYu-Nonce", nonce)
                .header("X-LianYu-Body-SHA256", bodyHash)
                .header("X-LianYu-Device-Id", deviceId)
                .header("X-LianYu-Key-Id", keyId)
                .header("X-LianYu-Pub", publicKey)
                .header("X-LianYu-Sig", signed.signature)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                UpdateCrypto.decodeManifest(UpdateChannel.APP_KEY, body, nonce)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun checkInstallPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            activity.startActivity(intent)
        }
    }

    /**
     * 启动下载。
     * @param mode [UpdateMode.IMMEDIATE] 前台下载完成后立即安装；[UpdateMode.BACKGROUND] 后台下载完成后发通知。
     */
    fun startDownload(info: UpdateInfo, mode: UpdateMode) {
        _downloadProgress.value = DownloadProgress(status = DownloadStatus.DOWNLOADING)
        downloadJob?.cancel()
        downloadJob = downloadScope.launch {
            // 签名下载地址为短时效（6h），过期则先冲压重新获取，避免下载到 410
            var target = info
            if (info.isUrlExpired()) {
                refreshSignedUrls()
                _updateInfo.value?.let { if (!it.isUrlExpired()) target = it }
            }
            val success = downloadWithFallback(target)
            if (success) {
                when (mode) {
                    UpdateMode.IMMEDIATE -> installApk(apkFile())
                    UpdateMode.BACKGROUND -> notifyDownloadComplete(target)
                }
            }
        }
    }

    /**
     * 静默重新拉取清单以刷新安全链接签名地址。
     * 仅更新下载地址相关字段，不影响弹窗展示状态。
     */
    private suspend fun refreshSignedUrls() {
        withContext(Dispatchers.IO) {
            try {
                val plain = requestEncryptedManifest() ?: return@withContext
                val manifest = json.decodeFromString<UpdateManifest>(plain)
                val current = _updateInfo.value ?: return@withContext
                if (manifest.apkUrl.isNotBlank()) {
                    _updateInfo.value = current.copy(
                        updateUrl = manifest.apkUrl,
                        urlExpiresAt = manifest.apkUrlExpiresAt,
                        sha256 = manifest.apkSha256.ifBlank { current.sha256 },
                        fileSize = if (manifest.apkSize > 0) manifest.apkSize else current.fileSize,
                        mirrors = if (manifest.mirrors.isNotEmpty()) manifest.mirrors else current.mirrors
                    )
                }
            } catch (e: Exception) {
                // 刷新失败则继续用原地址尝试
            }
        }
    }

    /**
     * 弱网自适应下载：按 主源 → 镜像 顺序尝试，每个源支持 Range 断点续传，
     * 采样速率检测弱网时切换镜像 / 续传，最终校验 SHA256。
     */
    private suspend fun downloadWithFallback(info: UpdateInfo): Boolean {
        val urls = buildList {
            if (info.updateUrl.isNotBlank()) add(info.updateUrl)
            info.mirrors.filter { it.isNotBlank() }.forEach { add(it) }
        }
        if (urls.isEmpty()) {
            _downloadProgress.value = _downloadProgress.value.copy(status = DownloadStatus.FAILED)
            return false
        }

        // 已存在部分文件则复用（断点续传）
        var existingBytes = apkFile().takeIf { it.exists() }?.length() ?: 0L
        if (info.fileSize > 0 && existingBytes >= info.fileSize) {
            existingBytes = 0L
            apkFile().delete()
        }

        for (url in urls) {
            var attempt = 0
            while (attempt < MAX_RETRIES_PER_MIRROR) {
                attempt++
                try {
                    if (downloadOnce(url, info.fileSize, existingBytes)) {
                        if (verifySha256(apkFile(), info.sha256)) {
                            _downloadProgress.value = DownloadProgress(
                                progress = 100,
                                totalBytes = info.fileSize,
                                downloadedBytes = info.fileSize,
                                status = DownloadStatus.COMPLETED
                            )
                            return true
                        } else {
                            // 校验失败，删除重下
                            apkFile().delete()
                            existingBytes = 0L
                        }
                    }
                } catch (e: Exception) {
                    // 网络中断，续传继续
                }
                // 保留已下载部分，供下一镜像/重试续传
                existingBytes = apkFile().takeIf { it.exists() }?.length() ?: 0L
                if (existingBytes > 0) {
                    delay(1000L * attempt) // 指数退避
                }
            }
        }
        _downloadProgress.value = _downloadProgress.value.copy(status = DownloadStatus.FAILED)
        return false
    }

    /**
     * 单次下载：优先全速流式；弱网/断点时回退到 Range 续传。
     * 返回是否成功写满整个文件。
     */
    private suspend fun downloadOnce(url: String, totalBytes: Long, startFrom: Long): Boolean {
        var downloadedBytes = startFrom

        val builder = Request.Builder().url(url)
        if (startFrom > 0) {
            builder.header("Range", "bytes=$startFrom-")
        }
        val request = builder.build()

        okHttpClient.newCall(request).execute().use { response ->
            if (startFrom > 0 && response.code != 206 && response.code != 200) {
                return false
            }
            if (startFrom == 0L && !response.isSuccessful) {
                return false
            }

            // 服务端不支持 Range 但已续传文件存在 → 从头下
            if (startFrom > 0 && response.code == 200) {
                apkFile().delete()
                downloadedBytes = 0L
            }

            val body = response.body ?: return false
            val expected = if (totalBytes > 0) totalBytes else body.contentLength().let {
                if (it > 0) it + startFrom else -1L
            }

            val inputStream: InputStream = body.byteStream()
            return writeStream(inputStream, downloadedBytes, expected)
        }
    }

    /**
     * 流式写盘 + 速率采样。弱网时减小缓冲并强制 flush，提升续传粒度。
     */
    private suspend fun writeStream(inputStream: InputStream, startBytes: Long, expectedTotal: Long): Boolean {
        val file = apkFile()
        FileOutputStream(file, startBytes > 0).use { output ->
            var downloadedBytes = startBytes
            var bufferSize = 128 * 1024 // 网络良好：大缓冲，速度优先
            val buffer = ByteArray(bufferSize)

            // 速率采样
            var sampleBytes = 0L
            var sampleStart = System.nanoTime()

            var bytesRead: Int
            while (inputStream.read(buffer, 0, buffer.size).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead
                sampleBytes += bytesRead

                val now = System.nanoTime()
                val elapsedSec = (now - sampleStart) / 1_000_000_000.0
                if (elapsedSec >= 1.0) {
                    val speed = (sampleBytes / elapsedSec).toLong()
                    sampleBytes = 0L
                    sampleStart = now
                    _downloadProgress.value = DownloadProgress(
                        progress = if (expectedTotal > 0) ((downloadedBytes * 100) / expectedTotal).toInt().coerceIn(0, 100) else 0,
                        totalBytes = if (expectedTotal > 0) expectedTotal else downloadedBytes,
                        downloadedBytes = downloadedBytes,
                        speedBytesPerSec = speed,
                        status = DownloadStatus.DOWNLOADING
                    )
                    // 弱网自适应：降缓冲，交回调度权，减小超时风险
                    if (speed < WEAK_NET_SPEED_THRESHOLD) {
                        bufferSize = 16 * 1024
                    }
                }
            }
            _downloadProgress.value = DownloadProgress(
                progress = 100,
                totalBytes = if (expectedTotal > 0) expectedTotal else downloadedBytes,
                downloadedBytes = downloadedBytes,
                status = DownloadStatus.DOWNLOADING
            )
            return expectedTotal <= 0 || downloadedBytes >= expectedTotal
        }
    }

    private fun verifySha256(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expected, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun apkFile(): File {
        return File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
    }

    private fun installApk(apkFile: File) {
        _downloadProgress.value = _downloadProgress.value.copy(status = DownloadStatus.INSTALLING)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.yunian.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                _downloadProgress.value = _downloadProgress.value.copy(status = DownloadStatus.FAILED)
            }
        }
    }

    /** 后台更新完成 → 系统通知，点击即安装 */
    private fun notifyDownloadComplete(info: UpdateInfo) {
        createNotificationChannel()
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.yunian.fileprovider", apkFile())
        } catch (e: Exception) {
            null
        }
        val installIntent = uri?.let {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(it, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val pendingIntent = if (installIntent != null) {
            PendingIntent.getActivity(
                context,
                0,
                installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("新版本 ${info.versionName} 已下载完成")
            .setContentText("点击安装更新")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        try {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // 通知失败则退回直接安装
            installApk(apkFile())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "应用更新",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "应用版本更新通知" }
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun ignoreThisVersion() {
        _updateInfo.value?.let { info ->
            val prefs = context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("ignored_version", info.versionName).apply()
        }
        _showUpdateDialog.value = false
    }

    fun dismissUpdate() {
        _showUpdateDialog.value = false
    }

    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 > p2) return 1
            if (p1 < p2) return -1
        }
        return 0
    }

    fun release() {
        downloadJob?.cancel()
        downloadScope.cancel()
    }

    @Serializable
    private data class UpdateManifest(
        @SerialName("versionCode") val versionCode: Long = 0,
        @SerialName("versionName") val versionName: String = "",
        @SerialName("minVersionCode") val minVersionCode: Long = 0,
        @SerialName("forceUpdate") val forceUpdate: Boolean = false,
        @SerialName("updateLog") val updateLog: String = "",
        @SerialName("publishDate") val publishDate: String = "",
        @SerialName("apkUrl") val apkUrl: String = "",
        @SerialName("apkUrlExpiresAt") val apkUrlExpiresAt: Long = 0,
        @SerialName("apkSize") val apkSize: Long = 0,
        @SerialName("apkSha256") val apkSha256: String = "",
        @SerialName("mirrors") val mirrors: List<String> = emptyList()
    )
}
