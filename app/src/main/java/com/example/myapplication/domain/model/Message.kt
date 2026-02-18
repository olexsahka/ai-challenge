package com.example.myapplication.domain.model

data class Message(
    val id: String,
    val content: String,
    val isFromUser: Boolean
)