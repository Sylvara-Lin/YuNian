package com.yunian.ai.domain

interface CoffeeOrderProvider {

    suspend fun isAvailable(): Boolean

    suspend fun queryShops(longitude: Double, latitude: Double, deptName: String?): String

    suspend fun searchProducts(deptId: Long, query: String): String

    suspend fun previewOrder(deptId: Long, productListJson: String): String

    suspend fun createOrder(
        deptId: Long,
        productListJson: String,
        longitude: Double,
        latitude: Double,
        remark: String?
    ): String

    suspend fun queryOrderDetail(orderId: String): String

    suspend fun cancelOrder(orderId: String): String
}
