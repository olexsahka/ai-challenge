package com.example.myapplication

import com.example.myapplication.agent.AgentMemory
import com.example.myapplication.agent.AgentRunner
import com.example.myapplication.agent.AgentStep
import com.example.myapplication.agent.AgentStepType
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for AgentRunner ReAct loop.
 * Фаза 0 — baseline тесты (пункт 0.1).
 *
 * AgentRunner не имеет Android-зависимостей — он готов к переносу в shared.
 * AgentMemory мокируется через Mockito (Context-зависимость изолируется),
 * что дополнительно демонстрирует необходимость KeyValueStorage интерфейса (Фаза 2).
 */
class AgentRunnerTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeMemory(entries: Map<String, String> = emptyMap()): AgentMemory {
        val storage = FakeKeyValueStorage()
        entries.forEach { (k, v) -> storage.putString(k, v) }
        return AgentMemory(storage)
    }

    private fun makeRunner(
        responses: List<ChatResponse>,
        memory: AgentMemory = makeMemory()
    ): Pair<AgentRunner, MutableList<AgentStep>> {
        val api = FakeAnthropicApi(responses)
        val steps = mutableListOf<AgentStep>()
        return AgentRunner(api, memory) to steps
    }

    // ---------------------------------------------------------------------------
    // FINAL_ANSWER
    // ---------------------------------------------------------------------------

    @Test
    fun `FINAL_ANSWER stops loop immediately`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(agentResponse(thought = "I know", action = "FINAL_ANSWER", input = "42"))
        )
        runner.run("What is 6*7?") { steps.add(it) }

        val finalIndex = steps.indexOfFirst { it.type == AgentStepType.FINAL_ANSWER }
        assertTrue("FINAL_ANSWER step not found", finalIndex >= 0)
        val stepsAfter = steps.drop(finalIndex + 1)
        assertTrue("Expected no steps after FINAL_ANSWER, got: $stepsAfter", stepsAfter.isEmpty())
    }

    @Test
    fun `FINAL_ANSWER content matches INPUT line`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(agentResponse(action = "FINAL_ANSWER", input = "The answer is Paris"))
        )
        runner.run("Capital of France?") { steps.add(it) }

        val final = steps.find { it.type == AgentStepType.FINAL_ANSWER }
        assertNotNull(final)
        assertEquals("The answer is Paris", final!!.content)
    }

    // ---------------------------------------------------------------------------
    // THOUGHT
    // ---------------------------------------------------------------------------

    @Test
    fun `THOUGHT step is emitted when present`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(agentResponse(thought = "Let me think", action = "FINAL_ANSWER", input = "done"))
        )
        runner.run("task") { steps.add(it) }

        val thought = steps.find { it.type == AgentStepType.THOUGHT }
        assertNotNull(thought)
        assertEquals("Let me think", thought!!.content)
    }

    @Test
    fun `THOUGHT appears before FINAL_ANSWER`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(agentResponse(thought = "reasoning", action = "FINAL_ANSWER", input = "answer"))
        )
        runner.run("task") { steps.add(it) }

        val thoughtIdx = steps.indexOfFirst { it.type == AgentStepType.THOUGHT }
        val finalIdx = steps.indexOfFirst { it.type == AgentStepType.FINAL_ANSWER }
        assertTrue(thoughtIdx >= 0 && finalIdx >= 0 && thoughtIdx < finalIdx)
    }

    @Test
    fun `response without THOUGHT still processes ACTION`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(agentResponse(thought = null, action = "FINAL_ANSWER", input = "no thought"))
        )
        runner.run("task") { steps.add(it) }

        assertNull(steps.find { it.type == AgentStepType.THOUGHT })
        assertNotNull(steps.find { it.type == AgentStepType.FINAL_ANSWER })
    }

    // ---------------------------------------------------------------------------
    // STORE_MEMORY
    // ---------------------------------------------------------------------------

    @Test
    fun `STORE_MEMORY calls memory store with correct key and value`() = runTest {
        val storage = FakeKeyValueStorage()
        val memory = AgentMemory(storage)
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "STORE_MEMORY", input = "user_name=Bob"),
                agentResponse(action = "FINAL_ANSWER", input = "stored")
            ),
            memory
        )
        runner.run("Remember Bob") { steps.add(it) }

        assertEquals("Bob", storage.getString("user_name"))
    }

    @Test
    fun `STORE_MEMORY observation confirms storage`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "STORE_MEMORY", input = "lang=Kotlin"),
                agentResponse(action = "FINAL_ANSWER", input = "Done")
            )
        )
        runner.run("store lang") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("Stored") })
    }

    @Test
    fun `STORE_MEMORY with malformed input returns error observation`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "STORE_MEMORY", input = "no_equals_sign"),
                agentResponse(action = "FINAL_ANSWER", input = "Done")
            )
        )
        runner.run("bad store") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("Error") })
    }

    // ---------------------------------------------------------------------------
    // SEARCH_MEMORY
    // ---------------------------------------------------------------------------

    @Test
    fun `SEARCH_MEMORY retrieves stored value`() = runTest {
        val memory = makeMemory(mapOf("project" to "MyApplication"))
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "SEARCH_MEMORY", input = "project"),
                agentResponse(action = "FINAL_ANSWER", input = "MyApplication")
            ),
            memory
        )
        runner.run("project name?") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("MyApplication") })
    }

    @Test
    fun `SEARCH_MEMORY for unknown key returns no memory found`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "SEARCH_MEMORY", input = "missing_key"),
                agentResponse(action = "FINAL_ANSWER", input = "Not found")
            )
        )
        runner.run("search") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("No memory found") })
    }

    // ---------------------------------------------------------------------------
    // CALCULATE
    // ---------------------------------------------------------------------------

    @Test
    fun `CALCULATE returns correct result for addition`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "CALCULATE", input = "2 + 3"),
                agentResponse(action = "FINAL_ANSWER", input = "5.0")
            )
        )
        runner.run("2+3") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("5.0") })
    }

    @Test
    fun `CALCULATE returns correct result for multiplication`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "CALCULATE", input = "6 * 7"),
                agentResponse(action = "FINAL_ANSWER", input = "42.0")
            )
        )
        runner.run("6*7") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("42.0") })
    }

    @Test
    fun `CALCULATE handles parentheses`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "CALCULATE", input = "(2 + 3) * 4"),
                agentResponse(action = "FINAL_ANSWER", input = "20.0")
            )
        )
        runner.run("(2+3)*4") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("20.0") })
    }

    @Test
    fun `CALCULATE subtraction and division`() = runTest {
        val (runner, steps) = makeRunner(
            listOf(
                agentResponse(action = "CALCULATE", input = "10 / 2 - 1"),
                agentResponse(action = "FINAL_ANSWER", input = "4.0")
            )
        )
        runner.run("10/2-1") { steps.add(it) }

        val observations = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(observations.any { it.content.contains("4.0") })
    }

    // ---------------------------------------------------------------------------
    // Max iterations
    // ---------------------------------------------------------------------------

    @Test
    fun `loop stops after at most 6 iterations`() = runTest {
        val infiniteResponses = (1..20).map {
            agentResponse(thought = "thinking $it", action = "CALCULATE", input = "1+1")
        }
        val (runner, steps) = makeRunner(infiniteResponses)
        runner.run("loop forever") { steps.add(it) }

        val actionSteps = steps.filter { it.type == AgentStepType.ACTION }
        assertTrue("Expected <= 6 ACTION steps, got ${actionSteps.size}", actionSteps.size <= 6)
    }

    // ---------------------------------------------------------------------------
    // Error handling
    // ---------------------------------------------------------------------------

    @Test
    fun `API error emits OBSERVATION with error message`() = runTest {
        val api = ThrowingAnthropicApi(RuntimeException("network error"))
        val memory = makeMemory()
        val steps = mutableListOf<AgentStep>()

        AgentRunner(api, memory).run("task") { steps.add(it) }

        val obs = steps.filter { it.type == AgentStepType.OBSERVATION }
        assertTrue(obs.any { it.content.contains("API error") })
    }

    @Test
    fun `API error stops the loop`() = runTest {
        val api = ThrowingAnthropicApi(RuntimeException("timeout"))
        val memory = makeMemory()
        val steps = mutableListOf<AgentStep>()

        AgentRunner(api, memory).run("task") { steps.add(it) }

        // Only 1 observation (the error), no further steps
        assertEquals(1, steps.size)
    }
}

