package com.yunian.ai.wechat

import android.content.Context
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.wechat.WeChatFailureReason
import com.yunian.ai.domain.wechat.WeChatIdentityMapPort
import com.yunian.ai.domain.wechat.WeChatUserMapping
import com.yunian.ai.feature.wechat.data.WeChatTokenStore
import com.yunian.ai.feature.wechat.data.WeChatUserMappingManager
import com.yunian.ai.feature.wechat.service.WeChatServiceLocator

class WeChatIdentityMapPortImpl(
    private val appContext: Context,
) : WeChatIdentityMapPort {

    private val tokenStore: WeChatTokenStore
        get() = WeChatServiceLocator.tokenStore(appContext)

    private val companionRepository: CompanionRepository
        get() = ServiceRegistry.getOrThrow(CompanionRepository::class.java)

    private val manager: WeChatUserMappingManager by lazy {
        WeChatUserMappingManager(tokenStore, companionRepository)
    }

    override suspend fun resolveCompanionId(wechatUserId: String): Long? {
        return manager.getMappingForWechatUser(wechatUserId)
    }

    override suspend fun getOrCreateMapping(wechatUserId: String): Long? {
        val id = manager.getOrCreateMapping(wechatUserId)
        if (id == null) {
            SecureLog.w(
                TAG,
                "getOrCreate failed reason=${WeChatFailureReason.MAPPING_MISSING.wireName} user=${mask(wechatUserId)}",
            )
        }
        return id
    }

    override suspend fun listMappings(): List<WeChatUserMapping> {
        val now = System.currentTimeMillis()
        return manager.getAllMappings()
            .filter { (_, companionId) -> companionId > 0 }
            .map { (wechatUserId, companionId) ->
                WeChatUserMapping(
                    wechatUserId = wechatUserId,
                    companionId = companionId,
                    updatedAtMs = now,
                )
            }
            .sortedBy { it.wechatUserId }
    }

    override suspend fun bind(wechatUserId: String, companionId: Long) {
        require(wechatUserId.isNotBlank()) { "wechatUserId blank" }
        require(companionId > 0) { "companionId invalid" }
        val ok = manager.updateMapping(wechatUserId.trim(), companionId)
        if (!ok) {
            SecureLog.w(
                TAG,
                "bind failed reason=${WeChatFailureReason.MAPPING_INVALID.wireName} companionId=$companionId",
            )
            throw IllegalArgumentException("伴侣不存在，可能已被删除，请重新选择")
        }
        SecureLog.i(TAG, "bind user=${mask(wechatUserId)} companionId=$companionId")
    }

    override suspend fun unbind(wechatUserId: String) {
        manager.removeMapping(wechatUserId)
        SecureLog.i(TAG, "unbind user=${mask(wechatUserId)}")
    }

    companion object {
        private const val TAG = "WeChatIdentityMap"

        private fun mask(userId: String): String {
            if (userId.length <= 6) return "***"
            return userId.take(3) + "***" + userId.takeLast(2)
        }
    }
}
