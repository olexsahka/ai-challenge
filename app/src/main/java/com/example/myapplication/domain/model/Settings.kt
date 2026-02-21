package com.example.myapplication.domain.model

data class RestrictionProfile(
    val name: String = "",
    val responseFormatDescription: String = "",
    val maxOutputTokens: Int? = null,
    val stopSequence: String = "",
    val generatePromptFirst: Boolean = false
)

data class Settings(
    val profiles: List<RestrictionProfile> = emptyList(),
    val unrestrictedGeneratePromptFirst: Boolean = false,
    val createLesson3Chats: Boolean = false
)
