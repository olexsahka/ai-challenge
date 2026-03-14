package com.example.myapplication.data.mcp

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "mcp_prefs"
private const val KEY_VKUSVILL_ENABLED = "vkusvill_enabled"

open class McpRepository(
    context: Context?,
    private val mcpClient: McpClient?
) {
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    open var vkusVillEnabled: Boolean
        get() = prefs?.getBoolean(KEY_VKUSVILL_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_VKUSVILL_ENABLED, value)?.apply() }

    open suspend fun connect(): McpConnectionStatus = mcpClient!!.connect()

    open fun disconnect() = mcpClient!!.disconnect()

    open val isConnected: Boolean get() = mcpClient?.isConnected ?: false

    open suspend fun callTool(toolName: String, arguments: org.json.JSONObject): String =
        mcpClient!!.callTool(toolName, arguments)
}
