package com.example.myapplication.data.mcp

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TELEGRAM_MCP_URL = "http://178.72.166.221/mcp"

class TelegramMcpClient(private val httpClient: OkHttpClient) {

    private val json = "application/json".toMediaType()
    private var basicAuthHeader: String? = null
    // title -> chat_id mapping, populated from get_dialogs / search_dialog responses
    private val dialogIdMap = mutableMapOf<String, Long>()

    val isConnected: Boolean get() = basicAuthHeader != null

    fun setCredentials(username: String, password: String) {
        val encoded = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        basicAuthHeader = "Basic $encoded"
    }

    fun disconnect() {
        basicAuthHeader = null
    }

    suspend fun connect(): McpConnectionStatus = withContext(Dispatchers.IO) {
        val auth = basicAuthHeader
            ?: return@withContext McpConnectionStatus.Error("No credentials set")
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", JSONObject())
            }.toString()

            val request = Request.Builder()
                .url(TELEGRAM_MCP_URL)
                .post(body.toRequestBody(json))
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string() ?: ""
            response.close()

            val tools = parseTools(responseText)
            // Pre-load dialogs so dialogIdMap is populated before first send_message
            loadDialogsIntoMap(auth)
            McpConnectionStatus.Connected(tools)
        } catch (e: Exception) {
            McpConnectionStatus.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun callTool(toolName: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        val auth = basicAuthHeader ?: return@withContext "Error: not connected"
        try {
            val resolvedArgs = resolveDialogId(arguments)
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", resolvedArgs)
                })
            }.toString()

            val request = Request.Builder()
                .url(TELEGRAM_MCP_URL)
                .post(body.toRequestBody(json))
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseText = response.body?.string() ?: ""
            response.close()

            val result = JSONObject(responseText)
            if (result.has("error")) {
                "Error: ${result.getJSONObject("error").optString("message")}"
            } else {
                val raw = extractResultText(result) ?: responseText
                stripChatIds(raw)
            }
        } catch (e: Exception) {
            "Error calling tool: ${e.message}"
        }
    }

    // Extract text content from MCP JSON-RPC result envelope
    // tools/call result = {"content": [{"type":"text","text":"..."}], "isError": false}
    // tools/list or direct array result = [...]
    private fun extractResultText(root: JSONObject): String? {
        // Case 1: result is an object with "content" array (tools/call)
        val resultObj = root.optJSONObject("result")
        if (resultObj != null) {
            val content = resultObj.optJSONArray("content")
            if (content != null && content.length() > 0) {
                val text = content.optJSONObject(0)?.optString("text")
                if (!text.isNullOrEmpty()) return text
            }
            return resultObj.toString().takeIf { it != "{}" }
        }
        // Case 2: result is a JSON array directly
        val resultArr = root.optJSONArray("result")
        if (resultArr != null) return resultArr.toString()
        return null
    }

    private fun loadDialogsIntoMap(auth: String) {
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 10)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "get_dialogs")
                    put("arguments", JSONObject().apply { put("limit", 200) })
                })
            }.toString()
            val request = Request.Builder()
                .url(TELEGRAM_MCP_URL)
                .post(body.toRequestBody(json))
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            val text = response.body?.string() ?: ""
            response.close()
            // parse and populate dialogIdMap — reuse stripChatIds side-effect
            val result = JSONObject(text)
            val raw = extractResultText(result) ?: return
            stripChatIds(raw) // populates dialogIdMap as side effect
        } catch (_: Exception) {}
    }

    // Strip chat_id from response but save the mapping title->id for later use
    private fun stripChatIds(raw: String): String {
        return try {
            val arr = org.json.JSONArray(raw)
            val result = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj == null) {
                    result.put(arr.get(i))
                } else {
                    val chatId = obj.optLong("chat_id", Long.MIN_VALUE)
                    val title = obj.optString("title", "")
                    if (chatId != Long.MIN_VALUE && title.isNotEmpty()) {
                        dialogIdMap[title.lowercase()] = chatId
                    }
                    obj.remove("chat_id")
                    obj.remove("dialog_id")
                    result.put(obj)
                }
            }
            result.toString()
        } catch (e: Exception) {
            try {
                val obj = JSONObject(raw)
                val chatId = obj.optLong("chat_id", Long.MIN_VALUE)
                val title = obj.optString("title", "")
                if (chatId != Long.MIN_VALUE && title.isNotEmpty()) {
                    dialogIdMap[title.lowercase()] = chatId
                }
                obj.remove("chat_id")
                obj.remove("dialog_id")
                obj.toString()
            } catch (e2: Exception) {
                raw
            }
        }
    }

    // Resolve numeric dialog_id from map if the value looks like a name
    private fun resolveDialogId(arguments: JSONObject): JSONObject {
        val raw = arguments.opt("dialog_id") ?: return arguments
        if (raw is Number) return arguments
        if (raw is String && raw.toLongOrNull() != null) return arguments

        val query = (raw as? String)?.lowercase() ?: return arguments

        // Exact match first
        val resolved = dialogIdMap[query]
            // Partial match: query contains key or key contains query
            ?: dialogIdMap.entries.firstOrNull { (k, _) ->
                query.contains(k) || k.contains(query)
            }?.value
            // Saved Messages aliases → pick first PRIVATE dialog (self-chat)
            ?: if (query.contains("избранн") || query.contains("saved") || query.contains("себ") || query.contains("myself")) {
                // self-chat is stored under the owner's name; pick any PRIVATE we know
                dialogIdMap.values.firstOrNull()
            } else null

        resolved ?: return arguments
        val copy = JSONObject(arguments.toString())
        copy.put("dialog_id", resolved)
        return copy
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
                    inputSchema = t.optJSONObject("inputSchema") ?: JSONObject()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
