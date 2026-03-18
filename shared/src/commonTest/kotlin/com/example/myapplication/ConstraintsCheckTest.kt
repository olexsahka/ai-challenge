package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.data.repository.ConstraintsRepository
import com.example.myapplication.data.repository.TaskMemory
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConstraintsCheckTest {

    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var factRepo: FakeFactRepository
    private lateinit var branchNodeRepo: FakeBranchNodeRepository
    private lateinit var fsmRepo: FakeTaskFsmRepository

    @BeforeTest
    fun setup() {
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        summaryRepo = FakeSummaryRepository()
        factRepo = FakeFactRepository()
        branchNodeRepo = FakeBranchNodeRepository()
        fsmRepo = FakeTaskFsmRepository()
    }

    private class SequentialApi(private val responses: List<ChatResponse>) : LLMApiClient {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0
        override suspend fun sendMessage(request: ChatRequest): ChatResponse {
            requests.add(request)
            return responses[index++]
        }
        override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
    }

    private fun textResponse(text: String) = ChatResponse(
        id = "r",
        output = listOf(OutputItem(type = "message", content = listOf(OutputContent(type = "output_text", text = text)))),
        usage = UsageInfo(input_tokens = 10, output_tokens = 5)
    )

    private fun planWith(stepCount: Int): String =
        "Plan:\n" + (1..stepCount).joinToString("\n") { "$it. Step $it" }

    private fun makeAgent(
        api: LLMApiClient,
        constraintRules: String = "no code generation",
        constraintsEnabled: Boolean = true
    ): LLMAgent {
        val profile = makeFakeUserProfile()
        profile.taskMemory = TaskMemory(name = "task", description = "", enabled = true)

        val constraintsRepo = makeFakeConstraintsRepository(enabled = constraintsEnabled, rules = constraintRules)

        return LLMAgent(
            api = api,
            sessionRepo = sessionRepo,
            messageRepo = messageRepo,
            memory = makeFakeMemory(),
            summaryRepo = summaryRepo,
            factRepo = factRepo,
            branchNodeRepo = branchNodeRepo,
            userProfileRepository = profile,
            taskFsmRepository = fsmRepo,
            constraintsRepository = constraintsRepo,
            clock = FakeClock(),
            uuidGenerator = FakeUuidGenerator(),
            dateFormatter = FakeDateFormatter()
        )
    }

    private fun setupSession() = sessionOf(id = "s1").also {
        sessionRepo.sessions["s1"] = it
        fsmRepo.states["s1"] = TaskFsmState(sessionId = "s1")
    }

    // -------------------------------------------------------------------------
    // Pre-check: user request violates constraint
    // -------------------------------------------------------------------------

    @Test
    fun `pre-check violation - FSM set to ERROR and no plan API call made`() = runTest {
        setupSession()

        val api = SequentialApi(listOf(
            textResponse("VIOLATION: no code generation"),
            textResponse("Explain the algorithm concept without writing code")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "write me a sorting algorithm")

        assertTrue(result.isSuccess)
        assertEquals(2, api.requests.size)
        val fsm = fsmRepo.get("s1")!!
        assertEquals(TaskStage.ERROR, fsm.stage)
    }

    @Test
    fun `pre-check violation - response contains Russian error message`() = runTest {
        setupSession()

        val api = SequentialApi(listOf(
            textResponse("VIOLATION: no code generation"),
            textResponse("Try asking for a high-level explanation instead")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "write me a sorting algorithm")
        val content = result.getOrNull()?.content ?: ""

        assertTrue(content.contains("❌ Ошибка"), "Should contain Russian error prefix")
        assertTrue(content.contains("no code generation"), "Should mention constraint")
        assertTrue(content.contains("Варианты:"), "Should list options")
    }

    @Test
    fun `pre-check violation - alternative suggestion included in response`() = runTest {
        setupSession()

        val alternative = "Explain how bubble sort works conceptually without writing code"
        val api = SequentialApi(listOf(
            textResponse("VIOLATION: no code generation"),
            textResponse(alternative)
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "write me a sorting algorithm")
        val content = result.getOrNull()?.content ?: ""

        assertTrue(content.contains(alternative), "Should include alternative suggestion")
    }

    // -------------------------------------------------------------------------
    // Pre-check: user request is OK
    // -------------------------------------------------------------------------

    @Test
    fun `pre-check OK - FSM proceeds to planning normally`() = runTest {
        setupSession()

        val api = SequentialApi(listOf(
            textResponse("OK"),
            textResponse(planWith(2)),
            textResponse("OK")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "explain how sorting works")
        assertTrue(result.isSuccess)

        assertEquals(3, api.requests.size)
        val fsm = fsmRepo.get("s1")!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // Post-check: planning response violates constraint
    // -------------------------------------------------------------------------

    @Test
    fun `post-check planning violation - FSM set to ERROR after plan saved`() = runTest {
        setupSession()

        val api = SequentialApi(listOf(
            textResponse("OK"),
            textResponse("1. Write code\n2. Run it"),
            textResponse("VIOLATION: no code generation"),
            textResponse("Try a conceptual walkthrough")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "explain sorting")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get("s1")!!
        assertEquals(TaskStage.ERROR, fsm.stage)

        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("❌ Ошибка"))
    }

    // -------------------------------------------------------------------------
    // Post-check: execution step response violates constraint (autoRun)
    // -------------------------------------------------------------------------

    @Test
    fun `post-check execution step violation in autoRun - FSM set to ERROR`() = runTest {
        val session = setupSession()
        fsmRepo.states["s1"] = TaskFsmState(
            sessionId = "s1",
            stage = TaskStage.EXECUTION,
            step = 1, stepCount = 2,
            expectedAction = "execute_step",
            autoRun = true
        )
        messageRepo.messages.add(makeUserMessage(1, "s1"))

        val api = SequentialApi(listOf(
            textResponse("Here is some code: fun main() {}"),
            textResponse("VIOLATION: no code generation"),
            textResponse("Describe the algorithm instead")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "да")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get("s1")!!
        assertEquals(TaskStage.ERROR, fsm.stage)
        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("❌ Ошибка"))
    }

    // -------------------------------------------------------------------------
    // Post-check: execution step violation in manual mode
    // -------------------------------------------------------------------------

    @Test
    fun `post-check execution step violation in manual mode - FSM set to ERROR`() = runTest {
        setupSession()
        fsmRepo.states["s1"] = TaskFsmState(
            sessionId = "s1",
            stage = TaskStage.EXECUTION,
            step = 1, stepCount = 2,
            expectedAction = "execute_step",
            autoRun = false
        )
        messageRepo.messages.add(makeUserMessage(1, "s1"))

        val api = SequentialApi(listOf(
            textResponse("fun hello() = println(\"hi\")"),
            textResponse("VIOLATION: no code generation"),
            textResponse("Explain the concept instead")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage("s1", "да")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get("s1")!!
        assertEquals(TaskStage.ERROR, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // Disabled constraints — no check performed
    // -------------------------------------------------------------------------

    @Test
    fun `disabled constraints - no constraint check API call made`() = runTest {
        setupSession()

        val api = SequentialApi(listOf(
            textResponse(planWith(1))
        ))
        val agent = makeAgent(api, constraintsEnabled = false)

        val result = agent.sendMessage("s1", "write code for me")
        assertTrue(result.isSuccess)

        assertEquals(1, api.requests.size)
        val fsm = fsmRepo.get("s1")!!
        assertNotEquals(TaskStage.ERROR, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // TaskFsmRepository.toInstructionsBlock with constraints
    // -------------------------------------------------------------------------

    @Test
    fun `toInstructionsBlock without constraints has no constraint section`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = FakeTaskFsmRepository().toInstructionsBlock(fsm, null, null, false, null, false)
        assertFalse(result.contains("Agent constraints"))
    }

    @Test
    fun `toInstructionsBlock with disabled constraints has no constraint section`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = FakeTaskFsmRepository().toInstructionsBlock(fsm, null, null, false, "no code", false)
        assertFalse(result.contains("Agent constraints"))
    }

    @Test
    fun `toInstructionsBlock with enabled constraints includes rules and check instructions`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = FakeTaskFsmRepository().toInstructionsBlock(fsm, null, null, false, "no code generation\nreply in English only", true)
        assertTrue(result.contains("Agent constraints (MUST NEVER violate):"))
        assertTrue(result.contains("no code generation"))
        assertTrue(result.contains("reply in English only"))
        assertTrue(result.contains("Constraint check rules:"))
        assertTrue(result.contains("Before every action"))
        assertTrue(result.contains("After every action"))
    }

    @Test
    fun `toInstructionsBlock with constraints appears before FSM state`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = FakeTaskFsmRepository().toInstructionsBlock(fsm, null, null, false, "no jargon", true)
        val constraintIdx = result.indexOf("Agent constraints")
        val stateIdx = result.indexOf("Current task state:")
        assertTrue(constraintIdx < stateIdx)
    }
}
