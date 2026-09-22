package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.domain.GeneratedImage
import com.yunian.ai.domain.ImageGenerationProvider
import com.yunian.ai.domain.ImageModelCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenTriggerTest {

    private val baseGlobal = ImageGenGlobalConfig(
        enabled = true,
        connectionMode = "CUSTOM",
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test",
        model = "flux-schnell",
        size = "1024x1024",
        count = 1,
        probability = 0,
        keywords = listOf("画一张", "生图"),
        cooldownMinutes = 3,
        promptTemplate = "{content}"
    )

    private val mainConn = "https://main.example.com/v1" to "sk-main"

    // ------------------------------------------------------------ 配置解析

    @Test
    fun `resolveEffective 在 auto 模式取主 API 连接`() {
        val effective = ImageGenTriggerLogic.resolveEffective(
            global = baseGlobal.copy(connectionMode = "auto", baseUrl = "", apiKey = ""),
            override = ImageGenCompanionOverride(),
            mainConnection = mainConn
        )
        assertTrue(effective.ready)
    }

    @Test
    fun `auto 模式缺少主 API 时未就绪`() {
        val effective = ImageGenTriggerLogic.resolveEffective(
            global = baseGlobal.copy(connectionMode = "auto", baseUrl = "", apiKey = ""),
            override = ImageGenCompanionOverride(),
            mainConnection = null
        )
        assertFalse(effective.ready)
    }

    @Test
    fun `独立模式缺少密钥时未就绪`() {
        val effective = ImageGenTriggerLogic.resolveEffective(
            global = baseGlobal.copy(apiKey = ""),
            override = ImageGenCompanionOverride(),
            mainConnection = mainConn
        )
        assertFalse(effective.ready)
    }

    @Test
    fun `单聊覆盖生效时使用单聊的概率与关键词`() {
        val effective = ImageGenTriggerLogic.resolveEffective(
            global = baseGlobal,
            override = ImageGenCompanionOverride(
                enabled = true,
                probability = 55,
                keywords = listOf("出图")
            ),
            mainConnection = mainConn
        )
        assertEquals(55, effective.probability)
        assertEquals(listOf("出图"), effective.keywords)
    }

    @Test
    fun `单聊未开启覆盖时沿用全局`() {
        val effective = ImageGenTriggerLogic.resolveEffective(
            global = baseGlobal.copy(probability = 30),
            override = ImageGenCompanionOverride(enabled = false, probability = 99, keywords = listOf("出图")),
            mainConnection = mainConn
        )
        assertEquals(30, effective.probability)
        assertEquals(baseGlobal.keywords, effective.keywords)
    }

    // ------------------------------------------------------------ 判定

    private fun decide(
        global: ImageGenGlobalConfig = baseGlobal,
        userText: String = "你好呀",
        aiText: String = "想我了吗",
        lastGenAtMs: Long = 0L,
        nowMs: Long = 10_000_000L,
        roll: Int = 99
    ): ImageGenDecision {
        val effective = ImageGenTriggerLogic.resolveEffective(
            global = global,
            override = ImageGenCompanionOverride(),
            mainConnection = mainConn
        )
        return ImageGenTriggerLogic.decide(global, effective, userText, aiText, lastGenAtMs, nowMs, roll)
    }

    @Test
    fun `总开关关闭时不触发`() {
        val decision = decide(global = baseGlobal.copy(enabled = false), userText = "画一张猫")
        assertFalse(decision.triggered)
        assertEquals(ImageGenTriggerReason.DISABLED, decision.reason)
    }

    @Test
    fun `模型未配置时不触发`() {
        val decision = decide(global = baseGlobal.copy(model = ""), userText = "画一张猫")
        assertFalse(decision.triggered)
        assertEquals(ImageGenTriggerReason.NOT_CONFIGURED, decision.reason)
    }

    @Test
    fun `关键词命中用户消息时无视概率触发`() {
        val decision = decide(
            global = baseGlobal.copy(probability = 0),
            userText = "帮我画一张穿裙子的猫"
        )
        assertTrue(decision.triggered)
        assertEquals(ImageGenTriggerReason.KEYWORD, decision.reason)
        assertEquals("画一张", decision.matchedKeyword)
    }

    @Test
    fun `关键词命中 AI 回复时同样触发`() {
        val decision = decide(
            global = baseGlobal.copy(keywords = listOf("给你看看")),
            aiText = "这就给你看看~"
        )
        assertTrue(decision.triggered)
        assertEquals(ImageGenTriggerReason.KEYWORD, decision.reason)
    }

    @Test
    fun `概率为 0 且无关键词时不触发`() {
        val decision = decide(global = baseGlobal.copy(probability = 0), roll = 0)
        assertFalse(decision.triggered)
        assertEquals(ImageGenTriggerReason.NOT_MATCHED, decision.reason)
    }

    @Test
    fun `概率为 100 时命中`() {
        val decision = decide(global = baseGlobal.copy(probability = 100), roll = 99)
        assertTrue(decision.triggered)
        assertEquals(ImageGenTriggerReason.PROBABILITY, decision.reason)
    }

    @Test
    fun `概率边界 30 时 roll 29 命中 roll 30 不命中`() {
        val global = baseGlobal.copy(probability = 30)
        assertTrue(decide(global = global, roll = 29).triggered)
        assertFalse(decide(global = global, roll = 30).triggered)
    }

    @Test
    fun `冷却期内不触发`() {
        val now = 10_000_000L
        val decision = decide(
            global = baseGlobal.copy(probability = 100),
            userText = "画一张猫",
            lastGenAtMs = now - 60_000L,
            nowMs = now,
            roll = 0
        )
        assertFalse(decision.triggered)
        assertEquals(ImageGenTriggerReason.COOLDOWN, decision.reason)
    }

    @Test
    fun `冷却期结束后恢复触发`() {
        val now = 10_000_000L
        val decision = decide(
            global = baseGlobal.copy(probability = 100),
            userText = "画一张猫",
            lastGenAtMs = now - 4 * 60_000L,
            nowMs = now,
            roll = 0
        )
        assertTrue(decision.triggered)
    }

    @Test
    fun `冷却为 0 分钟时不生效`() {
        val now = 10_000_000L
        val decision = decide(
            global = baseGlobal.copy(probability = 100, cooldownMinutes = 0),
            lastGenAtMs = now - 1L,
            nowMs = now,
            roll = 0
        )
        assertTrue(decision.triggered)
    }

    // ------------------------------------------------------------ Prompt

    @Test
    fun `提取生图标签`() {
        assertEquals("一只戴帽子的猫", ImageGenTriggerLogic.extractTaggedPrompt("好呀\n[[生图: 一只戴帽子的猫]]"))
        assertEquals("sunset", ImageGenTriggerLogic.extractTaggedPrompt("[[image: sunset]]"))
        assertNull(ImageGenTriggerLogic.extractTaggedPrompt("没有标签"))
    }

    @Test
    fun `剥离生图标签且保留正文`() {
        val stripped = ImageGenTriggerLogic.stripTags("好呀，马上画\n[[生图: 一只猫]]")
        assertFalse(stripped.contains("生图"))
        assertTrue(stripped.contains("马上画"))
    }

    @Test
    fun `全角括号画面描述变体被剥离`() {
        // BUG-1 回归用例：模型不按 [[生图: …]] 输出，而是模仿历史写成（画面：…），
        // 旧的 stripTags 只认双方括号标签，画面描述原文因此泄漏成聊天气泡。
        val stripped = ImageGenTriggerLogic.stripTags("好好好，最后一张啦（画面：男友视角自拍，动漫风少女白洲梓）")
        assertFalse(stripped.contains("画面"))
        assertTrue(stripped.contains("最后一张啦"))
    }

    @Test
    fun `半角括号与方括号书名号画面描述变体被剥离`() {
        assertFalse(ImageGenTriggerLogic.stripTags("好呀 (画面: a self portrait)").contains("画面"))
        assertFalse(ImageGenTriggerLogic.stripTags("好呀【画面：一只戴帽子的猫】").contains("画面"))
        assertFalse(ImageGenTriggerLogic.stripTags("好呀[画面：a cat in rain]").contains("画面"))
    }

    @Test
    fun `整条回复只有画面描述时判为 prompt only`() {
        val raw = "（画面：男友视角自拍，动漫风少女白洲梓，银白色长直发半遮着脸）"
        assertTrue(ImageGenTriggerLogic.isPromptOnly(raw))
        // 剥离后为空：调用方必须用占位文案兜底，绝不能回落成原文
        assertEquals("", ImageGenTriggerLogic.stripTags(raw))
    }

    @Test
    fun `心理活动括号不会被误伤`() {
        // 清洗只锚定「画面：」语义，正常括号心理活动必须原样保留
        val text = "（脸红）今天也很想你（偷偷开心）"
        assertEquals(text, ImageGenTriggerLogic.stripTags(text))
    }

    @Test
    fun `独占一行的画面描述被剥离`() {
        val stripped = ImageGenTriggerLogic.stripTags("好呀，马上画给你\n画面：一只戴帽子的猫在雨中奔跑")
        assertFalse(stripped.contains("戴帽子"))
        assertTrue(stripped.contains("马上画给你"))
    }

    @Test
    fun `系统规则包含禁止模仿画面描述的禁令`() {
        val rules = ImageGenTriggerLogic.systemRules(enabled = true, hasKeywordTrigger = false)
        assertTrue(rules.contains("[[生图:"))
        // 与「心理活动必须用圆括号」的既有规则不冲突的关键：显式禁止画面描述的非标签写法
        assertTrue(rules.contains("严禁"))
    }

    @Test
    fun `AI 标签优先于关键词与回复正文`() {
        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = "好呀\n[[生图: 赛博朋克城市夜景]]",
            userText = "画一张猫",
            matchedKeyword = "画一张",
            template = "{content}"
        )
        assertEquals("赛博朋克城市夜景", prompt)
    }

    @Test
    fun `关键词触发时使用去掉关键词后的用户文本`() {
        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = "好呀~",
            userText = "画一张穿着汉服的猫",
            matchedKeyword = "画一张",
            template = "{content}"
        )
        assertEquals("穿着汉服的猫", prompt)
    }

    @Test
    fun `无标签无关键词时回退到 AI 回复正文`() {
        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = "今天的天空很美，像打翻了水彩盘。",
            userText = "在干嘛",
            matchedKeyword = null,
            template = "{content}"
        )
        assertEquals("今天的天空很美，像打翻了水彩盘。", prompt)
    }

    @Test
    fun `模板占位符被替换`() {
        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = "",
            userText = "画一张猫",
            matchedKeyword = "画一张",
            template = "画一张：{content}，写实风格"
        )
        assertEquals("画一张：猫，写实风格", prompt)
    }

    @Test
    fun `模板无占位符时作为前缀拼接`() {
        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = "",
            userText = "画一张猫",
            matchedKeyword = "画一张",
            template = "写实风格："
        )
        assertEquals("写实风格：猫", prompt)
    }

    @Test
    fun `prompt 超长时截断`() {
        val prompt = ImageGenTriggerLogic.buildPrompt(
            aiText = "很".repeat(2000),
            userText = "",
            matchedKeyword = null,
            template = "{content}"
        )
        assertEquals(800, prompt.length)
    }

    @Test
    fun `总开关关闭时不注入系统规则`() {
        assertEquals("", ImageGenTriggerLogic.systemRules(enabled = false, hasKeywordTrigger = true))
        assertTrue(ImageGenTriggerLogic.systemRules(enabled = true, hasKeywordTrigger = false).contains("[[生图:"))
    }

    @Test
    fun `默认关键词能覆盖再生成一张这类追问`() {
        val defaults = com.yunian.ai.common.AppSettingsStore.ImageGenDefaults.DEFAULT_KEYWORDS

        // 真实踩坑：用户说「再生成一张」时，只有 "生成图片" 是匹配不上的
        assertEquals("生成一张", ImageGenTriggerLogic.matchKeyword("再生成一张", defaults))
        // AI 答应要画时也要能触发
        assertNotNull(ImageGenTriggerLogic.matchKeyword("好，帮你生成", defaults))
    }

    @Test
    fun `默认关键词不会被无关闲聊误触发`() {
        val defaults = com.yunian.ai.common.AppSettingsStore.ImageGenDefaults.DEFAULT_KEYWORDS

        assertNull(ImageGenTriggerLogic.matchKeyword("今天上班好累啊", defaults))
        assertNull(ImageGenTriggerLogic.matchKeyword("生成的内容我看过了", defaults))
    }

    // ------------------------------------------------------------ 编排

    private class FakeProvider(
        private val result: Result<List<GeneratedImage>>
    ) : ImageGenerationProvider {
        var lastPrompt: String? = null
        override suspend fun fetchImageModels(baseUrl: String, apiKey: String) =
            Result.success(ImageModelCatalog(emptyList(), emptyList()))

        override suspend fun testConnection(baseUrl: String, apiKey: String, model: String) =
            Result.success("ok")

        override suspend fun generateImage(
            baseUrl: String,
            apiKey: String,
            model: String,
            prompt: String,
            size: String,
            count: Int
        ): Result<List<GeneratedImage>> {
            lastPrompt = prompt
            return result
        }
    }

    private fun deps(
        provider: ImageGenerationProvider,
        written: MutableList<ChatMessage>,
        events: MutableList<Pair<String, Boolean>>,
        lifecycle: MutableList<String> = mutableListOf(),
        global: ImageGenGlobalConfig = baseGlobal,
        lastGenAt: Long = 0L
    ) = ImageGenDeps(
        provider = provider,
        loadGlobalConfig = { global },
        loadLastGenAt = { lastGenAt },
        saveLastGenAt = { },
        resolveMainConnection = { mainConn },
        writeMessage = { message -> written += message; 1L },
        emitMessage = { text, isError -> events += text to isError },
        onGenerationStart = { lifecycle += "start" },
        onGenerationFinish = { lifecycle += "finish" },
        randomRoll = { 0 }
    )

    @Test
    fun `触发成功后写入 IMAGE 消息并使用系统保留标签`() = runBlocking {
        val written = mutableListOf<ChatMessage>()
        val events = mutableListOf<Pair<String, Boolean>>()
        val lifecycle = mutableListOf<String>()
        val provider = FakeProvider(Result.success(listOf(GeneratedImage("/tmp/a.png", model = "flux-schnell"))))
        val coordinator = ImageGenCoordinator(7L, deps(provider, written, events, lifecycle))

        val decision = coordinator.maybeTriggerImage("画一张猫", "好呀")

        assertTrue(decision.triggered)
        assertEquals(1, written.size)
        assertEquals("[图片]", written[0].content)
        assertEquals(MessageType.IMAGE, written[0].type)
        assertEquals("/tmp/a.png", written[0].linkString)
        assertEquals(7L, written[0].companionId)
        assertFalse(written[0].isFromUser)
        // 等待动画：开始/结束成对出现
        assertEquals(listOf("start", "finish"), lifecycle)
        assertTrue(events.any { it.first == "配图已生成" && !it.second })
    }

    @Test
    fun `生图描述写入 searchContent 供后续追问复用`() = runBlocking {
        val written = mutableListOf<ChatMessage>()
        val events = mutableListOf<Pair<String, Boolean>>()
        val provider = FakeProvider(Result.success(listOf(GeneratedImage("/tmp/a.png"))))
        val coordinator = ImageGenCoordinator(7L, deps(provider, written, events))

        val decision = coordinator.maybeTriggerImage("画一张穿汉服的猫", "好呀")

        // 用户文本去掉关键词后即画面描述
        assertEquals("穿汉服的猫", decision.prompt)
        assertEquals("穿汉服的猫", written[0].searchContent)
    }

    @Test
    fun `图片消息送给模型时附带画面描述`() {
        val image = ChatMessage(
            companionId = 7L,
            content = "[图片]",
            isFromUser = false,
            type = MessageType.IMAGE,
            linkString = "/tmp/a.png",
            searchContent = "动漫风格的白洲梓，银白色长直发",
        )

        // 模型能看到画的是什么，所以「再生成一张」不会另起炉灶。
        // 注记必须用「系统注记」措辞：早期版本的「[图片]（画面：xxx）」会被模型原样
        // 照抄进回复，画面描述因此泄漏成聊天气泡（BUG-1 的诱导源），禁止回退到旧格式。
        assertEquals(
            "[图片]（系统注记：该图画面描述为 动漫风格的白洲梓，银白色长直发；" +
                "此注记仅用于你理解图片内容，禁止在回复中输出任何「画面：」或括号包裹的画面描述）",
            image.contentForModel(),
        )
        // 但 content 本身保持不变，渲染层仍走系统保留标签
        assertEquals("[图片]", image.content)
    }

    @Test
    fun `无描述或非图片消息的历史文本保持不变`() {
        val plainImage = ChatMessage(companionId = 7L, content = "[图片]", isFromUser = false, type = MessageType.IMAGE)
        val text = ChatMessage(companionId = 7L, content = "在呀", isFromUser = false, type = MessageType.TEXT)

        assertEquals("[图片]", plainImage.contentForModel())
        assertEquals("在呀", text.contentForModel())
    }

    @Test
    fun `生图失败时提示错误且不写消息`() = runBlocking {
        val written = mutableListOf<ChatMessage>()
        val events = mutableListOf<Pair<String, Boolean>>()
        val provider = FakeProvider(Result.failure(IllegalStateException("HTTP 401")))
        val coordinator = ImageGenCoordinator(7L, deps(provider, written, events))

        val decision = coordinator.maybeTriggerImage("画一张猫", "好呀")

        assertFalse(decision.triggered)
        assertTrue(written.isEmpty())
        assertTrue(events.any { it.second && it.first.contains("HTTP 401") })
    }

    @Test
    fun `未触发时不产生任何副作用`() = runBlocking {
        val written = mutableListOf<ChatMessage>()
        val events = mutableListOf<Pair<String, Boolean>>()
        val lifecycle = mutableListOf<String>()
        val provider = FakeProvider(Result.success(emptyList()))
        val coordinator = ImageGenCoordinator(
            7L,
            deps(provider, written, events, lifecycle, global = baseGlobal.copy(probability = 0))
        )

        val decision = coordinator.maybeTriggerImage("你好", "在的")

        assertFalse(decision.triggered)
        assertTrue(written.isEmpty())
        assertTrue(events.isEmpty())
        // 关键：未触发时不能点亮等待动画，否则每轮回复都会闪一下
        assertTrue(lifecycle.isEmpty())
    }

    @Test
    fun `生图失败时也会关闭等待动画`() = runBlocking {
        val written = mutableListOf<ChatMessage>()
        val events = mutableListOf<Pair<String, Boolean>>()
        val lifecycle = mutableListOf<String>()
        val provider = FakeProvider(Result.failure(IllegalStateException("HTTP 500")))
        val coordinator = ImageGenCoordinator(7L, deps(provider, written, events, lifecycle))

        coordinator.maybeTriggerImage("画一张猫", "好呀")

        assertEquals(listOf("start", "finish"), lifecycle)
    }

    @Test
    fun `多张图写入多条消息`() = runBlocking {
        val written = mutableListOf<ChatMessage>()
        val events = mutableListOf<Pair<String, Boolean>>()
        val provider = FakeProvider(
            Result.success(
                listOf(
                    GeneratedImage("/tmp/a.png"),
                    GeneratedImage("/tmp/b.png")
                )
            )
        )
        val coordinator = ImageGenCoordinator(
            7L,
            deps(provider, written, events, global = baseGlobal.copy(count = 2))
        )

        coordinator.maybeTriggerImage("画一张猫", "好呀")

        assertEquals(2, written.size)
        assertEquals("/tmp/a.png", written[0].linkString)
        assertEquals("/tmp/b.png", written[1].linkString)
    }
}
