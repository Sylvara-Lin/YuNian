package com.yunian.ai.common

enum class CompanionRole {
    GIRLFRIEND,
    BOYFRIEND;

    companion object {
        fun fromName(name: String?): CompanionRole = when (name?.uppercase()) {
            "BOYFRIEND" -> BOYFRIEND
            else -> GIRLFRIEND
        }
    }
}
