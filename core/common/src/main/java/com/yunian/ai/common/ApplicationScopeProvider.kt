package com.yunian.ai.common

import com.yunian.ai.common.concurrent.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

object ApplicationScopeProvider {

    private var _scope: CoroutineScope? = null

    val scope: CoroutineScope
        get() = _scope ?: CoroutineScope(SupervisorJob() + AppDispatchers.io).also {

            SecureLog.w("ApplicationScopeProvider", "Using fallback scope; did you forget to call init()?")
        }

    fun init(scope: CoroutineScope) {
        _scope = scope
    }
}
