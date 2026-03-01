package com.example.myapplication.agent

import android.content.Context
import androidx.core.content.edit

data class MemoryEntry(
    val key: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis()
)

class AgentMemory(context: Context) {
    private val prefs = context.getSharedPreferences("agent_memory", Context.MODE_PRIVATE)

    fun store(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    fun recall(key: String): String? = prefs.getString(key, null)

    fun recallAll(): List<MemoryEntry> {
        return prefs.all.map { (k, v) ->
            MemoryEntry(key = k, value = v.toString())
        }
    }

    fun forget(key: String) {
        prefs.edit { remove(key) }
    }

    fun forgetAll() {
        prefs.edit { clear() }
    }

    fun toContextString(): String {
        val all = recallAll()
        if (all.isEmpty()) return ""
        return "Stored memories:\n" + all.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}
