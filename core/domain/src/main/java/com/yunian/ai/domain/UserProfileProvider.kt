package com.yunian.ai.domain

interface UserProfileProvider {
    fun getUserId(): String
    fun getNickname(): String
    fun getAvatar(): String?

    fun observeAvatar(onChange: (String?) -> Unit): () -> Unit

    fun observeNickname(onChange: (String) -> Unit): () -> Unit

    fun isLoggedIn(): Boolean
}
