package com.example.myapplication.data.reminder

import android.content.Context
import android.content.SharedPreferences
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpProviderFacade
import com.example.myapplication.data.mcp.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val PREFS_NAME = "reminder_prefs"
private const val KEY_ENABLED = "reminder_enabled"
private const val MCP_URL = "http://10.0.2.2:8080/mcp"

open class CryptoMcpRepository(
    context: Context?,
    private val httpClient: OkHttpClient?
) : McpProviderFacade {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val apiKey: String get() = BuildConfig.CRYPTO_MCP_API_KEY
    private var connected = false

    open var cryptoEnabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply() }

    override val isEnabled: Boolean get() = cryptoEnabled
    override val isConnected: Boolean get() = connected

    override suspend fun connect(): McpConnectionStatus = withContext(Dispatchers.IO) {
        val client = httpClient ?: return@withContext McpConnectionStatus.Error("Client not initialized")
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", JSONObject())
            }.toString()
            val request = Request.Builder()
                .url(MCP_URL)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
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

    override fun disconnect() {
        connected = false
    }

    override suspend fun callTool(toolName: String, argumentsJson: String): String =
        withContext(Dispatchers.IO) {
            val client = httpClient ?: return@withContext "Error: client not initialized"
            try {
                val body = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 2)
                    put("method", "tools/call")
                    put("params", JSONObject().apply {
                        put("name", toolName)
                        put("arguments", JSONObject(argumentsJson))
                    })
                }.toString()
                val request = Request.Builder()
                    .url(MCP_URL)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .build()
                val response = client.newCall(request).execute()
                val responseText = response.body?.string() ?: ""
                response.close()
                extractResult(responseText)
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

    private fun parseTools(json: String): List<McpTool> {
        return try {
            val root = JSONObject(json)
            val result = root.optJSONObject("result") ?: return emptyList()
            val toolsArray = result.optJSONArray("tools") ?: return emptyList()
            (0 until toolsArray.length()).map { i ->
                val t = toolsArray.getJSONObject(i)
                McpTool(
                    name = t.optString("name"),
                    description = t.optString("description"),
                    inputSchemaJson = (t.optJSONObject("inputSchema") ?: JSONObject()).toString()
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun extractResult(json: String): String {
        return try {
            val root = JSONObject(json)
            val result = root.optJSONObject("result") ?: return json
            val content = result.optJSONArray("content")
            if (content != null && content.length() > 0) {
                content.getJSONObject(0).optString("text", json)
            } else {
                result.toString()
            }
        } catch (_: Exception) { json }
    }
}
