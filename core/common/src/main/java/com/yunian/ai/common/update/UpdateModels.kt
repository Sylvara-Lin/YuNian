package com.yunian.ai.common.update

data class UpdateInfo(
    val versionName: String = "",
    val versionCode: Long = 0,
    val updateUrl: String = "",
    val updateLog: String = "",
    val fileSize: Long = 0L,
    val publishDate: String = "",
    val isForceUpdate: Boolean = false,
    val sha256: String = "",
    /** 备用下载源，弱网/主源失败时按序切换 */
    val mirrors: List<String> = emptyList(),
    /** 下载地址失效时间（Unix 秒）。>0 且已过期时应重新拉取清单刷新签名地址 */
    val urlExpiresAt: Long = 0L
) {
    /** 下载地址是否已失效（含 60 秒安全余量） */
    fun isUrlExpired(nowSec: Long = System.currentTimeMillis() / 1000): Boolean =
        urlExpiresAt > 0 && nowSec + 60 >= urlExpiresAt
}

data class DownloadProgress(
    val progress: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    /** 实时下载速率（字节/秒），用于弱网检测 */
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.IDLE
)

enum class DownloadStatus {
    IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED, INSTALLING
}

enum class UpdateCheckState {
    IDLE, CHECKING, AVAILABLE, LATEST, ERROR
}

/**
 * 更新下载方式：
 * - [IMMEDIATE] 立即更新：前台展示进度，完成后拉起安装
 * - [BACKGROUND] 后台更新：静默后台下载，完成后发系统通知
 */
enum class UpdateMode {
    IMMEDIATE, BACKGROUND
}
