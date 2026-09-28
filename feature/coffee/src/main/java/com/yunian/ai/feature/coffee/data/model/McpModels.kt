package com.yunian.ai.feature.coffee.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QueryShopArgs(
    val longitude: Double,
    val latitude: Double,
    @SerialName("deptName") val deptName: String? = null
)

@Serializable
internal data class SearchProductArgs(
    @SerialName("deptId") val deptId: Long,
    val query: String
)

@Serializable
internal data class ProductDetailArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productId") val productId: Long
)

@Serializable
internal data class SwitchProductArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productId") val productId: Long,
    @SerialName("skuCode") val skuCode: String,
    @SerialName("attrOperationParam") val attrOperationParam: AttrOperationParam,
    val amount: Int
)

@Serializable
internal data class AttrOperationParam(
    @SerialName("attributeId") val attributeId: Long,
    @SerialName("subAttr") val subAttr: SubAttr
)

@Serializable
internal data class SubAttr(
    @SerialName("attributeId") val attributeId: Long,
    val operation: Int
)

@Serializable
data class ProductListItem(
    val amount: Int,
    @SerialName("productId") val productId: Long,
    @SerialName("skuCode") val skuCode: String
)

@Serializable
internal data class PreviewOrderArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productList") val productList: List<ProductListItem>
)

@Serializable
internal data class CreateOrderArgs(
    @SerialName("deptId") val deptId: Long,
    @SerialName("productList") val productList: List<ProductListItem>,
    val longitude: Double,
    val latitude: Double,
    @SerialName("couponCodeList") val couponCodeList: List<String>? = null,
    val remark: String? = null
)

@Serializable
internal data class OrderIdArgs(
    @SerialName("orderId") val orderId: String
)

@Serializable
data class ShopInfo(
    @SerialName("deptId") val deptId: Long = 0,
    @SerialName("deptName") val deptName: String = "",
    val address: String = "",
    @SerialName("deptTags") val deptTags: List<String> = emptyList(),
    val longitude: Double = 0.0,
    val latitude: Double = 0.0,
    @SerialName("workTimeStart") val workTimeStart: String = "",
    @SerialName("workTimeEnd") val workTimeEnd: String = "",
    val distance: Double = 0.0,
    val number: String = ""
)

@Serializable
data class ProductSubAttr(
    @SerialName("attributeId") val attributeId: Long = 0,
    @SerialName("attributeName") val attributeName: String = "",

    val selected: Boolean? = null,

    val price: Double = 0.0,

    @SerialName("canSelected") val canSelected: Int? = null
)

@Serializable
data class ProductAttrGroup(
    @SerialName("attributeId") val attributeId: Long = 0,
    @SerialName("attributeName") val attributeName: String = "",
    @SerialName("productSubAttrs") val productSubAttrs: List<ProductSubAttr> = emptyList()
)

@Serializable
data class ProductInfo(
    @SerialName("productId") val productId: Long = 0,
    @SerialName("productName") val productName: String = "",
    @SerialName("skuCode") val skuCode: String = "",
    @SerialName("pictureUrl") val pictureUrl: String = "",
    @SerialName("productAttrs") val productAttrs: List<ProductAttrGroup> = emptyList(),
    val tags: List<String>? = null,
    @SerialName("initialPrice") val initialPrice: Double = 0.0,
    @SerialName("estimatePrice") val estimatePrice: Double = 0.0
)

@Serializable
data class OrderProduct(
    @SerialName("productId") val productId: Long = 0,
    @SerialName("skuCode") val skuCode: String = "",
    val name: String = "",
    val amount: Int = 0,
    @SerialName("additionDesc") val additionDesc: String = "",
    @SerialName("bigPicUrl") val bigPicUrl: String? = null,
    @SerialName("breviaryPicUrl") val breviaryPicUrl: String? = null,
    @SerialName("initPrice") val initPrice: Double = 0.0,
    @SerialName("estimatePrice") val estimatePrice: Double = 0.0,
    @SerialName("estimateTotalPrice") val estimateTotalPrice: Double = 0.0
)

