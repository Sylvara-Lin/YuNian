package com.yunian.ai.feature.groupchat

import com.yunian.ai.common.text.DedupGuard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 群聊查重归一化「委托后与旧实现逐字节等价」的回归保护。
 *
 * 旧实现（内联在 GroupChatViewModel.normalizeForDedup）如下，本测试用同一份正则做对照，
 * 确保委托 [DedupGuard.normalize] 后行为不变。
 */
class GroupDedupUnchangedTest {

    private fun legacyNormalize(text: String): String {
        return text
            .replace(Regex("[@\\s，。！？!?,.～~…、:：;；\"'「」『』()（）\\[\\]【】]"), "")
            .lowercase()
            .take(40)
    }

    @Test
    fun `委托实现与旧实现逐字节等价`() {
        val samples = listOf(
            "",
            "   ",
            "你好，世界！",
            "@小明 你看这个～",
            "【重要】今天天气不错哦",
            "（笑）哈哈哈哈哈",
            "Hello, World!!!",
            "「引号」『内层』(圆括号)[方括号]",
            "混合：中文 English 123 ，。！？",
            "字".repeat(60),
            "……～～～",
        )
        for (sample in samples) {
            assertEquals("mismatch for: $sample", legacyNormalize(sample), DedupGuard.normalize(sample))
        }
    }
}
