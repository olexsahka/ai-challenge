package com.example.myapplication.data.repository

import android.content.Context
import com.example.myapplication.domain.model.Settings

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun getSettings(): Settings {
        val maxTokens = prefs.getInt(KEY_MAX_OUTPUT_TOKENS, 0)
        return Settings(
            showWithoutRestrictions = prefs.getBoolean(KEY_SHOW_WITHOUT_RESTRICTIONS, false),
            responseFormatDescription = prefs.getString(KEY_RESPONSE_FORMAT, "") ?: "",
            maxOutputTokens = if (maxTokens > 0) maxTokens else null,
            stopSequence = prefs.getString(KEY_STOP_SEQUENCE, "") ?: ""
        )
    }

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_SHOW_WITHOUT_RESTRICTIONS, settings.showWithoutRestrictions)
            .putString(KEY_RESPONSE_FORMAT, settings.responseFormatDescription)
            .putInt(KEY_MAX_OUTPUT_TOKENS, settings.maxOutputTokens ?: 0)
            .putString(KEY_STOP_SEQUENCE, settings.stopSequence)
            .apply()
    }

    companion object {
        private const val KEY_SHOW_WITHOUT_RESTRICTIONS = "show_without_restrictions"
        private const val KEY_RESPONSE_FORMAT = "response_format_description"
        private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
        private const val KEY_STOP_SEQUENCE = "stop_sequence"
    }
}
