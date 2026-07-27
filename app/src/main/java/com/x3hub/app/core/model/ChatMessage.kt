package com.x3hub.app.core.model

data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)
