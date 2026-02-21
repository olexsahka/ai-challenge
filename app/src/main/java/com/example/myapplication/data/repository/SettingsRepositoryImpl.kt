package com.example.myapplication.data.repository

import android.content.Context
import com.example.myapplication.domain.model.RestrictionProfile
import com.example.myapplication.domain.model.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SettingsRepositoryImpl(context: Context) : SettingsRepository {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun getSettings(): Settings {
        val json = prefs.getString(KEY_PROFILES, null)
        val profiles = if (json != null) {
            try {
                val type = object : TypeToken<List<RestrictionProfile>>() {}.type
                gson.fromJson<List<RestrictionProfile>>(json, type)
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
            .putString(KEY_PROFILES, gson.toJson(settings.profiles))
            .apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
    }
}
