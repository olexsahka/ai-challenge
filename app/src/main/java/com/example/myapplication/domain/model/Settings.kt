package com.example.myapplication.domain.model

data class Settings(
    val showWithoutRestrictions: Boolean = false,
    val responseFormatDescription: String = "",
    val maxOutputTokens: Int? = null,
    val stopSequence: String = ""
)
