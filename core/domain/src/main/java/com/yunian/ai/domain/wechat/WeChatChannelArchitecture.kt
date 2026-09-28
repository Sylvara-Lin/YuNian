package com.yunian.ai.domain.wechat

/**
 * 微信通道重构架构进度与已锁定决策。
 *
 * 详见 docs/wechat-channel-refactor-architecture.md
 *
 * ## 已锁定（2026-07-20）
 * 1. 模块形态 A：新建 `:core:wechat` + 瘦 `:feature:wechat`
 * 2. DialoguePort：app 适配现有 AI（ServiceRegistry），禁止 feature→feature
 * 3. 气泡：整条回复 = 一条气泡；连发由上层 BubbleLoopRunner 多次调用产出（不再客户端分句）
 * 4. S0：双端数据类型对齐；S1+S2：Outbox 分段投递 + Inbox 去重串行
 * 5. S3：Bridge 拆除内嵌 AI；[WeChatDialoguePort] 由 app 绑定
 * 6. S4：Transport 会话稳定（热更新 contextToken、主轮询租约、指数退避）
 * 7. S5：映射管理 UI + 可观测性（IdentityMapPort、通道健康快照、失败原因码）
 * 8. S6：删除 Broadcast 主路径；[WeChatOutboundPort] + [WeChatProactiveSync]
 * 9. S7：表情物化本地路径后入 Outbox（可重试），禁止字节直发主路径
 * 10. S8：Outbox 引用感知的表情缓存生命周期清理
 * 11. S9：ilink SDK、会话客户端与消息映射归 core
 * 12. S10：Android Keystore 外层存储内增加 Native 凭证密封
 * 13. 不下沉协议到 Native
 *
 * ## 硬约束
 * - feature 互不依赖
 * - 协议与业务留在 Kotlin；Native 仅可选凭证密封（后置）
 * - REASONING / STREAMING 不同步到微信
 * - 微信通道不复制完整 AI 管线
 */
object WeChatChannelArchitecture {
    const val SLICE = 10
    const val DOC_VERSION = "2026-07-25"

    /** S10：Native 凭证密封 */
    const val SLICE_NAME = "native-credential-sealing"

    const val SEGMENT_MODE = "SIMPLE"
    const val MODULE_SHAPE = "core:wechat"
    const val DIALOGUE_BINDING = "app-adapter"
    const val NATIVE_PROTOCOL = false
}
