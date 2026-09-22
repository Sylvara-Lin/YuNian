package com.yunian.ai.database

import com.yunian.ai.database.dao.ApiConfigDao
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApiConfigDaoQueryTest {
    private val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun activeConfigPrefersNewestEnabledConfiguredApi() {
        val daoSource = File(
            projectRoot,
            "core/database/src/main/java/com/yunian/ai/database/dao/ApiConfigDao.kt"
        ).readText()

        assertTrue(
            "聊天发消息必须选择最新启用、具有 apiKey 或由服务端动态供钥的 PARTNER 配置",
            daoSource.contains(
                "SELECT * FROM api_configs WHERE (apiKey IS NOT NULL AND apiKey != '' OR provider = 'PARTNER') AND isEnabled = 1 ORDER BY id DESC LIMIT 1"
            )
        )
    }
}
