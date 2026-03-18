package com.example.myapplication.data.mcp

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "mcp_prefs"
private const val KEY_VKUSVILL_ENABLED = "vkusvill_enabled"

open class McpRepository(
    context: Context?,
    private val mcpClient: McpClient?
) : McpProviderFacade {
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    open var vkusVillEnabled: Boolean
        get() = prefs?.getBoolean(KEY_VKUSVILL_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_VKUSVILL_ENABLED, value)?.apply() }

    override val isEnabled: Boolean get() = vkusVillEnabled

    override suspend fun connect(): McpConnectionStatus = mcpClient!!.connect()

    override fun disconnect() = mcpClient!!.disconnect()

    override val isConnected: Boolean get() = mcpClient?.isConnected ?: false

    override suspend fun callTool(toolName: String, argumentsJson: String): String {
        val arguments = org.json.JSONObject(argumentsJson)
        return mcpClient!!.callTool(toolName, arguments)
    }
}
