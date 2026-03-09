package com.example.myapplication.data.repository

import android.content.Context
import androidx.core.content.edit

class UserProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    var profileDescription: String
        get() = prefs.getString(KEY_PROFILE_DESCRIPTION, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PROFILE_DESCRIPTION, value) }

    var profileEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_PROFILE_ENABLED, value) }

    var taskName: String
        get() = prefs.getString(KEY_TASK_NAME, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TASK_NAME, value) }

    var taskDescription: String
        get() = prefs.getString(KEY_TASK_DESCRIPTION, "") ?: ""
        set(value) = prefs.edit { putString(KEY_TASK_DESCRIPTION, value) }

    var taskEnabled: Boolean
        get() = prefs.getBoolean(KEY_TASK_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_TASK_ENABLED, value) }

    fun toContextString(): String {
        val parts = mutableListOf<String>()
        if (profileEnabled && profileDescription.isNotBlank()) {
            parts.add("User profile:\n${profileDescription.trim()}")
        }
        if (taskEnabled && (taskName.isNotBlank() || taskDescription.isNotBlank())) {
            val sb = StringBuilder("Current task:")
            if (taskName.isNotBlank()) sb.append("\nName: ${taskName.trim()}")
            if (taskDescription.isNotBlank()) sb.append("\nDescription: ${taskDescription.trim()}")
            parts.add(sb.toString())
        }
        return parts.joinToString("\n\n")
    }

    companion object {
        private const val KEY_PROFILE_DESCRIPTION = "profile_description"
        private const val KEY_PROFILE_ENABLED = "profile_enabled"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_TASK_DESCRIPTION = "task_description"
        private const val KEY_TASK_ENABLED = "task_enabled"
    }
}
