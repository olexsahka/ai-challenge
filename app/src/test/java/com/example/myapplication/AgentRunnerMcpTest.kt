package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.AgentStep
import com.example.myapplication.agent.AgentStepType
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.mcp.McpConnectionStatus
import com.example.myapplication.data.mcp.McpRepository
import com.example.myapplication.data.mcp.McpTool
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for AgentRunner MCP integration and fallback behaviour.
 *
 * Uses FakeMcpRepository to avoid suspend-mock complexity and Android Context dependency.
 */
class AgentRunnerMcpTest {

    // ---------------------------------------------------------------------------
    // Fake McpRepository — avoids Context and suspend-mock issues
    // ---------------------------------------------------------------------------

    private class FakeMcpRepository(
        override var vkusVillEnabled: Boolean = true,
        private val connectStatus: McpConnectionStatus = McpConnectionStatus.Disconnected,
        private val toolResult: String = "tool result",
        private val toolError: Exception? = null
    ) : McpRepository(null, null) {

        val toolCalls = mutableListOf<Pair<String, JSONObject>>()
        var connectCalled = false

        override suspend fun connect(): McpConnectionStatus {
            connectCalled = true
            return connectStatus
        }

        override val isConnected: Boolean
            get() = connectStatus is McpConnectionStatus.Connected

        override suspend fun callTool(toolName: String, arguments: JSONObject): String {
            toolCalls.add(toolName to arguments)
            if (toolError != null) throw toolError
            return toolResult
        }

        override fun disconnect() {}
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeTool(
        name: String,
        description: String = "A tool",
        schemaJson: String = """{"type":"object","properties":{"q":{"type":"string"}}}"""
    ) = McpTool(name, description, JSONObject(schemaJson))

    private fun makeMemory(): AgentMemory {
        val m = mock<AgentMemory>()
        whenever(m.toContextString()).thenReturn("")
        whenever(m.recall(any())).thenReturn(null)
        return m
    }

    private suspend fun run(
        responses: List<ChatResponse>,
        mcpRepo: McpRepository? = null,
        memory: AgentMemory = makeMemory()
    ): Pair<List<AgentStep>, FakeAnthropicApi> {
        val api = FakeAnthropicApi(responses)
        val steps = mutableListOf<AgentStep>()
        AgentRunner(api, memory, mcpRepo).run("test task") { steps.add(it) }
        return steps to api
    }

    // ---------------------------------------------------------------------------
    // Plain-text response (no ReAct format) → FINAL_ANSWER
    // ---------------------------------------------------------------------------

    @Test
    fun `plain text response without ReAct format emits FINAL_ANSWER`() = runTest {
        val (steps, _) = run(listOf(assistantResponse("Вот ответ на ваш вопрос.")))

        val finalStep = steps.find { it.type == AgentStepType.FINAL_ANSWER }
        assertNotNull("Expected FINAL_ANSWER step", finalStep)
        assertEquals("Вот ответ на ваш вопрос.", finalStep!!.content)
    }

    @Test
    fun `plain text response stops loop after one iteration`() = runTest {
        val (steps, _) = run(
            listOf(
                assistantResponse("Direct answer"),
                agentResponse(action = "FINAL_ANSWER", input = "should not reach")
            )
        )

        val finalSteps = steps.filter { it.type == AgentStepType.FINAL_ANSWER }
        assertEquals(1, finalSteps.size)
        assertEquals("Direct answer", finalSteps[0].content)
    }

    @Test
    fun `plain text response does not emit ACTION or OBSERVATION`() = runTest {
        val (steps, _) = run(listOf(assistantResponse("plain answer")))

        assertTrue(steps.none { it.type == AgentStepType.ACTION })
        assertTrue(steps.none { it.type == AgentStepType.OBSERVATION })
    }

    // ---------------------------------------------------------------------------
    // MCP disabled → unknown action returns error
    // ---------------------------------------------------------------------------

    @Test
    fun `when MCP disabled unknown action returns error observation`() = runTest {
        val repo = FakeMcpRepository(vkusVillEnabled = false)
        val (steps, _) = run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = """{"q":"milk"}"""),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("Unknown action") })
    }

    @Test
    fun `when MCP disabled connect is never called`() = runTest {
        val repo = FakeMcpRepository(vkusVillEnabled = false)
        run(
            listOf(agentResponse(action = "FINAL_ANSWER", input = "ok")),
            mcpRepo = repo
        )

        assertFalse(repo.connectCalled)
    }

    // ---------------------------------------------------------------------------
    // MCP connect returns error → agent runs without tools
    // ---------------------------------------------------------------------------

    @Test
    fun `when MCP connect returns error agent still completes`() = runTest {
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Error("timeout")
        )

        val (steps, _) = run(
            listOf(agentResponse(action = "FINAL_ANSWER", input = "fallback answer")),
            mcpRepo = repo
        )

        val finalStep = steps.find { it.type == AgentStepType.FINAL_ANSWER }
        assertNotNull(finalStep)
        assertEquals("fallback answer", finalStep!!.content)
    }

    // ---------------------------------------------------------------------------
    // MCP enabled and connected → tool executed
    // ---------------------------------------------------------------------------

    @Test
    fun `MCP tool action calls callTool`() = runTest {
        val tool = makeTool("vkusvill_products_search")
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool))
        )

        run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = """{"q":"milk"}"""),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        assertEquals(1, repo.toolCalls.size)
        assertEquals("vkusvill_products_search", repo.toolCalls[0].first)
    }

    @Test
    fun `MCP tool result appears as OBSERVATION`() = runTest {
        val tool = makeTool("vkusvill_products_search")
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool)),
            toolResult = "Found: Молоко 3.2%"
        )

        val (steps, _) = run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = """{"q":"молоко"}"""),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("Found: Молоко 3.2%") })
    }

    @Test
    fun `MCP tool exception produces error OBSERVATION`() = runTest {
        val tool = makeTool("vkusvill_products_search")
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool)),
            toolError = RuntimeException("network error")
        )

        val (steps, _) = run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = """{"q":"молоко"}"""),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("Error") })
    }

    @Test
    fun `MCP tool called with correct JSON arguments`() = runTest {
        val tool = makeTool(
            "vkusvill_products_search", "Search",
            """{"type":"object","properties":{"q":{"type":"string"},"page":{"type":"integer"}}}"""
        )
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool))
        )

        run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = """{"q":"хлеб","page":2}"""),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        assertEquals(1, repo.toolCalls.size)
        val args = repo.toolCalls[0].second
        assertEquals("хлеб", args.getString("q"))
        assertEquals(2, args.getInt("page"))
    }

    // ---------------------------------------------------------------------------
    // Invalid JSON input for MCP tool → error observation
    // ---------------------------------------------------------------------------

    @Test
    fun `MCP action with invalid JSON input returns error observation`() = runTest {
        val tool = makeTool("vkusvill_products_search")
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool))
        )

        val (steps, _) = run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = "not valid json"),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("Error") })
        // callTool should NOT have been called
        assertTrue(repo.toolCalls.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // System prompt contains MCP tool names when enabled
    // ---------------------------------------------------------------------------

    @Test
    fun `system prompt includes MCP tool names when connected`() = runTest {
        val tools = listOf(
            makeTool("vkusvill_products_search"),
            makeTool("vkusvill_cart_link_create")
        )
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(tools)
        )

        val (_, api) = run(
            listOf(agentResponse(action = "FINAL_ANSWER", input = "ok")),
            mcpRepo = repo
        )

        val instructions = api.capturedRequests.first().instructions ?: ""
        assertTrue(instructions.contains("vkusvill_products_search"))
        assertTrue(instructions.contains("vkusvill_cart_link_create"))
    }

    @Test
    fun `system prompt has no MCP tool names when disabled`() = runTest {
        val repo = FakeMcpRepository(vkusVillEnabled = false)

        val (_, api) = run(
            listOf(agentResponse(action = "FINAL_ANSWER", input = "ok")),
            mcpRepo = repo
        )

        val instructions = api.capturedRequests.first().instructions ?: ""
        assertFalse(instructions.contains("vkusvill_products_search"))
    }

    // ---------------------------------------------------------------------------
    // Multiple MCP tool calls in one session
    // ---------------------------------------------------------------------------

    @Test
    fun `multiple MCP tool calls in sequence each get observation`() = runTest {
        val tool = makeTool("vkusvill_products_search")
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool)),
            toolResult = "result"
        )

        val (steps, _) = run(
            listOf(
                agentResponse(action = "vkusvill_products_search", input = """{"q":"молоко"}"""),
                agentResponse(action = "vkusvill_products_search", input = """{"q":"хлеб"}"""),
                agentResponse(action = "FINAL_ANSWER", input = "done")
            ),
            mcpRepo = repo
        )

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertEquals(2, observations.size)
        assertEquals(2, repo.toolCalls.size)
    }

    @Test
    fun `MCP tool description injected into system prompt`() = runTest {
        val tool = makeTool("vkusvill_products_search", "Поиск товаров ВкусВилл")
        val repo = FakeMcpRepository(
            vkusVillEnabled = true,
            connectStatus = McpConnectionStatus.Connected(listOf(tool))
        )

        val (_, api) = run(
            listOf(agentResponse(action = "FINAL_ANSWER", input = "ok")),
            mcpRepo = repo
        )

        val instructions = api.capturedRequests.first().instructions ?: ""
        assertTrue(instructions.contains("Поиск товаров ВкусВилл"))
    }
}
