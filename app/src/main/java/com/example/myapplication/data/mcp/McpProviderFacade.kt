package com.example.myapplication.data.mcp

import org.json.JSONObject

/**
 * Unified interface for all MCP provider repositories.
 * Both VkusVill and Telegram repositories implement this.
 */
interface McpProviderFacade {
    val isEnabled: Boolean
    val isConnected: Boolean
    suspend fun connect(): McpConnectionStatus
    fun disconnect()
    suspend fun callTool(toolName: String, arguments: JSONObject): String
}
