package com.example.myapplication.data.repository

import com.example.myapplication.domain.model.Settings

interface SettingsRepository {
    fun getSettings(): Settings
    fun saveSettings(settings: Settings)
}
