package com.example.myapplication.data.repository

import android.content.Context
import com.example.myapplication.domain.model.RestrictionProfile
import com.example.myapplication.domain.model.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun getSettings(): Settings {
        val jsonStr = prefs.getString(KEY_PROFILES, null)
        val profiles = if (jsonStr != null) {
            try {
                json.decodeFromString(ListSerializer(RestrictionProfile.serializer()), jsonStr)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        return Settings(profiles = profiles)
    }

    override fun saveSettings(settings: Settings) {
        prefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(ListSerializer(RestrictionProfile.serializer()), settings.profiles))
            .apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
    }
}
