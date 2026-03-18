package com.example.myapplication

import com.example.myapplication.agent.TaskFsmRepository
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.data.repository.TaskMemory
import com.example.myapplication.agent.LLMAgent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Tests for constraints checking in LLMAgent:
 * - Pre-check: user request violates constraint → ERROR, no FSM execution
 * - Pre-check: user request is OK → FSM executes normally
 * - Post-check: planning response violates constraint → ERROR after planning
 * - Post-check: execution step response violates constraint → ERROR after step
 * - Violation message contains Russian error text and alternative suggestion
 * - Disabled constraints are not checked
 */
class ConstraintsCheckTest {

    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var factRepo: FakeFactRepository
    private lateinit var branchNodeRepo: FakeBranchNodeRepository
    private lateinit var fsmDao: FakeTaskFsmDao
    private lateinit var fsmRepo: TaskFsmRepository

    @Before
    fun setup() {
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        summaryRepo = FakeSummaryRepository()
        factRepo = FakeFactRepository()
        branchNodeRepo = FakeBranchNodeRepository()
        fsmDao = FakeTaskFsmDao()
        fsmRepo = TaskFsmRepository(fsmDao)
    }

    // Sequential API that returns responses in order
    private class SequentialApi(private val responses: List<ChatResponse>)  : LLMApiClient {
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
        val profile = mock<com.example.myapplication.data.repository.UserProfileRepository>()
        whenever(profile.taskMemory).thenReturn(TaskMemory(name = "task", description = "", enabled = true))
        whenever(profile.toContextString()).thenReturn("")
        whenever(profile.userInformationContextString()).thenReturn("")

        val constraintsRepo = makeMockConstraintsRepository(enabled = constraintsEnabled, rules = constraintRules)

        return LLMAgent(
            api = api,
            sessionRepo = sessionRepo,
            messageRepo = messageRepo,
            memory = makeMockMemory(),
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

    private fun setupSession(): com.example.myapplication.domain.model.Session {
        val session = sessionOf(id = "s1")
        sessionRepo.sessions["s1"] = session
        return session
    }

    // -------------------------------------------------------------------------
    // Pre-check: user request violates constraint
    // -------------------------------------------------------------------------

    @Test
    fun `pre-check violation - FSM set to ERROR and no plan API call made`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        // API: call 1 = constraint pre-check → VIOLATION
        //      call 2 = alternative suggestion (inside handleConstraintViolation)
        //      planning must NOT be called
        val api = SequentialApi(listOf(
            textResponse("VIOLATION: no code generation"),
            textResponse("Explain the algorithm concept without writing code")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "write me a sorting algorithm")

        assertTrue(result.isSuccess)
        // Exactly 2 API calls: constraint check + alternative suggestion (no planning)
        assertEquals(2, api.requests.size)
        // FSM should be in ERROR
        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.ERROR, fsm.stage)
    }

    @Test
    fun `pre-check violation - response contains Russian error message`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(
            textResponse("VIOLATION: no code generation"),
            textResponse("Try asking for a high-level explanation instead")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "write me a sorting algorithm")
        val content = result.getOrNull()?.content ?: ""

        assertTrue("Should contain Russian error prefix", content.contains("❌ Ошибка"))
        assertTrue("Should mention constraint", content.contains("no code generation"))
        assertTrue("Should list options", content.contains("Варианты:"))
    }

    @Test
    fun `pre-check violation - alternative suggestion included in response`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val alternative = "Explain how bubble sort works conceptually without writing code"
        val api = SequentialApi(listOf(
            textResponse("VIOLATION: no code generation"),
            textResponse(alternative)
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "write me a sorting algorithm")
        val content = result.getOrNull()?.content ?: ""

        assertTrue("Should include alternative suggestion", content.contains(alternative))
    }

    // -------------------------------------------------------------------------
    // Pre-check: user request is OK
    // -------------------------------------------------------------------------

