package com.yunian.ai.domain

interface AutomationTickProvider {

    suspend fun onTick()
}
