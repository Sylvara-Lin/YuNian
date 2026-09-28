package com.yunian.ai.feature.chat.data

import android.content.Context

class ChatDraftStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDraft(companionId: Long): String = prefs.getString(key(companionId), null).orEmpty()

    fun setDraft(companionId: Long, text: String) {
        val editor = prefs.edit()
        if (text.isBlank()) {
            editor.remove(key(companionId))
        } else {
            editor.putString(key(companionId), text)
        }
        editor.apply()
    }

    private fun key(companionId: Long) = "draft_$companionId"

    private companion object {
        const val PREFS_NAME = "chat_draft_store"
    }
}
