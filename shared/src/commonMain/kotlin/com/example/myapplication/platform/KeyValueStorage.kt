package com.example.myapplication.platform

interface KeyValueStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getAll(): Map<String, String>
    fun remove(key: String)
    fun clear()
}
