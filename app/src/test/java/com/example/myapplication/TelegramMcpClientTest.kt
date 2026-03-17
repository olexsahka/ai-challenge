package com.example.myapplication

import com.example.myapplication.data.mcp.TelegramMcpClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Unit tests for TelegramMcpClient private helper methods accessed via reflection.
 * Covers extractResultText, stripChatIds, and resolveDialogId.
 */
class TelegramMcpClientTest {

    private lateinit var client: TelegramMcpClient
    private lateinit var extractResultText: Method
    private lateinit var stripChatIds: Method
    private lateinit var resolveDialogId: Method
    private lateinit var dialogIdMap: java.lang.reflect.Field

    @Before
    fun setUp() {
        client = TelegramMcpClient(OkHttpClient())

        extractResultText = TelegramMcpClient::class.java
            .getDeclaredMethod("extractResultText", JSONObject::class.java)
            .also { it.isAccessible = true }

        stripChatIds = TelegramMcpClient::class.java
            .getDeclaredMethod("stripChatIds", String::class.java)
            .also { it.isAccessible = true }

        resolveDialogId = TelegramMcpClient::class.java
            .getDeclaredMethod("resolveDialogId", JSONObject::class.java)
            .also { it.isAccessible = true }

        dialogIdMap = TelegramMcpClient::class.java
            .getDeclaredField("dialogIdMap")
            .also { it.isAccessible = true }
    }

    // ---------------------------------------------------------------------------
    // extractResultText
    // ---------------------------------------------------------------------------

    @Test
    fun `extractResultText with result as JSONArray returns array string`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":[{"chat_id":123,"title":"Test"}]}""")
        val result = extractResultText.invoke(client, root) as String?
        assertNotNull(result)
        assertTrue(result!!.contains("chat_id") || result.contains("123"))
    }

    @Test
    fun `extractResultText with result object with content array returns text`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"[{\"title\":\"T\"}]"}],"isError":false}}""")
        val result = extractResultText.invoke(client, root) as String?
        assertNotNull(result)
        assertEquals("""[{"title":"T"}]""", result)
    }

    @Test
    fun `extractResultText with null result returns null`() {
        val root = JSONObject("""{"jsonrpc":"2.0","id":1}""")
        val result = extractResultText.invoke(client, root) as String?
        assertNull(result)
    }

    @Test
    fun `extractResultText with empty result object returns null`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{}}""")
        val result = extractResultText.invoke(client, root) as String?
        assertNull(result)
    }

    // ---------------------------------------------------------------------------
    // stripChatIds
    // ---------------------------------------------------------------------------

    @Test
    fun `stripChatIds removes chat_id and dialog_id from array items`() {
        val raw = """[{"chat_id":123,"dialog_id":456,"title":"Work","message":"hi"}]"""
        val result = stripChatIds.invoke(client, raw) as String
        val parsed = org.json.JSONArray(result)
        val item = parsed.getJSONObject(0)
        assertFalse("chat_id should be removed", item.has("chat_id"))
        assertFalse("dialog_id should be removed", item.has("dialog_id"))
        assertEquals("Work", item.getString("title"))
        assertEquals("hi", item.getString("message"))
    }

    @Test
    fun `stripChatIds populates dialogIdMap with title-to-id mapping`() {
        val raw = """[{"chat_id":111,"title":"Family"},{"chat_id":222,"title":"Work"}]"""
        stripChatIds.invoke(client, raw)

        @Suppress("UNCHECKED_CAST")
        val map = dialogIdMap.get(client) as Map<String, Long>
        assertEquals(111L, map["family"])
        assertEquals(222L, map["work"])
    }

    @Test
    fun `stripChatIds handles non-array raw string gracefully`() {
        val raw = """{"chat_id":999,"title":"Solo","text":"hello"}"""
        val result = stripChatIds.invoke(client, raw) as String
        val obj = JSONObject(result)
        assertFalse(obj.has("chat_id"))
        assertFalse(obj.has("dialog_id"))
    }

    @Test
    fun `stripChatIds with plain string returns as-is`() {
        val raw = "plain text, not json"
        val result = stripChatIds.invoke(client, raw) as String
        assertEquals(raw, result)
    }

    // ---------------------------------------------------------------------------
    // resolveDialogId
    // ---------------------------------------------------------------------------

    private fun populateMap() {
        // Populate dialogIdMap via stripChatIds side-effect
        val raw = """[{"chat_id":100,"title":"Family"},{"chat_id":200,"title":"Work Group"},{"chat_id":300,"title":"News"}]"""
        stripChatIds.invoke(client, raw)
    }

    @Test
    fun `resolveDialogId string title resolves to Long from map`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"Family","text":"hello"}""")
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        assertEquals(100L, resolved.getLong("dialog_id"))
    }

    @Test
    fun `resolveDialogId Number passes through unchanged`() {
        populateMap()
        val args = JSONObject().apply {
            put("dialog_id", 999L)
            put("text", "hi")
        }
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        assertEquals(999L, resolved.getLong("dialog_id"))
    }

    @Test
    fun `resolveDialogId numeric string passes through unchanged`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"12345","text":"hi"}""")
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        assertEquals("12345", resolved.getString("dialog_id"))
    }

    @Test
    fun `resolveDialogId partial match works`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"work","text":"hello"}""")
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        assertEquals(200L, resolved.getLong("dialog_id"))
    }

    @Test
    fun `resolveDialogId izbrannoye alias picks first value when no exact match`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"избранное","text":"note"}""")
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        // Should resolve to some value from map (first available)
        assertTrue(resolved.has("dialog_id"))
        val id = resolved.opt("dialog_id")
        assertTrue("Should be a Long, got $id", id is Long)
    }

    @Test
    fun `resolveDialogId no dialog_id key returns unchanged`() {
        val args = JSONObject("""{"text":"no dialog id here"}""")
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        assertFalse(resolved.has("dialog_id"))
    }

    @Test
    fun `resolveDialogId unresolvable name returns original unchanged`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"completely_unknown_xyz","text":"hi"}""")
        val resolved = resolveDialogId.invoke(client, args) as JSONObject
        assertEquals("completely_unknown_xyz", resolved.getString("dialog_id"))
    }
}
