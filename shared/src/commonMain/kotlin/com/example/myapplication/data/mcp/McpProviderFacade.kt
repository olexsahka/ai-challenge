package com.example.myapplication.data.mcp

/**
 * Unified interface for all MCP provider repositories.
 * Both VkusVill and Telegram repositories implement this.
 */
interface McpProviderFacade {
    val isEnabled: Boolean
    val isConnected: Boolean
    suspend fun connect(): McpConnectionStatus
    fun disconnect()
    suspend fun callTool(toolName: String, argumentsJson: String): String
}
