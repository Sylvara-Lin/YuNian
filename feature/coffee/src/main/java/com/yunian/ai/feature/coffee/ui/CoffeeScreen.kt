package com.yunian.ai.feature.coffee.ui
import com.yunian.ai.uicommon.icon.AppIcons


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.yunian.ai.feature.coffee.data.model.OrderDetail
import com.yunian.ai.feature.coffee.data.model.ProductInfo
import com.yunian.ai.feature.coffee.data.model.ShopInfo
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

private val LuckinBlue = Color(0xFF0066CC)
private val LuckinDarkBlue = Color(0xFF003D7A)
private val LuckinLightBlue = Color(0xFFE6F0FF)
private val LuckinRed = Color(0xFFE1251B)
private val LuckinGray = Color(0xFFF5F5F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoffeeScreen(
    onBack: () -> Unit,
    onProductClick: (Long, Long) -> Unit = { _, _ -> },
    onSettingsClick: () -> Unit = {},
    onOrderQueryClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: CoffeeViewModel = viewModel(factory = CoffeeViewModel.factory(context))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    GlassPageScaffold(
        topBar = {
            GlassTopBar(
                title = "瑞幸咖啡",
                onBack = {
                    if (uiState.currentStep == OrderStep.SHOP_SELECT) {
                        onBack()
                    } else {
                        viewModel.goBack()
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onOrderQueryClick, modifier = Modifier.size(32.dp)) {
                            Icon(AppIcons.List, contentDescription = "订单查询", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
                            Icon(AppIcons.Settings, contentDescription = "瑞幸设置", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LuckinGray)
                .padding(padding)
        ) {
            when (uiState.currentStep) {
                OrderStep.SHOP_SELECT -> ShopSelectContent(viewModel, uiState)
                OrderStep.PRODUCT_SELECT -> ProductSelectContent(viewModel, uiState, onProductClick)
                OrderStep.ORDER_CONFIRM -> OrderConfirmContent(viewModel, uiState)
                OrderStep.ORDER_PREVIEW -> OrderPreviewContent(viewModel, uiState)
                OrderStep.PAYMENT -> PaymentContent(viewModel, uiState)
                OrderStep.ORDER_STATUS -> OrderStatusContent(viewModel, uiState)
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = LuckinBlue)
                            Spacer(Modifier.height(12.dp))
                            Text("加载中…", color = LuckinDarkBlue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopSelectContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    val context = LocalContext.current
    var locationMessage by remember { mutableStateOf("") }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            fetchLocation(context) { lat, lng, precise ->
                viewModel.updateLocation(lng, lat, precise)
                locationMessage = if (precise) "定位成功" else "定位成功（粗略）"
            }
        } else {
            locationMessage = "定位权限被拒绝，可手动输入经纬度"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.MapPin, contentDescription = null, tint = LuckinBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("门店定位", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        state.hasPreciseLocation -> "已定位: %.6f, %.6f".format(state.longitude, state.latitude)
                        locationMessage.isNotEmpty() -> locationMessage
                        else -> "点击下方按钮获取定位"
                    },
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (hasFine || hasCoarse) {
                            fetchLocation(context) { lat, lng, precise ->
                                viewModel.updateLocation(lng, lat, precise)
                                locationMessage = if (precise) "定位成功" else "定位成功（粗略）"
                            }
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(AppIcons.MapPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("获取当前位置")
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    label = { Text("门店名称（可选）") },
                    placeholder = { Text("如：诺布中心店") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.queryShops(state.searchQuery.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(AppIcons.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("查找门店")
                }
            }
        }

        if (state.shops.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.shops, key = { it.deptId }) { shop ->
                    ShopCard(shop = shop, hasPreciseLocation = state.hasPreciseLocation) {
                        viewModel.selectShop(shop)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopCard(shop: ShopInfo, hasPreciseLocation: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(shop.deptName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = LuckinDarkBlue)
            Spacer(Modifier.height(4.dp))
            Text(shop.address, fontSize = 13.sp, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("营业: ${shop.workTimeStart} - ${shop.workTimeEnd}", fontSize = 12.sp, color = Color.Gray)
                if (hasPreciseLocation && shop.distance > 0) {
                    Spacer(Modifier.width(12.dp))
                    Text("距离: ${shop.distance}km", fontSize = 12.sp, color = LuckinBlue)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("选择此门店", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProductSelectContent(
    viewModel: CoffeeViewModel,
    state: CoffeeUiState,
    onProductClick: (Long, Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {

        state.selectedShop?.let { shop ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.MapPin, contentDescription = null, tint = LuckinBlue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(shop.deptName, fontWeight = FontWeight.Bold, color = LuckinDarkBlue, fontSize = 14.sp)
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text("搜索商品，如：生椰拿铁") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = viewModel::searchProducts) {
                    Icon(AppIcons.Search, contentDescription = "搜索", tint = LuckinBlue)
                }
            }
        }

        if (state.products.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.products, key = { it.productId }) { product ->
                    ProductCard(
                        product = product,
                        onCustomize = { onProductClick(state.selectedShop?.deptId ?: 0L, product.productId) },
                        onAddQuick = { viewModel.addProductQuick(product) }
                    )
                }
            }
        }

        if (state.selectedProducts.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("已选商品 (${state.selectedProducts.size})", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                    Spacer(Modifier.height(8.dp))
                    state.selectedProducts.forEach { sp ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${sp.productName} x${sp.amount}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("¥${"%.2f".format(sp.estimatePrice * sp.amount)}", fontSize = 14.sp, color = LuckinRed)
                            TextButton(onClick = { viewModel.removeProduct(sp.productId) }) {
                                Icon(AppIcons.Trash2, contentDescription = "移除", tint = LuckinRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::goToOrderConfirm,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确认订单", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCard(
    product: ProductInfo,
    onCustomize: () -> Unit,
    onAddQuick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ctx = LocalContext.current
            if (product.pictureUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(product.pictureUrl)
                        .crossfade(true)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = product.productName,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(LuckinGray)
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    product.productName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = LuckinDarkBlue
                )
                product.tags?.takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), fontSize = 11.sp, color = LuckinBlue, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    if (product.initialPrice > product.estimatePrice && product.initialPrice > 0) {
                        Text(
                            "¥${"%.0f".format(product.initialPrice)} ",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                        )
                    }
                    Text("¥${"%.0f".format(product.estimatePrice)}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LuckinRed)
                    Spacer(Modifier.width(4.dp))
                    Text("到手价", fontSize = 11.sp, color = LuckinRed)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onCustomize,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text("定制温度/杯型 ›", fontSize = 12.sp, color = LuckinBlue)
                }
            }
            Button(
                onClick = onAddQuick,
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text("添加", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OrderConfirmContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        state.selectedShop?.let { shop ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("自提门店", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                    Spacer(Modifier.height(4.dp))
                    Text(shop.deptName, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(shop.address, fontSize = 13.sp, color = Color.Gray)
                    Text("营业: ${shop.workTimeStart} - ${shop.workTimeEnd}", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("商品明细", fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
                Spacer(Modifier.height(8.dp))
                var totalEstimate = 0.0
                state.selectedProducts.forEach { sp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${sp.productName} x${sp.amount}", fontSize = 14.sp)
                        val price = sp.estimatePrice * sp.amount
                        totalEstimate += price
                        Text("¥${"%.2f".format(price)}", fontSize = 14.sp, color = LuckinRed)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("预估总价", fontWeight = FontWeight.Bold)
                    Text("¥${"%.2f".format(totalEstimate)}", fontWeight = FontWeight.Bold, color = LuckinRed, fontSize = 18.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
        ) {
            Text(
                "确认后将调用 previewOrder 获取真实价格和优惠。\n最终价格以预览结果为准。",
                Modifier.padding(16.dp),
                fontSize = 12.sp,
                color = LuckinDarkBlue,
                lineHeight = 18.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.remark,
            onValueChange = viewModel::updateRemark,
            label = { Text("订单备注（可选）") },
            placeholder = { Text("如：少冰、不要搅拌") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = viewModel::previewOrder,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("预览订单（获取真实价格）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun OrderPreviewContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    val preview = state.orderPreview ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("订单预览", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LuckinDarkBlue)
                Spacer(Modifier.height(16.dp))

                PriceRow("商品总面价", preview.totalInitialPrice)

                PriceRow("优惠", -preview.privilegeMoney, color = LuckinRed)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("应付金额", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("¥${"%.2f".format(preview.discountPrice)}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = LuckinRed)
                }

                if (preview.couponCodeList.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("已使用优惠券: ${preview.couponCodeList.size} 张", fontSize = 12.sp, color = LuckinBlue)
                }

                if (preview.productInfoList.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("商品明细", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    preview.productInfoList.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${p.name} x${p.amount}${if (p.additionDesc.isNotBlank()) " (${p.additionDesc})" else ""}",
                                fontSize = 13.sp,
                                color = Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                            Text("¥${"%.2f".format(p.estimateTotalPrice)}", fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { viewModel.createOrder() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("确认下单（创建订单）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun PriceRow(label: String, price: Double, color: Color = Color.DarkGray) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = color)
        Text("¥${"%.2f".format(price)}", fontSize = 14.sp, color = color)
    }
}

@Composable
private fun PaymentContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    val order = state.createdOrder ?: return
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text("订单已创建", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = LuckinDarkBlue)
        Spacer(Modifier.height(8.dp))

        val displayOrderId = order.orderIdStr.ifBlank { order.orderId.toString() }
        Text("订单号: $displayOrderId", fontSize = 14.sp, color = Color.Gray)

        if (!order.needPay) {
            Spacer(Modifier.height(8.dp))
            Text("本单无需支付（优惠已全额抵扣）", fontSize = 13.sp, color = LuckinBlue, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))

        if (order.needPay) {
            val canLaunchWeixin = order.payOrderUrl.isNotBlank() &&
                order.payOrderUrl.startsWith("weixin://", ignoreCase = true)

            if (canLaunchWeixin) {
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(order.payOrderUrl)
                        )
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure {

                            }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("微信支付", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("点击拉起微信完成付款", fontSize = 12.sp, color = Color.Gray)
            }

            if (order.payOrderQrCodeUrl.isNotBlank()) {
                if (canLaunchWeixin) {
                    Spacer(Modifier.height(16.dp))
                    Text("— 或用另一台手机扫码 —", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                }
                Card(
                    modifier = Modifier.size(240.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(order.payOrderQrCodeUrl)
                            .crossfade(true)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = "支付二维码",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("用微信扫上方二维码", fontSize = 12.sp, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = LuckinLightBlue)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("支付完成后告诉我一声，我可以马上帮你查询订单状态和取餐码。", fontSize = 13.sp, color = LuckinDarkBlue)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = viewModel::queryCurrentOrderStatus,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("已支付，帮我查取餐码", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.navigateToStep(OrderStep.SHOP_SELECT) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("还没支付，稍后再查")
                }
            }
        }
    }
}

@Composable
private fun OrderStatusContent(viewModel: CoffeeViewModel, state: CoffeeUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        if (state.createdOrder == null) {
            OutlinedTextField(
                value = state.queryOrderId,
                onValueChange = viewModel::updateQueryOrderId,
                label = { Text("订单号") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.queryOrderStatus() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = LuckinBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("查询订单状态")
            }
            Spacer(Modifier.height(16.dp))
        }

        state.orderDetail?.let { detail ->
            OrderDetailCard(detail)
            Spacer(Modifier.height(16.dp))

            if (detail.orderStatus == OrderDetail.STATUS_UNPAID ||
                detail.orderStatus == OrderDetail.STATUS_SUCCESS ||
                detail.orderStatus == OrderDetail.STATUS_MAKING
            ) {
                OutlinedButton(
                    onClick = viewModel::cancelOrder,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LuckinRed)
                ) {
                    Text("取消订单")
                }
            }
        }
    }
}

private fun fetchLocation(
    context: Context,
    onResult: (lat: Double, lng: Double, precise: Boolean) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            onResult(39.9087, 116.3975, false)
            return
        }

        val provider = when {
            isGpsEnabled -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            val location: Location? = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                onResult(location.latitude, location.longitude, provider == LocationManager.GPS_PROVIDER)
            } else {
                val networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (networkLocation != null) {
                    onResult(networkLocation.latitude, networkLocation.longitude, false)
                } else {
                    onResult(39.9087, 116.3975, false)
                }
            }
        } else {
            onResult(39.9087, 116.3975, false)
        }
    } catch (e: SecurityException) {
        onResult(39.9087, 116.3975, false)
    } catch (e: Exception) {
        onResult(39.9087, 116.3975, false)
    }
}
