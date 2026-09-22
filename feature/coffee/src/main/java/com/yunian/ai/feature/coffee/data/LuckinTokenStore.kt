package com.yunian.ai.feature.coffee.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yunian.ai.feature.coffee.data.model.OrderCreated
import com.yunian.ai.feature.coffee.data.model.OrderHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.coffeeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "luckin_coffee_prefs"
)

class LuckinTokenStore(private val context: Context) {

    private val tokenKey = stringPreferencesKey("luckin_mcp_token")
    private val tokenSaveTimeKey = stringPreferencesKey("luckin_mcp_token_save_time")
    private val orderHistoryKey = stringPreferencesKey("luckin_order_history")

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val token: Flow<String> = context.coffeeDataStore.data.map { it[tokenKey] ?: "" }

    private val tokenSaveTime: Flow<Long> = context.coffeeDataStore.data.map {
        it[tokenSaveTimeKey]?.toLongOrNull() ?: 0L
    }

    val orderHistory: Flow<List<OrderHistoryEntry>> = context.coffeeDataStore.data.map { prefs ->
        prefs[orderHistoryKey]?.let(::decodeHistory) ?: emptyList()
    }

    suspend fun saveToken(token: String) {
        context.coffeeDataStore.edit { prefs ->
            prefs[tokenKey] = token.trim()
            prefs[tokenSaveTimeKey] = System.currentTimeMillis().toString()
        }
    }

    suspend fun clearToken() {
        context.coffeeDataStore.edit {
            it.remove(tokenKey)
            it.remove(tokenSaveTimeKey)
        }
    }

    suspend fun isTokenLikelyExpired(): Boolean {
        val saveTime = tokenSaveTime.first()
        if (saveTime == 0L) return true
        val elapsed = System.currentTimeMillis() - saveTime
        return elapsed > 29L * 24 * 60 * 60 * 1000
    }

    suspend fun tokenSavedDaysAgo(): Int {
        val saveTime = tokenSaveTime.first()
        if (saveTime == 0L) return 0
        val elapsed = System.currentTimeMillis() - saveTime
        return (elapsed / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
    }

    suspend fun addOrderHistory(order: OrderCreated, deptName: String) {
        if (order.orderIdStr.isBlank() && order.orderId == 0L) return
        context.coffeeDataStore.edit { prefs ->
            val current = prefs[orderHistoryKey]?.let(::decodeHistory) ?: emptyList()
            val entry = OrderHistoryEntry(
                orderIdStr = order.orderIdStr.ifBlank { order.orderId.toString() },
                deptName = deptName,
                discountPrice = order.discountPrice,
                createdAt = System.currentTimeMillis()
            )
            val updated = (listOf(entry) + current).take(20)
            prefs[orderHistoryKey] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(OrderHistoryEntry.serializer()),
                updated
            )
        }
    }

    suspend fun clearOrderHistory() {
        context.coffeeDataStore.edit { it.remove(orderHistoryKey) }
    }

    suspend fun snapshotOrderHistory(): List<OrderHistoryEntry> = orderHistory.first()

    private fun decodeHistory(raw: String): List<OrderHistoryEntry> =
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(OrderHistoryEntry.serializer()),
                raw
            )
        }.getOrElse { emptyList() }
}