@Serializable
data class OrderCommodity(
    @SerialName("commodityId") val commodityId: Long = 0,
    @SerialName("commodityCode") val commodityCode: String = "",
    @SerialName("commodityName") val commodityName: String = "",
    @SerialName("payableMoney") val payableMoney: Double = 0.0,
    @SerialName("payMoney") val payMoney: Double = 0.0
)

@Serializable
data class TakeMealCodeInfo(
    val code: String = "",
    @SerialName("takeOrderId") val takeOrderId: String = ""
)

@Serializable
data class DispatchInfo(
    @SerialName("dispatcherName") val dispatcherName: String = "",
    @SerialName("dispatcherMobile") val dispatcherMobile: String = "",
    @SerialName("dispatchAboutTime") val dispatchAboutTime: String = "",
    @SerialName("destinationDistance") val destinationDistance: Double = 0.0
)

@Serializable
data class OrderPreview(
    @SerialName("aboutTime") val aboutTime: Long = 0,
    @SerialName("discountPrice") val discountPrice: Double = 0.0,
    @SerialName("shopInfo") val shopInfo: ShopInfo? = null,
    @SerialName("productInfoList") val productInfoList: List<OrderProduct> = emptyList(),
    @SerialName("couponCodeList") val couponCodeList: List<String> = emptyList(),
    @SerialName("orderGranularCommodityList") val orderGranularCommodityList: List<OrderCommodity> = emptyList(),
    @SerialName("expressExpectTime") val expressExpectTime: Long? = null,
    @SerialName("privilegeMoney") val privilegeMoney: Double = 0.0,
    @SerialName("totalInitialPrice") val totalInitialPrice: Double = 0.0
)

@Serializable
data class OrderCreated(
    @SerialName("orderId") val orderId: Long = 0,
    @SerialName("orderIdStr") val orderIdStr: String = "",
    @SerialName("payOrderUrl") val payOrderUrl: String = "",
    @SerialName("payOrderQrCodeUrl") val payOrderQrCodeUrl: String = "",
    @SerialName("discountPrice") val discountPrice: Double = 0.0,
    @SerialName("needPay") val needPay: Boolean = true,
    @SerialName("tradeNo") val tradeNo: String? = null,
    val description: String? = null,
    @SerialName("businessNotifyUrl") val businessNotifyUrl: String? = null,
    @SerialName("subMchid") val subMchid: String? = null
)

@Serializable
data class OrderDetail(
    @SerialName("orderId") val orderId: String = "",
    @SerialName("orderStatus") val orderStatus: Int = 0,
    @SerialName("orderStatusName") val orderStatusName: String = "",
    @SerialName("aboutTime") val aboutTime: Long = 0,
    @SerialName("takeMealTime") val takeMealTime: String = "",
    @SerialName("takeMealCodeInfo") val takeMealCodeInfo: TakeMealCodeInfo? = null,
    @SerialName("shopInfo") val shopInfo: ShopInfo? = null,
    @SerialName("productInfoList") val productInfoList: List<OrderProduct>? = null,
    @SerialName("orderPayAmount") val orderPayAmount: Double = 0.0,
    @SerialName("dispatchInfo") val dispatchInfo: DispatchInfo? = null,
    @SerialName("orderCommodityList") val orderCommodityList: List<OrderCommodity> = emptyList(),
    @SerialName("orderType") val orderType: String = "",
    @SerialName("customerParams") val customerParams: String? = null
) {
    companion object {

        const val STATUS_UNPAID = 10
        const val STATUS_SUCCESS = 20
        const val STATUS_MAKING = 30
        const val STATUS_WAITING = 60
        const val STATUS_DONE = 80
        const val STATUS_CANCELED = 100
    }
}

@Serializable
data class OrderHistoryEntry(
    @SerialName("orderIdStr") val orderIdStr: String,
    @SerialName("deptName") val deptName: String = "",
    @SerialName("discountPrice") val discountPrice: Double = 0.0,
    @SerialName("createdAt") val createdAt: Long
)

data class SelectedProduct(
    val productId: Long,
    val productName: String,
    val pictureUrl: String,
    val skuCode: String,
    val amount: Int,
    val estimatePrice: Double
)
