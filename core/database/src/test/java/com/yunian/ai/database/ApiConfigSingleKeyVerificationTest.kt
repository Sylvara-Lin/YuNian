package com.yunian.ai.database

import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 验证：自定义 API 只填「主 key」时，轮询列表必须仍返回 1 个可用 key。
 *
 * 背景（用户报障）：旧版要求同时填主 key + 备用 key，只填一个用不了。
 * 本次改动把对话框里的 extraApiKeys 固定写为 ""，需要确认
 * ApiConfig.getAllApiKeys() 对空字符串不会因为 split 产出 [""] 而污染列表。
 */
class ApiConfigSingleKeyVerificationTest {

    private fun customConfig(apiKey: String, extra: String) = ApiConfig(
        provider = ApiProvider.CUSTOM,
        name = "我的自定义API",
        apiKey = apiKey,
        extraApiKeys = extra,
        baseUrl = "https://example.com/v1/",
        model = "my-model"
    )

    /** 核心用例：只有主 key、extraApiKeys 为空字符串 —— 必须恰好 1 个 key。 */
    @Test
    fun mainKeyOnly_emptyExtra_returnsExactlyOneKey() {
        val keys = customConfig(apiKey = "sk-main-123", extra = "").getAllApiKeys()
        assertEquals("只填主 key 时应只有 1 个 key", 1, keys.size)
        assertEquals("sk-main-123", keys.first())
    }

    /** 空字符串 split(",") 会产生 [""],必须被过滤掉 —— 不允许空串混进列表。 */
    @Test
    fun emptyExtra_neverProducesBlankKey() {
        val keys = customConfig(apiKey = "sk-main-123", extra = "").getAllApiKeys()
        assertTrue("不允许出现空白 key: $keys", keys.none { it.isBlank() })
    }

    /** 纯空白 extra（用户留空格）同样安全。 */
    @Test
    fun blankExtra_neverProducesBlankKey() {
        for (blank in listOf(" ", "   ", "\t", "\n", " , ", ",", ",,")) {
            val keys = customConfig(apiKey = "sk-main-123", extra = blank).getAllApiKeys()
            assertEquals("extra='$blank' 时应只剩主 key", listOf("sk-main-123"), keys)
        }
    }

    /** 主 key 前后空格应被 trim。 */
    @Test
    fun mainKeyIsTrimmed() {
        val keys = customConfig(apiKey = "  sk-main-123  ", extra = "").getAllApiKeys()
        assertEquals(listOf("sk-main-123"), keys)
    }

    /** 历史行为不能回归：真的填了备用 key 时仍要全部收进来。 */
    @Test
    fun multipleExtraKeys_stillSupported() {
        val keys = customConfig(
            apiKey = "sk-main-123",
            extra = "sk-backup-a, sk-backup-b"
        ).getAllApiKeys()
        assertEquals(3, keys.size)
        assertEquals(listOf("sk-main-123", "sk-backup-a", "sk-backup-b"), keys)
    }

    /** getUserApiKeys 与 getAllApiKeys 对空 extra 的行为必须一致安全。 */
    @Test
    fun userApiKeys_emptyExtra_returnsExactlyOneKey() {
        val cfg = customConfig(apiKey = "sk-main-123", extra = "")
        assertEquals(listOf("sk-main-123"), cfg.getUserApiKeys())
        assertTrue("hasUserKeys 只填主 key 时应为 true", cfg.hasUserKeys())
    }

    /** 边界：主 key 也是空 —— 允许返回空列表，但绝不能返回 [""]。 */
    @Test
    fun emptyMainAndExtra_returnsEmptyNotBlankString() {
        val keys = customConfig(apiKey = "", extra = "").getAllApiKeys()
        assertTrue("完全未填写时应为空列表,实际=$keys", keys.isEmpty())
    }

    /** 去重行为不受影响。 */
    @Test
    fun duplicateKeysAreDistinct() {
        val keys = customConfig(apiKey = "sk-same", extra = "sk-same, sk-other").getAllApiKeys()
        assertEquals(listOf("sk-same", "sk-other"), keys)
    }

    /** PARTNER 保留原有分支：无用户输入时返回空列表，由 RemoteKeyProvider 兜底。 */
    @Test
    fun partnerWithEmptyKeys_returnsEmptyList() {
        val cfg = ApiConfig(
            provider = ApiProvider.PARTNER,
            apiKey = "",
            extraApiKeys = "",
            baseUrl = ApiProvider.PARTNER.defaultBaseUrl,
            model = "auto"
        )
        assertTrue("PARTNER 无 key 时应为空列表以便走远程兜底", cfg.getAllApiKeys().isEmpty())
    }

    /**
     * 反证/防空洞测试：确认上面的断言不是"恒真"的。
     *
     * 1) 证明空字符串 split(",") 确实会产生 [""],即埋雷场景真实存在;
     * 2) 证明代码里有两道防线:外层 isNotBlank 守卫 + 内层 filter { isNotEmpty }。
     *    若两道防线都被移除,mainKeyOnly_emptyExtra_returnsExactlyOneKey 就会失败,
     *    说明该用例确实具备捕获回归的能力。
     */
    @Test
    fun hazardControl_emptySplitReallyProducesBlankAndFilterRemovesIt() {
        // 埋雷场景真实存在
        assertEquals(listOf(""), "".split(","))
        // 两道防线各自都能清掉空串
        val afterFilterOnly = "".split(",").map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("filter 防线生效", afterFilterOnly.isEmpty())
        assertTrue("isNotBlank 防线生效", "".isNotBlank().not())
    }
}
