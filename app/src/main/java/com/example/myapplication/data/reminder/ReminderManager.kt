package com.example.myapplication.data.reminder

import android.content.Context
import android.content.SharedPreferences
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val PREFS_NAME = "reminder_prefs"
private const val KEY_ENABLED = "reminder_enabled"
private const val BASE_URL = "http://10.0.2.2:8080"
private const val MCP_URL = "$BASE_URL/mcp"

class ReminderManager(
    context: Context,
    private val sseRepository: ReminderSseRepository,
    private val httpClient: OkHttpClient
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _reminderEvents = MutableSharedFlow<ReminderEvent>(extraBufferCapacity = 64)
    val reminderEvents: Flow<ReminderEvent> = _reminderEvents.asSharedFlow()

    private val apiKey: String get() = BuildConfig.CRYPTO_MCP_API_KEY

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    suspend fun connect(): McpConnectionStatus = withContext(Dispatchers.IO) {
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
            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string() ?: ""
            response.close()
            val tools = parseTools(responseText)
            McpConnectionStatus.Connected(tools)
        } catch (e: Exception) {
            McpConnectionStatus.Error(e.message ?: "Unknown error")
        }
    }

    fun connectFlow(): Flow<ReminderEvent> = sseRepository.connect(BASE_URL, apiKey)

    suspend fun emitReminder(event: ReminderEvent) {
        _reminderEvents.emit(event)
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
                    inputSchemaJson = (t.optJSONObject("inputSchema") ?: org.json.JSONObject()).toString()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
