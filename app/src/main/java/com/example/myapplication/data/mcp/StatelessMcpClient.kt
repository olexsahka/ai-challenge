package com.example.myapplication.data.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class StatelessMcpClient(private val httpClient: OkHttpClient, private val url: String) {

    private val json = "application/json".toMediaType()
    private var connected = false

    val isConnected: Boolean get() = connected

    fun disconnect() {
        connected = false
    }

    suspend fun connect(): McpConnectionStatus = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", JSONObject())
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(json))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string() ?: ""
            response.close()

            val tools = parseTools(responseText)
            connected = true
            McpConnectionStatus.Connected(tools)
        } catch (e: Exception) {
            connected = false
            McpConnectionStatus.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        if (!connected) return@withContext "Error: not connected"
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", arguments)
                })
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(json))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string() ?: ""
            response.close()

            val result = JSONObject(responseText)
            if (result.has("error")) {
                "Error: ${result.getJSONObject("error").optString("message")}"
            } else {
                extractResultText(result) ?: responseText
            }
        } catch (e: Exception) {
            "Error calling tool: ${e.message}"
        }
    }

    internal fun extractResultText(root: JSONObject): String? {
        val resultObj = root.optJSONObject("result")
        if (resultObj != null) {
            val content = resultObj.optJSONArray("content")
            if (content != null && content.length() > 0) {
                val text = content.optJSONObject(0)?.optString("text")
                if (!text.isNullOrEmpty()) return text
            }
            return resultObj.toString().takeIf { it != "{}" }
        }
        val resultArr = root.optJSONArray("result")
        if (resultArr != null) return resultArr.toString()
        return null
    }

    private fun parseTools(json: String): List<McpTool> {
        return try {
            val root = JSONObject(json)
            val toolsArray = root.getJSONObject("result").getJSONArray("tools")
            (0 until toolsArray.length()).map { i ->
                val t = toolsArray.getJSONObject(i)
                McpTool(
                    name = t.getString("name"),
                    description = t.optString("description", ""),
                    inputSchemaJson = (t.optJSONObject("inputSchema") ?: JSONObject()).toString()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
