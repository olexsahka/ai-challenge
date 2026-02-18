package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.Message

interface ChatRepository {
    suspend fun sendMessage(messages: List<Message>): Result<String>
}
