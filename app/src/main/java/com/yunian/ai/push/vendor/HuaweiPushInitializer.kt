package com.yunian.ai.push.vendor

import android.app.Application
import com.huawei.hms.aaid.HmsInstanceId
import com.huawei.hms.common.ApiException
import com.huawei.hms.push.HmsMessaging
import com.yunian.ai.common.SecureLog
import com.yunian.ai.push.dispatch.PushMessageDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * 华为 HMS 推送初始化。
 *
 * ⚠️ **当前为停用状态（2026-09-30）**：`PushManager` 已不再调用本类的 [register]。
 * 原因是 HMS Push 在本工程无法工作（仓库内无 `agconnect-services.json`，且 shell manifest
 * 已移除 HMS 核心 Provider），但在华为设备上初始化会拉起注定失败的 HMS 组件 → 安装/启动即闪退。
 * 详见 `app/src/main/AndroidManifest.xml` 与 `app/src/shell/AndroidManifest.xml` 中的说明。
 *
 * 本文件保留并已加固（异常兜底到 `Throwable`），以便将来补齐 HMS 配置后可直接复用。
 */
object HuaweiPushInitializer {

    private const val TAG = "HuaweiPush"

    fun register(application: Application) {
        try {
            HmsMessaging.getInstance(application).isAutoInitEnabled = true
            requestToken(application)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // 兜到 Throwable：HMS SDK 初始化失败可能抛 Error（ExceptionInInitializerError /
            // NoClassDefFoundError 等），只 catch Exception 会让它逃逸并杀死进程。
            SecureLog.e(TAG, "Failed to register Huawei push: ${t.javaClass.simpleName}", t)
        }
    }

    private fun requestToken(application: Application) {
        // 注意：GlobalScope 内没有异常处理器，抛出的 Throwable 会直达默认未捕获处理器 → 闪退。
        // 因此这里**必须**兜到 Throwable（取消除外）。
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val token = HmsInstanceId.getInstance(application)
                    .getToken(application.packageName, HmsMessaging.DEFAULT_TOKEN_SCOPE)
                if (!token.isNullOrBlank()) {
                    SecureLog.d(TAG, "Huawei token obtained")
                    PushMessageDispatcher.onTokenReceived(application, "huawei", token)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: ApiException) {
                SecureLog.e(TAG, "Huawei getToken failed: ${e.statusCode}", e)
            } catch (t: Throwable) {
                SecureLog.e(TAG, "Huawei getToken error: ${t.javaClass.simpleName}", t)
            }
        }
    }
}
