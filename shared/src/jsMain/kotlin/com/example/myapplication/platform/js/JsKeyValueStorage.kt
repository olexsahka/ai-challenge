package com.example.myapplication.platform.js

import com.example.myapplication.platform.KeyValueStorage
import kotlinx.browser.localStorage

class JsKeyValueStorage(private val prefix: String = "app_") : KeyValueStorage {

    override fun getString(key: String): String? = localStorage.getItem("$prefix$key")

    override fun putString(key: String, value: String) {
        localStorage.setItem("$prefix$key", value)
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        localStorage.getItem("$prefix$key")?.toBoolean() ?: default

    override fun putBoolean(key: String, value: Boolean) {
        localStorage.setItem("$prefix$key", value.toString())
    }

    override fun getAll(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (i in 0 until localStorage.length) {
            val fullKey = localStorage.key(i) ?: continue
            if (fullKey.startsWith(prefix)) {
                result[fullKey.removePrefix(prefix)] = localStorage.getItem(fullKey) ?: continue
            }
        }
        return result
    }

    override fun remove(key: String) {
        localStorage.removeItem("$prefix$key")
    }

    override fun clear() {
        val keysToRemove = (0 until localStorage.length)
            .mapNotNull { localStorage.key(it) }
            .filter { it.startsWith(prefix) }
        keysToRemove.forEach { localStorage.removeItem(it) }
    }
}
