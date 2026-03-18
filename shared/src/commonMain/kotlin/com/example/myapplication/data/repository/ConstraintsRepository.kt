package com.example.myapplication.data.repository

import com.example.myapplication.platform.KeyValueStorage

data class Constraints(
    val rules: String = "",
    val enabled: Boolean = false
)

class ConstraintsRepository(private val storage: KeyValueStorage) {

    var constraints: Constraints
        get() = Constraints(
            rules = storage.getString(KEY_RULES) ?: "",
            enabled = storage.getBoolean(KEY_ENABLED, false)
        )
        set(value) {
            storage.putString(KEY_RULES, value.rules)
            storage.putBoolean(KEY_ENABLED, value.enabled)
        }

    fun toContextBlock(): String {
        val c = constraints
        if (!c.enabled || c.rules.isBlank()) return ""
        return "Agent constraints (MUST NEVER violate):\n${c.rules.trim()}"
    }

    companion object {
        private const val KEY_RULES = "constraints_rules"
        private const val KEY_ENABLED = "constraints_enabled"
    }
}
