package com.example.myapplication.data.repository

import android.content.Context
import androidx.core.content.edit

data class Constraints(
    val rules: String = "",
    val enabled: Boolean = false
)

class ConstraintsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("agent_constraints", Context.MODE_PRIVATE)

    var constraints: Constraints
        get() = Constraints(
            rules = prefs.getString(KEY_RULES, "") ?: "",
            enabled = prefs.getBoolean(KEY_ENABLED, false)
        )
        set(value) = prefs.edit {
            putString(KEY_RULES, value.rules)
            putBoolean(KEY_ENABLED, value.enabled)
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
