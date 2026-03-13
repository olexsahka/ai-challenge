package com.example.myapplication.data.repository

import android.content.Context
import androidx.core.content.edit

data class UserInformation(
    val name: String = "",
    val occupation: String = "",
    val language: String = "",
    val responseStyle: String = "",
    val responseFormat: String = "",
    val constraints: String = "",
    val additionalNotes: String = ""
)

data class TaskMemory(
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = false
)

class UserProfileRepository(context: Context) {
    private val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    var userInformation: UserInformation
        get() = UserInformation(
            name = prefs.getString(KEY_USER_NAME, "") ?: "",
            occupation = prefs.getString(KEY_USER_OCCUPATION, "") ?: "",
            language = prefs.getString(KEY_USER_LANGUAGE, "") ?: "",
            responseStyle = prefs.getString(KEY_RESPONSE_STYLE, "") ?: "",
            responseFormat = prefs.getString(KEY_RESPONSE_FORMAT, "") ?: "",
            constraints = prefs.getString(KEY_CONSTRAINTS, "") ?: "",
            additionalNotes = prefs.getString(KEY_ADDITIONAL_NOTES, "") ?: ""
        )
        set(value) = prefs.edit {
            putString(KEY_USER_NAME, value.name)
            putString(KEY_USER_OCCUPATION, value.occupation)
            putString(KEY_USER_LANGUAGE, value.language)
            putString(KEY_RESPONSE_STYLE, value.responseStyle)
            putString(KEY_RESPONSE_FORMAT, value.responseFormat)
            putString(KEY_CONSTRAINTS, value.constraints)
            putString(KEY_ADDITIONAL_NOTES, value.additionalNotes)
        }

    var taskMemory: TaskMemory
        get() = TaskMemory(
            name = prefs.getString(KEY_TASK_NAME, "") ?: "",
            description = prefs.getString(KEY_TASK_DESCRIPTION, "") ?: "",
            enabled = prefs.getBoolean(KEY_TASK_ENABLED, false)
        )
        set(value) = prefs.edit {
            putString(KEY_TASK_NAME, value.name)
            putString(KEY_TASK_DESCRIPTION, value.description)
            putBoolean(KEY_TASK_ENABLED, value.enabled)
        }

    fun userInformationContextString(): String {
        val info = userInformation
        val infoLines = mutableListOf<String>()
        if (info.name.isNotBlank()) infoLines.add("Name: ${info.name.trim()}")
        if (info.occupation.isNotBlank()) infoLines.add("Occupation: ${info.occupation.trim()}")
        if (info.language.isNotBlank()) infoLines.add("Language: ${info.language.trim()}")
        if (info.responseStyle.isNotBlank()) infoLines.add("Response style: ${info.responseStyle.trim()}")
        if (info.responseFormat.isNotBlank()) infoLines.add("Response format: ${info.responseFormat.trim()}")
        if (info.constraints.isNotBlank()) infoLines.add("Constraints: ${info.constraints.trim()}")
        if (info.additionalNotes.isNotBlank()) infoLines.add("Notes: ${info.additionalNotes.trim()}")
        return if (infoLines.isNotEmpty()) "User information:\n${infoLines.joinToString("\n")}" else ""
    }

    fun toContextString(): String {
        val parts = mutableListOf<String>()

        val info = userInformation
        val infoLines = mutableListOf<String>()
        if (info.name.isNotBlank()) infoLines.add("Name: ${info.name.trim()}")
        if (info.occupation.isNotBlank()) infoLines.add("Occupation: ${info.occupation.trim()}")
        if (info.language.isNotBlank()) infoLines.add("Language: ${info.language.trim()}")
        if (info.responseStyle.isNotBlank()) infoLines.add("Response style: ${info.responseStyle.trim()}")
        if (info.responseFormat.isNotBlank()) infoLines.add("Response format: ${info.responseFormat.trim()}")
        if (info.constraints.isNotBlank()) infoLines.add("Constraints: ${info.constraints.trim()}")
        if (info.additionalNotes.isNotBlank()) infoLines.add("Notes: ${info.additionalNotes.trim()}")
        if (infoLines.isNotEmpty()) {
            parts.add("User information:\n${infoLines.joinToString("\n")}")
        }

        val task = taskMemory
        if (task.enabled && (task.name.isNotBlank() || task.description.isNotBlank())) {
            val sb = StringBuilder("Current task:")
            if (task.name.isNotBlank()) sb.append("\nName: ${task.name.trim()}")
            if (task.description.isNotBlank()) sb.append("\nDescription: ${task.description.trim()}")
            parts.add(sb.toString())
        }

        return parts.joinToString("\n\n")
    }

    companion object {
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_OCCUPATION = "user_occupation"
        private const val KEY_USER_LANGUAGE = "user_language"
        private const val KEY_RESPONSE_STYLE = "response_style"
        private const val KEY_RESPONSE_FORMAT = "response_format"
        private const val KEY_CONSTRAINTS = "constraints"
        private const val KEY_ADDITIONAL_NOTES = "additional_notes"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_TASK_DESCRIPTION = "task_description"
        private const val KEY_TASK_ENABLED = "task_enabled"
    }
}