    @Test
    fun `pre-check OK - FSM proceeds to planning normally`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(
            textResponse("OK"),           // constraint pre-check
            textResponse(planWith(2)),    // planning
            textResponse("OK")            // constraint post-check on plan response
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "explain how sorting works")
        assertTrue(result.isSuccess)

        // 3 API calls: pre-check + planning + post-check on plan
        assertEquals(3, api.requests.size)
        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // Post-check: planning response violates constraint
    // -------------------------------------------------------------------------

    @Test
    fun `post-check planning violation - FSM set to ERROR after plan saved`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(
            textResponse("OK"),                               // constraint pre-check
            textResponse("1. Write code\n2. Run it"),         // plan with numbered steps (triggers post-check)
            textResponse("VIOLATION: no code generation"),   // post-check on plan response
            textResponse("Try a conceptual walkthrough")     // alternative suggestion
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "explain sorting")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get(session.id)!!
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
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id,
            stage = TaskStage.EXECUTION.name,
            step = 1, stepCount = 2,
            expectedAction = "execute_step",
            autoRun = true
        ))
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(
            textResponse("Here is some code: fun main() {}"),  // step 1 result
            textResponse("VIOLATION: no code generation"),      // post-check on step 1
            textResponse("Describe the algorithm instead")      // alternative
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.ERROR, fsm.stage)
        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("❌ Ошибка"))
    }

    // -------------------------------------------------------------------------
    // Post-check: execution step violation in manual mode
    // -------------------------------------------------------------------------

    @Test
    fun `post-check execution step violation in manual mode - FSM set to ERROR`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id,
            stage = TaskStage.EXECUTION.name,
            step = 1, stepCount = 2,
            expectedAction = "execute_step",
            autoRun = false
        ))
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(
            textResponse("fun hello() = println(\"hi\")"),   // step 1 result
            textResponse("VIOLATION: no code generation"),    // post-check
            textResponse("Explain the concept instead")       // alternative
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.ERROR, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // Disabled constraints — no check performed
    // -------------------------------------------------------------------------

    @Test
    fun `disabled constraints - no constraint check API call made`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        // Only planning response needed — constraint check should be skipped
        val api = SequentialApi(listOf(
            textResponse(planWith(1))
        ))
        val agent = makeAgent(api, constraintsEnabled = false)

        val result = agent.sendMessage(session.id, "write code for me")
        assertTrue(result.isSuccess)

        // Only 1 call: planning (no constraint check)
        assertEquals(1, api.requests.size)
        val fsm = fsmRepo.get(session.id)!!
        assertNotEquals(TaskStage.ERROR, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // TaskFsmRepository.toInstructionsBlock with constraints
    // -------------------------------------------------------------------------

    @Test
    fun `toInstructionsBlock without constraints has no constraint section`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = TaskFsmRepository(FakeTaskFsmDao()).toInstructionsBlock(fsm, null, null, false, null, false)
        assertFalse(result.contains("Agent constraints"))
    }

    @Test
    fun `toInstructionsBlock with disabled constraints has no constraint section`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = TaskFsmRepository(FakeTaskFsmDao()).toInstructionsBlock(fsm, null, null, false, "no code", false)
        assertFalse(result.contains("Agent constraints"))
    }

    @Test
    fun `toInstructionsBlock with enabled constraints includes rules and check instructions`() {
        val fsm = TaskFsmState(sessionId = "s1")
        val result = TaskFsmRepository(FakeTaskFsmDao()).toInstructionsBlock(fsm, null, null, false, "no code generation\nreply in English only", true)
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
        val result = TaskFsmRepository(FakeTaskFsmDao()).toInstructionsBlock(fsm, null, null, false, "no jargon", true)
        val constraintIdx = result.indexOf("Agent constraints")
        val stateIdx = result.indexOf("Current task state:")
        assertTrue(constraintIdx < stateIdx)
    }
}
