package com.example.myapplication.agent

import com.example.myapplication.platform.KeyValueStorage
//test
data class MemoryEntry(
    val key: String,
    val value: String,
    val createdAt: Long = 0L
)

class AgentMemory(private val storage: KeyValueStorage) {

    fun store(key: String, value: String) {
        storage.putString(key, value)
    }

    fun recall(key: String): String? = storage.getString(key)

    fun recallAll(): List<MemoryEntry> {
        return storage.getAll().map { (k, v) -> MemoryEntry(key = k, value = v) }
    }

    fun forget(key: String) {
        storage.remove(key)
    }

    fun forgetAll() {
        storage.clear()
    }

    fun toContextString(): String {
        val all = recallAll()
        if (all.isEmpty()) return ""
        return "Stored memories:\n" + all.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}
