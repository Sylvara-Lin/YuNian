package com.yunian.ai.feature.chat.ui.viewmodel

internal object ChatToolIntent {

    private val keywords = listOf(

        "记忆", "记得", "记住", "记下", "回忆", "想起", "以前", "之前", "偏好", "喜欢什么",
        "记录", "存档", "备注", "备忘", "纪念日", "生日",

        "咖啡", "瑞幸", "luckin", "拿铁", "美式", "生椰", "门店", "下单", "点单", "订单",
        "取餐", "取消订单", "支付", "价格", "来一杯", "外卖", "自提", "优惠",

        "提醒", "定时", "几点", "每天", "每日", "每周", "每月", "明天", "明早", "今晚",
        "闹钟", "待办", "别忘了", "自动化", "安排", "计划", "设定", "设置", "到点", "准时",
        "打卡", "喝水", "吃药", "起床", "睡觉", "吃饭", "健身", "运动", "学习", "读书",
        "周期", "重复", "例行",

        "工作流", "巡检", "吃醋", "抽查", "流程", "节点", "执行", "巡查", "检查",
        "监控", "监督", "跟踪", "跟进", "问候", "早安", "晚安", "守护", "看护", "盯着", "看着",

        "搜索", "查一下", "查查", "最新", "新闻", "实时", "现在", "当前", "网上",
        "百度", "谷歌", "必应", "Brave", "search", "lookup", "google",

        "取消", "删除", "停用", "列出", "跑一下", "触发"
    )

    fun hasKeywordHit(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.lowercase()
        return keywords.any { keyword -> normalized.contains(keyword) }
    }

    fun shouldEnableTools(content: String, latestUserText: String?): Boolean {
        val text = content.ifBlank { latestUserText.orEmpty() }
        if (text.isBlank()) return false
        return hasKeywordHit(text)
    }
}
