package com.example.myapplication

import com.example.myapplication.data.mcp.StatelessMcpClient
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StatelessMcpClientTest {

    private lateinit var client: StatelessMcpClient

    @Before
    fun setUp() {
        client = StatelessMcpClient(OkHttpClient(), "http://unused:8081/mcp")
    }

    @Test
    fun `extractResultText with content array returns text of first element`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"search result"}],"isError":false}}""")
        val result = client.extractResultText(root)
        assertEquals("search result", result)
    }

    @Test
    fun `extractResultText with empty content array returns toString of result object`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{"content":[],"status":"ok"}}""")
        val result = client.extractResultText(root)
        assertNotNull(result)
        assertTrue(result!!.contains("status"))
    }

    @Test
    fun `extractResultText with result as JSONArray returns array toString`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":[{"id":1,"name":"task"}]}""")
        val result = client.extractResultText(root)
        assertNotNull(result)
        assertTrue(result!!.contains("task"))
    }

    @Test
    fun `extractResultText without result field returns null`() {
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

    @Test
    fun `extractResultText with content array with empty text falls back to resultObj toString`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{"content":[{"type":"text","text":""}],"extra":"data"}}""")
        val result = client.extractResultText(root)
        assertNotNull(result)
        assertTrue(result!!.contains("extra"))
    }

    @Test
    fun `callTool when not connected returns error message`() {
        // Client is freshly created and not connected
        val notConnectedClient = StatelessMcpClient(OkHttpClient(), "http://unused:8081/mcp")
        // Use a blocking runBlocking to call suspend function
        val result = kotlinx.coroutines.runBlocking {
            notConnectedClient.callTool("some_tool", JSONObject())
        }
        assertEquals("Error: not connected", result)
    }

    @Test
    fun `extractResultText with result object having no content uses toString fallback`() {
        val root = JSONObject("""{"jsonrpc":"2.0","result":{"status":"saved","id":"abc123"}}""")
        val result = client.extractResultText(root)
        assertNotNull(result)
        assertTrue(result!!.contains("saved"))
    }
}
