package com.example.myapplication.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

sealed class McpConnectionStatus {
    object Disconnected : McpConnectionStatus()
    object Connecting : McpConnectionStatus()
    data class Connected(val tools: List<McpTool>) : McpConnectionStatus()
    data class Error(val message: String) : McpConnectionStatus()
}

class McpClient(private val httpClient: OkHttpClient) {

    private val baseUrl = "https://mcp001.vkusvill.ru/mcp"
    private val json = "application/json".toMediaType()

    private var sessionId: String? = null

    val isConnected: Boolean get() = sessionId != null

    suspend fun connect(): McpConnectionStatus = withContext(Dispatchers.IO) {
        try {
            val initBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "AndroidMcpClient")
                        put("version", "1.0")
                    })
                })
            }.toString()

            val initRequest = Request.Builder()
                .url(baseUrl)
                .post(initBody.toRequestBody(json))
                .addHeader("Accept", "application/json, text/event-stream")
                .build()

            val initResponse = httpClient.newCall(initRequest).execute()
            val newSessionId = initResponse.header("Mcp-Session-Id")
                ?: return@withContext McpConnectionStatus.Error("No session id in response")
            initResponse.close()

            // Send notifications/initialized
            val notifyBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
                put("params", JSONObject())
            }.toString()

            val notifyRequest = Request.Builder()
                .url(baseUrl)
                .post(notifyBody.toRequestBody(json))
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("Mcp-Session-Id", newSessionId)
                .build()

            httpClient.newCall(notifyRequest).execute().close()

            // Get tools list
            val toolsBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/list")
                put("params", JSONObject())
            }.toString()

            val toolsRequest = Request.Builder()
                .url(baseUrl)
                .post(toolsBody.toRequestBody(json))
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("Mcp-Session-Id", newSessionId)
                .build()

            val toolsResponse = httpClient.newCall(toolsRequest).execute()
            val toolsJson = toolsResponse.body?.string()
                ?: return@withContext McpConnectionStatus.Error("Empty tools response")
            toolsResponse.close()

            val tools = parseTools(toolsJson)
            sessionId = newSessionId
            McpConnectionStatus.Connected(tools)
        } catch (e: Exception) {
            McpConnectionStatus.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext "Error: not connected"
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 3)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                })
            }.toString()

            val request = Request.Builder()
                .url(baseUrl)
                .post(body.toRequestBody(json))
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("Mcp-Session-Id", sid)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string() ?: ""
            response.close()

            val result = JSONObject(responseText)
            if (result.has("error")) {
                "Error: ${result.getJSONObject("error").optString("message")}"
            } else {
                val content = result.getJSONObject("result").optJSONArray("content")
                content?.let { arr ->
                    (0 until arr.length()).joinToString("\n") { i ->
                        arr.getJSONObject(i).optString("text", "")
                    }
                } ?: responseText
            }
        } catch (e: Exception) {
            "Error calling tool: ${e.message}"
        }
    }

    fun disconnect() {
        sessionId = null
    }

    private fun parseTools(json: String): List<McpTool> {
        return try {
            val root = JSONObject(json)
            val toolsArray: JSONArray = root.getJSONObject("result").getJSONArray("tools")
            (0 until toolsArray.length()).map { i ->
                val t = toolsArray.getJSONObject(i)
                McpTool(
                    name = t.getString("name"),
                    description = t.optString("description", ""),
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
