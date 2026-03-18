package com.example.myapplication.data.mcp

/**
 * Represents a single MCP tool with its schema as a JSON string.
 * Using String instead of JSONObject to keep this KMP-safe (org.json not available in commonMain).
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}"
)

sealed class McpConnectionStatus {
    object Disconnected : McpConnectionStatus()
    object Connecting : McpConnectionStatus()
    data class Connected(val tools: List<McpTool>) : McpConnectionStatus()
    data class Error(val message: String) : McpConnectionStatus()
}
