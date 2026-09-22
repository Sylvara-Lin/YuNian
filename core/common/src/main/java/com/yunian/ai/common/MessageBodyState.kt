package com.yunian.ai.common

sealed interface MessageBodyState<out T> {
    data object Loading : MessageBodyState<Nothing>
    data class Ready<T>(val value: T) : MessageBodyState<T>
    data class Error(val message: String) : MessageBodyState<Nothing>
}