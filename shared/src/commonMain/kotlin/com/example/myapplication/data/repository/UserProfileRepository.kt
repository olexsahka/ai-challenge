package com.example.myapplication.data.repository

import com.example.myapplication.platform.KeyValueStorage

data class UserInformation(
    val name: String = "",
    val occupation: String = "",
    val language: String = "",
    val responseStyle: String = "",
    val responseFormat: String = "",
    val additionalNotes: String = ""
)

data class TaskMemory(
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = false
)

open class UserProfileRepository(private val storage: KeyValueStorage) {

    var userInformation: UserInformation
        get() = UserInformation(
            name = storage.getString(KEY_USER_NAME) ?: "",
            occupation = storage.getString(KEY_USER_OCCUPATION) ?: "",
            language = storage.getString(KEY_USER_LANGUAGE) ?: "",
            responseStyle = storage.getString(KEY_RESPONSE_STYLE) ?: "",
            responseFormat = storage.getString(KEY_RESPONSE_FORMAT) ?: "",
            additionalNotes = storage.getString(KEY_ADDITIONAL_NOTES) ?: ""
        )
        set(value) {
            storage.putString(KEY_USER_NAME, value.name)
            storage.putString(KEY_USER_OCCUPATION, value.occupation)
            storage.putString(KEY_USER_LANGUAGE, value.language)
            storage.putString(KEY_RESPONSE_STYLE, value.responseStyle)
            storage.putString(KEY_RESPONSE_FORMAT, value.responseFormat)
            storage.putString(KEY_ADDITIONAL_NOTES, value.additionalNotes)
        }

    var taskMemory: TaskMemory
        get() = TaskMemory(
            name = storage.getString(KEY_TASK_NAME) ?: "",
            description = storage.getString(KEY_TASK_DESCRIPTION) ?: "",
            enabled = storage.getBoolean(KEY_TASK_ENABLED, false)
        )
        set(value) {
            storage.putString(KEY_TASK_NAME, value.name)
            storage.putString(KEY_TASK_DESCRIPTION, value.description)
            storage.putBoolean(KEY_TASK_ENABLED, value.enabled)
        }

    private fun buildUserInfoLines(info: UserInformation): List<String> = buildList {
        if (info.name.isNotBlank()) add("Name: ${info.name.trim()}")
        if (info.occupation.isNotBlank()) add("Occupation: ${info.occupation.trim()}")
        if (info.language.isNotBlank()) add("Language: ${info.language.trim()}")
        if (info.responseStyle.isNotBlank()) add("Response style: ${info.responseStyle.trim()}")
        if (info.responseFormat.isNotBlank()) add("Response format: ${info.responseFormat.trim()}")
        if (info.additionalNotes.isNotBlank()) add("Notes: ${info.additionalNotes.trim()}")
    }

    fun userInformationContextString(): String {
        val lines = buildUserInfoLines(userInformation)
        return if (lines.isNotEmpty()) "User information:\n${lines.joinToString("\n")}" else ""
    }

    open fun toContextString(): String {
        val parts = mutableListOf<String>()

        val lines = buildUserInfoLines(userInformation)
        if (lines.isNotEmpty()) parts.add("User information:\n${lines.joinToString("\n")}")

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
        private const val KEY_ADDITIONAL_NOTES = "additional_notes"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_TASK_DESCRIPTION = "task_description"
        private const val KEY_TASK_ENABLED = "task_enabled"
    }
}
