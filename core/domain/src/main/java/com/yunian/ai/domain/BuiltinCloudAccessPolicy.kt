package com.yunian.ai.domain

interface BuiltinCloudAccessPolicy {
    fun isBuiltinCloudAccessAllowed(): Boolean

    fun denialReason(): String?
}
