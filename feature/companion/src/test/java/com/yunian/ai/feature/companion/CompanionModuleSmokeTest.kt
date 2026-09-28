package com.yunian.ai.feature.companion

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 占位冒烟测试（非真实业务断言，已在报告向 team-lead 说明）：
 *
 * feature/companion 目前共 5 个源文件，均为 Context / Compose / ViewModel 依赖：
 *  - CompanionProviderImpl（需 android.content.Context + AppDatabase）
 *  - CreateCompanionViewModel / CompanionListScreen / ContactsScreen / CreateCompanionScreen（Compose + ViewModel）
 * 模块内不存在可脱离 Android 运行时直测的纯逻辑，因此暂以本测试验证
 * `src/test` 源集与 `testImplementation(libs.junit)` 已正确接通。
 *
 * 后续若从 CreateCompanionScreen 抽出纯校验逻辑（如角色卡字段校验），
 * 应以真实断言替换本测试。
 */
class CompanionModuleSmokeTest {

    @Test
    fun `test source set is wired and runs on JVM`() {
        assertTrue("placeholder smoke test", true)
    }
}
