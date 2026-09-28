package com.yunian.ai.feature.coffee

import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.CoffeeOrderProvider
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginContext
import com.yunian.ai.domain.plugin.PluginServices
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object LuckinCoffeeTools {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun registerAll(provider: CoffeeOrderProvider) {
        ToolRegistry.register(QueryShopsTool(provider))
        ToolRegistry.register(SearchProductsTool(provider))
        ToolRegistry.register(PreviewOrderTool(provider))
        ToolRegistry.register(CreateOrderTool(provider))
        ToolRegistry.register(QueryOrderTool(provider))
        ToolRegistry.register(CancelOrderTool(provider))
    }

    // ── 工具工厂（Cordis 插件装配用；与 registerAll 注册同一批实现） ──
    // 嵌套工具类是 private，故由本 object 暴露构造入口给 CoffeePlugin。

    internal fun queryShops(provider: CoffeeOrderProvider): AiTool = QueryShopsTool(provider)
    internal fun searchProducts(provider: CoffeeOrderProvider): AiTool = SearchProductsTool(provider)
    internal fun previewOrder(provider: CoffeeOrderProvider): AiTool = PreviewOrderTool(provider)
    internal fun createOrder(provider: CoffeeOrderProvider): AiTool = CreateOrderTool(provider)
    internal fun queryOrder(provider: CoffeeOrderProvider): AiTool = QueryOrderTool(provider)
    internal fun cancelOrder(provider: CoffeeOrderProvider): AiTool = CancelOrderTool(provider)

    private class QueryShopsTool(private val provider: CoffeeOrderProvider) : AiTool {
        override val name = "luckin_query_shops"
        override val description = "查询附近的瑞幸咖啡门店。需要经纬度坐标。可按门店名筛选。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"longitude":{"type":"number","description":"经度"},"latitude":{"type":"number","description":"纬度"},"deptName":{"type":"string","description":"门店名筛选词（可选）"}},"required":["longitude","latitude"]}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            if (!provider.isAvailable()) return """{"error":"瑞幸 Token 未配置，请在设置中配置"}"""
            val obj = json.parseToJsonElement(argumentsJson).jsonObject
            val longitude = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val latitude = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val deptName = obj["deptName"]?.jsonPrimitive?.contentOrNull
            return provider.queryShops(longitude, latitude, deptName)
        }
    }

    private class SearchProductsTool(private val provider: CoffeeOrderProvider) : AiTool {
        override val name = "luckin_search_products"
        override val description = "在指定瑞幸门店搜索商品。返回商品名、SKU编码、到手价等。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"deptId":{"type":"integer","description":"门店ID"},"query":{"type":"string","description":"搜索关键词，如 生椰拿铁"}},"required":["deptId","query"]}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            if (!provider.isAvailable()) return """{"error":"瑞幸 Token 未配置"}"""
            val obj = json.parseToJsonElement(argumentsJson).jsonObject
            val deptId = obj["deptId"]?.jsonPrimitive?.longOrNull ?: 0L
            val query = obj["query"]?.jsonPrimitive?.contentOrNull ?: ""
            return provider.searchProducts(deptId, query)
        }
    }

    private class PreviewOrderTool(private val provider: CoffeeOrderProvider) : AiTool {
        override val name = "luckin_preview_order"
        override val description = "预览瑞幸订单，获取真实到手价、优惠金额、商品明细。下单前必先预览。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"deptId":{"type":"integer","description":"门店ID"},"productList":{"type":"array","items":{"type":"object","properties":{"amount":{"type":"integer"},"productId":{"type":"integer"},"skuCode":{"type":"string"}},"required":["amount","productId","skuCode"]}}},"required":["deptId","productList"]}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            if (!provider.isAvailable()) return """{"error":"瑞幸 Token 未配置"}"""
            val obj = json.parseToJsonElement(argumentsJson).jsonObject
            val deptId = obj["deptId"]?.jsonPrimitive?.longOrNull ?: 0L
            val productListJson = obj["productList"]?.toString() ?: "[]"
            return provider.previewOrder(deptId, productListJson)
        }
    }

    private class CreateOrderTool(private val provider: CoffeeOrderProvider) : AiTool {
        override val name = "luckin_create_order"
        override val description = "创建瑞幸订单并生成支付二维码。注意：此操作会产生真实订单，请先预览并经用户确认后再调用。返回支付链接和二维码URL。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"deptId":{"type":"integer","description":"门店ID"},"productList":{"type":"array","items":{"type":"object","properties":{"amount":{"type":"integer"},"productId":{"type":"integer"},"skuCode":{"type":"string"}},"required":["amount","productId","skuCode"]}},"longitude":{"type":"number","description":"经度"},"latitude":{"type":"number","description":"纬度"},"remark":{"type":"string","description":"订单备注（可选）"}},"required":["deptId","productList","longitude","latitude"]}
        """.trimIndent()

        override val requiresConfirmation: Boolean get() = true

        override fun summarizeArguments(argumentsJson: String): String {
            val obj = runCatching { json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            val deptId = obj?.get("deptId")?.jsonPrimitive?.longOrNull
            val count = obj?.get("productList")?.let { el ->
                runCatching { (el as kotlinx.serialization.json.JsonArray).size }.getOrDefault(0)
            } ?: 0
            return "瑞幸下单 · 门店 $deptId · 商品 $count 件（将生成支付二维码）"
        }

        override suspend fun execute(argumentsJson: String): String {
            if (!provider.isAvailable()) return """{"error":"瑞幸 Token 未配置"}"""
            val obj = json.parseToJsonElement(argumentsJson).jsonObject
            val deptId = obj["deptId"]?.jsonPrimitive?.longOrNull ?: 0L
            val productListJson = obj["productList"]?.toString() ?: "[]"
            val longitude = obj["longitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val latitude = obj["latitude"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val remark = obj["remark"]?.jsonPrimitive?.contentOrNull
            return provider.createOrder(deptId, productListJson, longitude, latitude, remark)
        }
    }

    private class QueryOrderTool(private val provider: CoffeeOrderProvider) : AiTool {
        override val name = "luckin_query_order"
        override val description = "查询瑞幸订单详情，包括状态、取餐码、门店、商品明细、支付金额。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"orderId":{"type":"string","description":"订单号"}},"required":["orderId"]}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            if (!provider.isAvailable()) return """{"error":"瑞幸 Token 未配置"}"""
            val obj = json.parseToJsonElement(argumentsJson).jsonObject
            val orderId = obj["orderId"]?.jsonPrimitive?.contentOrNull ?: ""
            return provider.queryOrderDetail(orderId)
        }
    }

    private class CancelOrderTool(private val provider: CoffeeOrderProvider) : AiTool {
        override val name = "luckin_cancel_order"
        override val description = "取消瑞幸订单。仅未支付或制作中的订单可取消。"
        override val parametersJsonSchema = """
            {"type":"object","properties":{"orderId":{"type":"string","description":"订单号"}},"required":["orderId"]}
        """.trimIndent()

        override suspend fun execute(argumentsJson: String): String {
            if (!provider.isAvailable()) return """{"error":"瑞幸 Token 未配置"}"""
            val obj = json.parseToJsonElement(argumentsJson).jsonObject
            val orderId = obj["orderId"]?.jsonPrimitive?.contentOrNull ?: ""
            return provider.cancelOrder(orderId)
        }
    }
}

/**
 * 瑞幸咖啡工具插件（Cordis 双层插件模板 · 代码插件，kind = TOOL）。
 *
 * 与 [LuckinCoffeeTools.registerAll] 的差异：逐工具注册 [PluginContext.effect] 注销副作用，
 * 插件卸载时工具随之清理（「卸载不留鸡毛」语义）。
 * 装配契约：requires = [PluginServices.TOOLS]（宿主预置 ToolRegistry）。
 */
class CoffeePlugin(
    private val provider: CoffeeOrderProvider,
) : LianYuPlugin {

    companion object {
        const val ID = "coffee.luckin"
    }

    override val id: String = ID
    override val name: String = "瑞幸咖啡"
    override val requires: Set<String> = setOf(PluginServices.TOOLS)
    override val configSchema: String? = null

    override fun setup(ctx: PluginContext) {
        val registry = ctx.inject<ToolRegistry>(PluginServices.TOOLS)
        val tools = listOf(
            LuckinCoffeeTools.queryShops(provider),
            LuckinCoffeeTools.searchProducts(provider),
            LuckinCoffeeTools.previewOrder(provider),
            LuckinCoffeeTools.createOrder(provider),
            LuckinCoffeeTools.queryOrder(provider),
            LuckinCoffeeTools.cancelOrder(provider),
        )
        tools.forEach { registry.register(it) }
        tools.forEach { tool ->
            ctx.effect({ registry.unregister(tool.name) }, "unregister:${tool.name}")
        }
    }
}
