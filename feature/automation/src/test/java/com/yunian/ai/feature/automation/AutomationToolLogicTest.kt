package com.yunian.ai.feature.automation

import com.yunian.ai.feature.automation.data.Automation
import com.yunian.ai.feature.automation.data.AutomationType
import com.yunian.ai.feature.automation.data.WorkflowNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationToolLogicTest {

    @Test
    fun parseDailyCreateParams() {
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"喝水","companionId":1,"type":"daily","hour":8,"minute":0,"message":"到点啦"}"""
        )
        assertNotNull(params)
        assertEquals("喝水", params!!.title)
        assertEquals(AutomationType.DAILY, params.type)
        assertEquals(8, params.hourOfDay)
        assertEquals(0, params.minuteOfHour)
    }

    @Test
    fun parseOnceCreateParamsWithTriggerAt() {
        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"开会","companionId":2,"type":"once","triggerAt":1700000000000,"message":"去开会"}"""
        )
        assertNotNull(params)
        assertEquals(AutomationType.ONCE, params!!.type)
        assertEquals(1_700_000_000_000L, params.triggerAtMillis)
    }

    @Test
    fun parseInvalidReturnsNull() {
        assertNull(AutomationToolLogic.parseCreateParams("""{"title":123}"""))
    }

    @Test
    fun weeklyMapsDayOfWeek() {

        val params = AutomationToolLogic.parseCreateParams(
            """{"title":"健身","companionId":1,"type":"weekly","dayOfWeek":1,"hour":19,"minute":30}"""
        )
        assertNotNull(params)
        assertEquals(AutomationType.WEEKLY, params!!.type)

        assertEquals(2, params.dayOfWeekCalendar)
    }

    @Test
    fun parseWorkflowParams() {
        val args = """
            {
              "title":"吃醋巡检","companionId":1,"type":"daily","hour":20,"minute":0,
              "description":"定时巡检小监护有没有冷落乖乖",
              "nodes":[
                {"id":"start","type":"start","title":"开始"},
                {"id":"gen","type":"ai_generate","title":"生成吃醋文案","prompt":"用撒娇吃醋语气写一句提醒用户不要冷落你的话，30字以内","outputVar":"msg"},
                {"id":"end","type":"end","title":"结束"}
              ],
              "edges":[
                {"id":"e1","source":"start","target":"gen"},
                {"id":"e2","source":"gen","target":"end"}
              ]
            }
        """.trimIndent()
        val p = AutomationToolLogic.parseCreateWorkflowParams(args)
        assertNotNull(p)
        assertEquals("吃醋巡检", p!!.title)
        assertEquals(AutomationType.DAILY, p.type)
        assertEquals(3, p.nodes.size)
        assertEquals(2, p.edges.size)
        assertEquals(WorkflowNodeType.AI_GENERATE, p.nodes[1].type)
        assertEquals("msg", p.nodes[1].outputVar)
    }

    @Test
    fun parseWorkflowRequiresCompanionId() {
        assertNull(AutomationToolLogic.parseCreateWorkflowParams("""{"title":"x","type":"daily","nodes":[],"edges":[]}"""))
    }

    @Test
    fun matchByTitleUniqueHit() {
        val list = listOf(
            automation("a", "喝水"),
            automation("b", "健身"),
            automation("c", "健身")
        )
        assertEquals(listOf(list[0]), AutomationToolLogic.matchByTitle(list, "喝水"))
        assertEquals(2, AutomationToolLogic.matchByTitle(list, "健身").size)
        assertEquals(0, AutomationToolLogic.matchByTitle(list, "不存在").size)
    }

    @Test
    fun workflowAutomationHasStatsDefaults() {
        val a = Automation(
            id = "1", title = "t", companionId = 1L, type = AutomationType.ONCE,
            triggerAtMillis = 1L, hourOfDay = 0, minuteOfHour = 0,
            isWorkflow = true, nodes = emptyList(), edges = emptyList()
        )
        assertTrue(a.isWorkflow)
        assertEquals(0, a.stats.fireCount)
    }

    private fun automation(id: String, title: String) = Automation(
        id = id, title = title, companionId = 1L, type = AutomationType.ONCE,
        triggerAtMillis = 1L, hourOfDay = 0, minuteOfHour = 0, message = "m"
    )
}
