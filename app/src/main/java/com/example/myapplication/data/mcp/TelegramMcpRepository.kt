package com.example.myapplication.data.mcp

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "telegram_mcp_prefs"
private const val KEY_ENABLED = "telegram_enabled"
private const val USERNAME = "mcp"

open class TelegramMcpRepository(
    context: Context?,
    private val client: TelegramMcpClient?,
    private val password: String
) : McpProviderFacade {
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    open var telegramEnabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply() }

    override val isEnabled: Boolean get() = telegramEnabled

    override val isConnected: Boolean get() = client?.isConnected ?: false

    override suspend fun connect(): McpConnectionStatus {
        val c = client ?: return McpConnectionStatus.Error("Client not initialized")
        c.setCredentials(USERNAME, password)
        return c.connect()
    }

    override fun disconnect() = client?.disconnect() ?: Unit

    override suspend fun callTool(toolName: String, argumentsJson: String): String {
        val arguments = org.json.JSONObject(argumentsJson)
        return client?.callTool(toolName, arguments) ?: "Error: client not initialized"
    }
}