// ---------------------------------------------------------------------------
// Shared test infrastructure (used by AgentRunnerTest and LLMAgentTest)
// ---------------------------------------------------------------------------

fun agentResponse(
    thought: String? = null,
    action: String,
    input: String
): ChatResponse {
    val lines = buildString {
        if (thought != null) appendLine("THOUGHT: $thought")
        appendLine("ACTION: $action")
        append("INPUT: $input")
    }
    return ChatResponse(
        id = "fake-id",
        output = listOf(
            OutputItem(
                type = "message",
                content = listOf(OutputContent(type = "output_text", text = lines))
            )
        ),
        usage = UsageInfo(10, 20)
    )
}

fun assistantResponse(text: String, inputTokens: Int = 10, outputTokens: Int = 20): ChatResponse =
    ChatResponse(
        id = "fake-id",
        output = listOf(
            OutputItem(
                type = "message",
                content = listOf(OutputContent(type = "output_text", text = text))
            )
        ),
        usage = UsageInfo(inputTokens, outputTokens)
    )

class FakeAnthropicApi(private val responses: List<ChatResponse>)  : LLMApiClient {
    private var callCount = 0
    val capturedRequests = mutableListOf<ChatRequest>()

    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        capturedRequests.add(request)
        return responses.getOrElse(callCount) { responses.last() }.also { callCount++ }
    }

    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}

class ThrowingAnthropicApi(private val ex: Exception)  : LLMApiClient {
    override suspend fun sendMessage(request: ChatRequest): ChatResponse = throw ex
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}
