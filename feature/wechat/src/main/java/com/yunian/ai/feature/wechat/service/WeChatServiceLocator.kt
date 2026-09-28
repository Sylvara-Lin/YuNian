package com.yunian.ai.feature.wechat.service

import android.content.Context
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.feature.wechat.data.SdkWeChatTransport
import com.yunian.ai.feature.wechat.data.WeChatChatBridge
import com.yunian.ai.feature.wechat.data.WeChatMessageRepository
import com.yunian.ai.feature.wechat.data.WeChatStickerMaterializer
import com.yunian.ai.feature.wechat.data.WeChatTokenStore
import com.yunian.ai.wechat.inbox.WeChatInboxCoordinator
import com.yunian.ai.wechat.ilink.IlinkClientManager
import com.yunian.ai.wechat.outbox.WeChatOutboxCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

object WeChatServiceLocator {

    @Volatile
    private var tokenStore: WeChatTokenStore? = null

    @Volatile
    private var messageRepository: WeChatMessageRepository? = null

    @Volatile
    private var sdkClientManager: IlinkClientManager? = null

    @Volatile
    private var chatBridge: WeChatChatBridge? = null

    @Volatile
    private var outboxCoordinator: WeChatOutboxCoordinator? = null

    @Volatile
    private var inboxCoordinator: WeChatInboxCoordinator? = null

    @Volatile
    private var channelScope: CoroutineScope? = null

    fun tokenStore(context: Context): WeChatTokenStore {
        return tokenStore ?: synchronized(this) {
            tokenStore ?: WeChatTokenStore(context.applicationContext).also {
                tokenStore = it
            }
        }
    }

    fun messageRepository(context: Context): WeChatMessageRepository {
        return messageRepository ?: synchronized(this) {
            messageRepository ?: run {
                val store = tokenStore(context)
                val manager = sdkClientManager(context)
                WeChatMessageRepository(context.applicationContext, manager, store).also {
                    messageRepository = it
                }
            }
        }
    }

    fun chatBridge(context: Context): WeChatChatBridge {

        return chatBridge ?: synchronized(this) {
            chatBridge ?: run {
                val repo = messageRepository(context)
                WeChatChatBridge(context.applicationContext, repo).also {
                    chatBridge = it
                }
            }
        }
    }

    fun outboxCoordinator(context: Context): WeChatOutboxCoordinator {
        return outboxCoordinator ?: synchronized(this) {
            outboxCoordinator ?: run {
                val app = context.applicationContext
                val db = AppDatabase.getDatabase(app)
                val store = tokenStore(app)
                val transport = SdkWeChatTransport(sdkClientManager(app), store)
                WeChatOutboxCoordinator(
                    dao = db.weChatOutboxDao(),
                    transport = transport,
                    sessionStore = store,
                    managedMediaCacheDir = java.io.File(app.cacheDir, WeChatStickerMaterializer.CACHE_DIR),
                ).also { outboxCoordinator = it }
            }
        }
    }

    fun inboxCoordinator(context: Context): WeChatInboxCoordinator {
        return inboxCoordinator ?: synchronized(this) {
            inboxCoordinator ?: run {
                val app = context.applicationContext
                val db = AppDatabase.getDatabase(app)
                WeChatInboxCoordinator(
                    dedupeDao = db.weChatInboxDedupeDao(),
                    scope = channelScope(app),
                ).also { inboxCoordinator = it }
            }
        }
    }

    private fun channelScope(context: Context): CoroutineScope {

        return channelScope ?: synchronized(this) {
            channelScope ?: CoroutineScope(SupervisorJob() + AppDispatchers.io).also {
                channelScope = it
            }
        }
    }

    fun shutdown() {
        synchronized(this) {
            chatBridge?.close()
            chatBridge = null
            inboxCoordinator?.cancelAll()
            inboxCoordinator = null
            outboxCoordinator = null
            channelScope?.cancel()
            channelScope = null
            messageRepository?.destroy()
            messageRepository = null
            sdkClientManager = null
            tokenStore = null
            WeChatChannelRuntime.reset()
        }
    }

    fun sdkClientManager(context: Context): IlinkClientManager {
        return sdkClientManager ?: synchronized(this) {
            sdkClientManager ?: IlinkClientManager(tokenStore(context)).also {
                sdkClientManager = it
            }
        }
    }
}
