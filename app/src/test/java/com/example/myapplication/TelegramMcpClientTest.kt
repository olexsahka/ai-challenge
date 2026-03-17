package com.example.myapplication

import com.example.myapplication.data.mcp.TelegramMcpClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TelegramMcpClient internal helper methods.
 * Covers extractResultText, stripChatIds, and resolveDialogId.
 */
class TelegramMcpClientTest {

    private lateinit var client: TelegramMcpClient

    @Before
    fun setUp() {
        client = TelegramMcpClient(OkHttpClient())
    }

    // ---------------------------------------------------------------------------
    // extractResultText
    // ---------------------------------------------------------------------------

    @Test
    fun `extractResultText with result as JSONArray returns array string`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":[{"chat_id":123,"title":"Test"}]}""")
        val result = client.extractResultText(root)
        assertNotNull(result)
        assertTrue(result!!.contains("123"))
    }

    @Test
    fun `extractResultText with result object with content array returns text`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"[{\"title\":\"T\"}]"}],"isError":false}}""")
        val result = client.extractResultText(root)
        assertNotNull(result)
        assertEquals("""[{"title":"T"}]""", result)
    }

    @Test
    fun `extractResultText with null result returns null`() {
        val root = JSONObject("""{"jsonrpc":"2.0","id":1}""")
        val result = client.extractResultText(root)
        assertNull(result)
    }

    @Test
    fun `extractResultText with empty result object returns null`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{}}""")
        val result = client.extractResultText(root)
        assertNull(result)
    }

    // ---------------------------------------------------------------------------
    // stripChatIds
    // ---------------------------------------------------------------------------

    @Test
    fun `stripChatIds removes chat_id and dialog_id from array items`() {
        val raw = """[{"chat_id":123,"dialog_id":456,"title":"Work","message":"hi"}]"""
        val result = client.stripChatIds(raw)
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
        client.stripChatIds(raw)
        assertEquals(111L, client.dialogIdMap["family"])
        assertEquals(222L, client.dialogIdMap["work"])
    }

    @Test
    fun `stripChatIds handles non-array raw string gracefully`() {
        val raw = """{"chat_id":999,"title":"Solo","text":"hello"}"""
        val result = client.stripChatIds(raw)
        val obj = JSONObject(result)
        assertFalse(obj.has("chat_id"))
        assertFalse(obj.has("dialog_id"))
    }

    @Test
    fun `stripChatIds with plain string returns as-is`() {
        val raw = "plain text, not json"
        val result = client.stripChatIds(raw)
        assertEquals(raw, result)
    }

    // ---------------------------------------------------------------------------
    // resolveDialogId
    // ---------------------------------------------------------------------------

    private fun populateMap() {
        val raw = """[{"chat_id":100,"title":"Family"},{"chat_id":200,"title":"Work Group"},{"chat_id":300,"title":"News"}]"""
        client.stripChatIds(raw)
    }

    @Test
    fun `resolveDialogId string title resolves to Long from map`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"Family","text":"hello"}""")
        val resolved = client.resolveDialogId(args)
        assertEquals(100L, resolved.getLong("dialog_id"))
    }

    @Test
    fun `resolveDialogId Number passes through unchanged`() {
        populateMap()
        val args = JSONObject().apply {
            put("dialog_id", 999L)
            put("text", "hi")
        }
        val resolved = client.resolveDialogId(args)
        assertEquals(999L, resolved.getLong("dialog_id"))
    }

    @Test
    fun `resolveDialogId numeric string passes through unchanged`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"12345","text":"hi"}""")
        val resolved = client.resolveDialogId(args)
        assertEquals("12345", resolved.getString("dialog_id"))
    }

    @Test
    fun `resolveDialogId partial match works`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"work","text":"hello"}""")
        val resolved = client.resolveDialogId(args)
        assertEquals(200L, resolved.getLong("dialog_id"))
    }

    @Test
    fun `resolveDialogId izbrannoye alias picks first value when no exact match`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"избранное","text":"note"}""")
        val resolved = client.resolveDialogId(args)
        assertTrue(resolved.has("dialog_id"))
        val id = resolved.opt("dialog_id")
        assertTrue("Should be a Long, got $id", id is Long)
    }

    @Test
    fun `resolveDialogId no dialog_id key returns unchanged`() {
        val args = JSONObject("""{"text":"no dialog id here"}""")
        val resolved = client.resolveDialogId(args)
        assertFalse(resolved.has("dialog_id"))
    }

    @Test
    fun `resolveDialogId unresolvable name returns original unchanged`() {
        populateMap()
        val args = JSONObject("""{"dialog_id":"completely_unknown_xyz","text":"hi"}""")
        val resolved = client.resolveDialogId(args)
        assertEquals("completely_unknown_xyz", resolved.getString("dialog_id"))
    }
}
