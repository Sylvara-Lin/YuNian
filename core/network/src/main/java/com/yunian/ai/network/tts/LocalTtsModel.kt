package com.yunian.ai.network.tts

import android.content.Context
import java.io.File

sealed class LocalTtsModel(
    val id: String,
    val displayName: String,
    val files: List<LocalTtsModelFile>,
    val numSpeakers: Int,
    val modelType: LocalTtsModelType
) {

    fun modelDir(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(root, "models"), "tts/$id").also { it.mkdirs() }
    }

    fun file(context: Context, fileName: String): File = File(modelDir(context), fileName)

    fun isAllFilesPresent(context: Context): Boolean =
        files.all { file(context, it.fileName).exists() }

    val modelFileName: String get() = files.first { it.isMainModel }.fileName

    val tokensFileName: String get() = files.first { it.role == LocalTtsFileRole.TOKENS }.fileName

    val lexiconFileName: String? get() = files.firstOrNull { it.role == LocalTtsFileRole.LEXICON }?.fileName

    data object Aishell3 : LocalTtsModel(
        id = "vits_zh_aishell3",
        displayName = "VITS 中文 aishell3 (174音色)",
        numSpeakers = 174,
        modelType = LocalTtsModelType.VITS,
        files = listOf(
            LocalTtsModelFile(
                fileName = "model.onnx",
                downloadUrl = "",
                sha256 = "",
                expectedBytes = 1_200_000_000L,
                role = LocalTtsFileRole.MAIN_MODEL,
                isMainModel = true
            ),
            LocalTtsModelFile(
                fileName = "tokens.txt",
                downloadUrl = "",
                sha256 = "",
                expectedBytes = 0L,
                role = LocalTtsFileRole.TOKENS,
                isMainModel = false
            ),
            LocalTtsModelFile(
                fileName = "lexicon.txt",
                downloadUrl = "",
                sha256 = "",
                expectedBytes = 0L,
                role = LocalTtsFileRole.LEXICON,
                isMainModel = false
            )
        )
    )

    data object Custom : LocalTtsModel(
        id = "custom",
        displayName = "自定义 VITS 模型",
        numSpeakers = 1,
        modelType = LocalTtsModelType.VITS,
        files = listOf(
            LocalTtsModelFile(
                fileName = "model.onnx",
                downloadUrl = "",
                sha256 = "",
                expectedBytes = 0L,
                role = LocalTtsFileRole.MAIN_MODEL,
                isMainModel = true
            ),
            LocalTtsModelFile(
                fileName = "tokens.txt",
                downloadUrl = "",
                sha256 = "",
                expectedBytes = 0L,
                role = LocalTtsFileRole.TOKENS,
                isMainModel = false
            ),
            LocalTtsModelFile(
                fileName = "lexicon.txt",
                downloadUrl = "",
                sha256 = "",
                expectedBytes = 0L,
                role = LocalTtsFileRole.LEXICON,
                isMainModel = false
            )
        )
    )
}

enum class LocalTtsModelType {
    VITS,
    MATCHA,
    KOKORO
}

enum class LocalTtsFileRole {
    MAIN_MODEL,
    TOKENS,
    LEXICON,
    VOCODER,
    VOICES,
    DATA_DIR
}

data class LocalTtsModelFile(
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val expectedBytes: Long,
    val role: LocalTtsFileRole = LocalTtsFileRole.MAIN_MODEL,
    val isMainModel: Boolean = false
)

object LocalTtsCatalog {
    val default: LocalTtsModel = LocalTtsModel.Aishell3
    val all: List<LocalTtsModel> = listOf(LocalTtsModel.Aishell3, LocalTtsModel.Custom)

    fun findById(modelId: String): LocalTtsModel =
        all.find { it.id == modelId } ?: default
}
