package com.example.myapplication.domain.model

data class RestrictionProfile(
    val name: String = "",
    val responseFormatDescription: String = "",
    val maxOutputTokens: Int? = null,
    val stopSequence: String = "",
    val generatePromptFirst: Boolean = false,
    val temperature: Float = 1.0f,
    val model: String = "gpt-4o"
)

data class Settings(
    val profiles: List<RestrictionProfile> = emptyList(),
    val unrestrictedGeneratePromptFirst: Boolean = false,
    val unrestrictedTemperature: Float = 1.0f,
    val unrestrictedModel: String = "gpt-4o",
    val createLesson3Chats: Boolean = false
)
