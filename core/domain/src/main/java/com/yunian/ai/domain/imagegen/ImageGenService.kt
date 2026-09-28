package com.yunian.ai.domain.imagegen

/**
 * AI 生图的跨 feature 入口。
 *
 * feature 之间禁止互相依赖，微信 / QQ 桥接链路（feature:wechat / feature:qqbot）
 * 不能直接用 feature:chat 里的 [ImageGenCoordinator]，因此由 feature:chat 实现本接口、
 * 在 `:app` 注册进 `ServiceRegistry`，桥接链路只依赖 core:domain。
 *
 * 判定逻辑（关键词 → 概率 → 冷却）与 App 内聊天完全一致，不存在第二份实现。
 */
interface ImageGenService {

    /**
     * 判定并生图，成功后把图片消息写入该伴侣的聊天记录。
     *
     * 本方法**不抛异常**：总开关关闭、未配置、未命中、生图失败都会返回空列表，
     * 调用方（桥接链路）无需 try/catch，也不允许因为生图失败影响聊天主流程。
     *
     * @param companionId 伴侣 id
     * @param userText 用户本轮输入（用于关键词判定）
     * @param aiText 模型原始回复（用于标签提取与关键词判定）
     * @param mirrorToWeChat 是否把生成的图片同步镜像到微信（App 内聊天为 true；
     *                       微信桥接自己负责发送，必须传 false，否则重复发送）
     * @param onMessage 可选的过程提示回调，(文案, 是否为错误)
     * @return 已落库的图片信息；未触发或失败时为空列表
     */
    suspend fun generateForReply(
        companionId: Long,
        userText: String,
        aiText: String,
        mirrorToWeChat: Boolean = false,
        onMessage: ((String, Boolean) -> Unit)? = null,
    ): List<GeneratedImageRecord>
}

/** 生图落库后的结果，供桥接链路把同一张图发到微信 / QQ。 */
data class GeneratedImageRecord(
    /** 写入聊天记录后的消息 id（>0 表示落库成功） */
    val messageId: Long,
    /** 生成本地文件路径 */
    val filePath: String,
    /** 实际送去生图的画面描述 */
    val prompt: String,
)
