package com.yunian.ai.feature.coffee.domain

import com.yunian.ai.feature.coffee.data.LuckinMcpClient
import com.yunian.ai.feature.coffee.data.LuckinTokenStore
import com.yunian.ai.feature.coffee.data.McpException
import com.yunian.ai.feature.coffee.data.model.OrderCreated
import com.yunian.ai.feature.coffee.data.model.OrderDetail
import com.yunian.ai.feature.coffee.data.model.OrderPreview
import com.yunian.ai.feature.coffee.data.model.ProductInfo
import com.yunian.ai.feature.coffee.data.model.ProductListItem
import com.yunian.ai.feature.coffee.data.model.ShopInfo
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class CoffeeOrderRepository(
    private val client: LuckinMcpClient,
    private val tokenStore: LuckinTokenStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private suspend fun requireToken(): String {
        val token = tokenStore.token.first()
        if (token.isBlank()) {
            throw IllegalStateException("请先配置瑞幸 MCP Token")
        }
        return token
    }

    private inline fun <reified T> JsonElement.decodeAsList(): List<T> =
        json.decodeFromJsonElement(this)

    suspend fun queryShopList(
        longitude: Double,
        latitude: Double,
        deptName: String? = null
    ): List<ShopInfo> {
        val token = requireToken()
        val args = buildJsonObject {
            put("longitude", longitude)
            put("latitude", latitude)
            if (!deptName.isNullOrBlank()) {
                put("deptName", deptName)
            }
        }
        val data = client.callTool(token, "queryShopList", args)

        return data.decodeAsList()
    }

    suspend fun searchProduct(deptId: Long, query: String): List<ProductInfo> {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("query", query)
        }
        val data = client.callTool(token, "searchProductForMcp", args)

        return data.decodeAsList()
    }

    suspend fun queryProductDetail(deptId: Long, productId: Long): ProductInfo {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("productId", productId)
        }
        val data = client.callTool(token, "queryProductDetailInfo", args)

        return json.decodeFromJsonElement(data)
    }

    suspend fun switchProduct(
        deptId: Long,
        productId: Long,
        skuCode: String,
        attributeId: Long,
        subAttributeId: Long,
        operation: Int = 3,
        amount: Int
    ): ProductInfo {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("productId", productId)
            put("skuCode", skuCode)
            put("amount", amount)
            putJsonObject("attrOperationParam") {
                put("attributeId", attributeId)
                putJsonObject("subAttr") {
                    put("attributeId", subAttributeId)
                    put("operation", operation)
                }
            }
        }
        val data = client.callTool(token, "switchProduct", args)

        return json.decodeFromJsonElement(data)
    }

    suspend fun previewOrder(
        deptId: Long,
        productList: List<ProductListItem>
    ): OrderPreview {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            putJsonArray("productList") {
                productList.forEach { item ->
                    add(buildJsonObject {
                        put("amount", item.amount)
                        put("productId", item.productId)
                        put("skuCode", item.skuCode)
                    })
                }
            }
        }
        val data = client.callTool(token, "previewOrder", args)
        return json.decodeFromJsonElement(data)
    }

    suspend fun createOrder(
        deptId: Long,
        productList: List<ProductListItem>,
        longitude: Double,
        latitude: Double,
        couponCodeList: List<String>? = null,
        remark: String? = null
    ): OrderCreated {
        val token = requireToken()
        val args = buildJsonObject {
            put("deptId", deptId)
            put("longitude", longitude)
            put("latitude", latitude)
            putJsonArray("productList") {
                productList.forEach { item ->
                    add(buildJsonObject {
                        put("amount", item.amount)
                        put("productId", item.productId)
                        put("skuCode", item.skuCode)
                    })
                }
            }
            if (!couponCodeList.isNullOrEmpty()) {
                putJsonArray("couponCodeList") {
                    couponCodeList.forEach { add(JsonPrimitive(it)) }
                }
            }
            if (!remark.isNullOrBlank()) {
                put("remark", remark)
            }
        }
        val data = client.callTool(token, "createOrder", args)
        return json.decodeFromJsonElement(data)
    }

    suspend fun queryOrderDetail(orderId: String): OrderDetail {
        val token = requireToken()
        val args = buildJsonObject {
            put("orderId", orderId)
        }
        val data = client.callTool(token, "queryOrderDetailInfo", args)
        return json.decodeFromJsonElement(data)
    }

    suspend fun cancelOrder(orderId: String): Boolean {
        val token = requireToken()
        val args = buildJsonObject {
            put("orderId", orderId)
        }
        return try {
            val data = client.callTool(token, "cancelOrder", args)

            when (data) {
                is JsonPrimitive -> data.content.toBooleanStrictOrNull() ?: false
                else -> data.toString().trim('"').toBooleanStrictOrNull() ?: false
            }
        } catch (e: McpException) {
            false
        }
    }
}
