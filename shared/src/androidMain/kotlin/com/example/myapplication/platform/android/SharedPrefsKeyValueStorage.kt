package com.example.myapplication.platform.android

import android.content.Context
import androidx.core.content.edit
import com.example.myapplication.platform.KeyValueStorage

class SharedPrefsKeyValueStorage(context: Context, name: String) : KeyValueStorage {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun getAll(): Map<String, String> = prefs.all.mapValues { it.value.toString() }
    override fun remove(key: String) = prefs.edit { remove(key) }
    override fun clear() = prefs.edit { clear() }
}
