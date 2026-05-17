package com.example.myapplication

import com.example.myapplication.data.composition.BtcTrackingController
import com.example.myapplication.data.composition.BtcTrackingMcpProvider
import com.example.myapplication.data.mcp.McpConnectionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BtcTrackingMcpProviderTest {

    private lateinit var provider: BtcTrackingMcpProvider
    private var capturedPrompt: String? = null

    private val fakeController = object : BtcTrackingController {
        override fun startBtcTracking(userPrompt: String) { capturedPrompt = userPrompt }
        override fun unbind() {}
    }

    @Before
    fun setUp() {
        provider = BtcTrackingMcpProvider()
        capturedPrompt = null
    }

    @Test
    fun `connect returns Connected with start_btc_tracking tool`() = runTest {
        val status = provider.connect()
        assertTrue(status is McpConnectionStatus.Connected)
        val tools = (status as McpConnectionStatus.Connected).tools
        assertEquals(1, tools.size)
        assertEquals("start_btc_tracking", tools[0].name)
    }

    @Test
    fun `callTool without bound controller returns error`() = runTest {
        val result = provider.callTool("start_btc_tracking", """{"user_request":"test"}""")
        assertTrue(result.startsWith("Error: controller not bound"))
    }

    @Test
    fun `callTool with valid request starts tracking and returns confirmation`() = runTest {
        provider.bind(fakeController)
        val result = provider.callTool("start_btc_tracking", """{"user_request":"track BTC"}""")
        assertEquals("track BTC", capturedPrompt)
        assertTrue(result.contains("запущен") || result.contains("ПОДПИСКА"))
    }

    @Test
    fun `callTool with blank user_request returns error`() = runTest {
        provider.bind(fakeController)
        val result = provider.callTool("start_btc_tracking", """{"user_request":""}""")
        assertTrue(result.startsWith("Error: user_request is required"))
    }

    @Test
    fun `callTool with unknown tool returns error`() = runTest {
        provider.bind(fakeController)
        val result = provider.callTool("unknown_tool", "{}")
        assertTrue(result.startsWith("Error: unknown tool"))
    }

    @Test
    fun `callTool with invalid JSON returns error`() = runTest {
        provider.bind(fakeController)
        val result = provider.callTool("start_btc_tracking", "not-json")
        // Should handle gracefully — either error or treat as blank
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `unbind clears controller so subsequent callTool returns error`() = runTest {
        provider.bind(fakeController)
        provider.unbind()
        val result = provider.callTool("start_btc_tracking", """{"user_request":"test"}""")
        assertTrue(result.startsWith("Error: controller not bound"))
    }

    @Test
    fun `isEnabled and isConnected always return true`() {
        assertTrue(provider.isEnabled)
        assertTrue(provider.isConnected)
    }
}
