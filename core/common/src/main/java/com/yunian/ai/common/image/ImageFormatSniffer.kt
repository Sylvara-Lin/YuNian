package com.yunian.ai.common.image

/**
 * 图片格式嗅探（Bug2 修复核心）。
 *
 * 纯粹按文件头**魔数**判定格式，不依赖 `OpenableColumns.DISPLAY_NAME` 后缀，
 * 也不依赖 `ContentResolver.getType` 的 MIME —— SAF / 第三方 provider
 * （微信、QQ、华为相册、部分文件管理器）经常不给后缀（如 `1000045678`）
 * 或返回 `application/octet-stream` / null，导致合法 jpg/png 被误判为「非图片」。
 *
 * 纯函数、无 Android 依赖，可直接在 JVM 单测覆盖。
 */
object ImageFormatSniffer {

    /** 建议读取的头字节数：足够覆盖 HEIC/HEIF 的 `ftyp` + major brand。 */
    const val HEADER_SIZE = 16

    /** HEIC 家族 major brand（`ftyp` 后 4 字节）。 */
    private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")

    /** HEIF 家族 major brand。 */
    private val HEIF_BRANDS = setOf("mif1", "msf1")

    /**
     * 按文件头魔数返回扩展名（`png` / `jpg` / `gif` / `webp` / `bmp` / `heic` / `heif`）；
     * 无法识别返回 null。
     *
     * 传入的 [header] 允许短于文件真实长度（头部被截断时安全返回 null，不抛异常）。
     */
    fun detect(header: ByteArray): String? {
        if (header.size < 3) return null

        // PNG: 89 50 4E 47
        if (header.size >= 4 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
        ) {
            return "png"
        }

        // JPEG: FF D8 FF
        if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "jpg"
        }

        // GIF: 47 49 46 38 ("GIF8")
        if (header.size >= 4 &&
            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() && header[3] == 0x38.toByte()
        ) {
            return "gif"
        }

        // WEBP: "RIFF" .... "WEBP"（偏移 4..12）
        if (header.size >= 12 &&
            header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
            header[10] == 0x42.toByte() && header[11] == 0x50.toByte()
        ) {
            return "webp"
        }

        // BMP: 42 4D ("BM")
        if (header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()) {
            return "bmp"
        }

        // HEIC / HEIF: 偏移 4..8 为 "ftyp"，偏移 8..12 为 major brand
        if (header.size >= 12 &&
            header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
            header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
        ) {
            val brand = String(header, 8, 4, Charsets.US_ASCII).lowercase()
            if (brand in HEIC_BRANDS) return "heic"
            if (brand in HEIF_BRANDS) return "heif"
        }

        return null
    }
}
