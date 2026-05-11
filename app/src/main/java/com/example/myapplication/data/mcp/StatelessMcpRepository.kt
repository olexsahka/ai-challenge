package com.example.myapplication.data.mcp

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

open class StatelessMcpRepository(
    context: Context?,
    private val client: StatelessMcpClient,
    private val prefKey: String,
    prefName: String = "task_mcp_prefs"
) : McpProviderFacade {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    open var enabled: Boolean
        get() = prefs?.getBoolean(prefKey, true) ?: true
        set(value) { prefs?.edit()?.putBoolean(prefKey, value)?.apply() }

    override val isEnabled: Boolean get() = enabled

    override val isConnected: Boolean get() = client.isConnected

    override suspend fun connect(): McpConnectionStatus = client.connect()

    override fun disconnect() = client.disconnect()

    override suspend fun callTool(toolName: String, argumentsJson: String): String {
        return try {
            val arguments = JSONObject(argumentsJson)
            client.callTool(toolName, arguments)
        } catch (e: Exception) {
            "Error: invalid JSON arguments: ${e.message}"
        }
    }
}
