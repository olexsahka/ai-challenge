package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.agent.TaskFsmRepository
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.domain.model.TaskStage
import com.example.myapplication.data.repository.TaskMemory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.whenever

/**
 * Unit tests for FSM integration in LLMAgent:
 * - Manual step-by-step execution (autoRun=false)
 * - Auto-run execution (autoRun=true)
 * - Stop auto-run mid-execution
 * - Error handling (no numbered steps → ERROR stage)
 * - Error messages excluded from history (isError=true)
 * - FSM ERROR stage → reset and restart on next message
 */
class FsmLLMAgentTest {

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

    private fun fsmResponse(text: String) = ChatResponse(
        id = "r", output = listOf(
            OutputItem(type = "message", content = listOf(
                OutputContent(type = "output_text", text = text)
            ))
        ), usage = UsageInfo(input_tokens = 10, output_tokens = 5)
    )

    private fun planWith(stepCount: Int): String {
        val lines = (1..stepCount).joinToString("\n") { "$it. Step $it" }
        return "Plan:\n$lines"
    }

    private fun makeAgent(api: LLMApiClient): LLMAgent {
        val profile = org.mockito.kotlin.mock<com.example.myapplication.data.repository.UserProfileRepository>()
        val tm = TaskMemory(name = "app", description = "", enabled = true)
        whenever(profile.taskMemory).thenReturn(tm)
        whenever(profile.toContextString()).thenReturn("")
        whenever(profile.userInformationContextString()).thenReturn("")
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
            constraintsRepository = makeMockConstraintsRepository(),
            clock = FakeClock(),
            uuidGenerator = FakeUuidGenerator(),
            dateFormatter = FakeDateFormatter()
        )
    }

    private fun setupSession(id: String = "s1"): com.example.myapplication.domain.model.Session {
        val session = sessionOf(id = id)
        sessionRepo.sessions[id] = session
        return session
    }

    // -------------------------------------------------------------------------
    // Manual step-by-step (autoRun=false)
    // -------------------------------------------------------------------------

    @Test
    fun `manual mode - planning creates FSM and asks to proceed to step 1`() = runTest {
        val session = setupSession()
        // FSM created by sendMessage when taskMemory.enabled=true
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(fsmResponse(planWith(3))))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "build a mario game")

        assertTrue(result.isSuccess)
        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should ask to proceed to step 1", content.contains("Приступить к шагу 1"))

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
        assertEquals(1, fsm.step)
        assertEquals(3, fsm.stepCount)
    }

    @Test
    fun `manual mode - execution step 1 asks to proceed to step 2`() = runTest {
        val session = setupSession()
        // FSM already in EXECUTION step 1 of 3
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id, stage = TaskStage.EXECUTION.name,
            step = 1, stepCount = 3, expectedAction = "execute_step"
        ))
        // Pre-populate history with planning message
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("Step 1 done")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should ask to proceed to step 2", content.contains("Приступить к шагу 2"))

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
        assertEquals(2, fsm.step)
    }

    @Test
    fun `manual mode - last execution step asks to proceed to validation`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id, stage = TaskStage.EXECUTION.name,
            step = 2, stepCount = 2, expectedAction = "execute_step"
        ))
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("Step 2 done")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should ask to proceed to validation", content.contains("валидации"))

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.VALIDATION, fsm.stage)
    }

    @Test
    fun `manual mode - validation passes and asks to finalize`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id, stage = TaskStage.VALIDATION.name,
            step = 1, stepCount = 2, expectedAction = "validate_results"
        ))
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("All good, no errors")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should ask to finalize", content.contains("Завершить"))

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.DONE, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // Auto-run (autoRun=true)
    // -------------------------------------------------------------------------

    @Test
    fun `autoRun executes planning and all steps and validation and done`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id, autoRun = true))

        // plan(3 steps) + step1 + step2 + step3 + validation + done = 6 responses
        val api = SequentialApi(listOf(
            fsmResponse(planWith(2)),
            fsmResponse("Step 1 result"),
            fsmResponse("Step 2 result"),
            fsmResponse("All validated"),
            fsmResponse("Task completed!")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "build app")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should show done stage", content.contains("🏁"))

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.DONE, fsm.stage)
        assertEquals(5, api.requests.size)
    }

    @Test
    fun `autoRun stops when disableAutoRun called between steps`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id, stage = TaskStage.EXECUTION.name,
            step = 1, stepCount = 3, expectedAction = "execute_step", autoRun = true
        ))
        messageRepo.messages.add(makeUserMessage(1, session.id))

        var callCount = 0
        val api = object  : LLMApiClient {
            override suspend fun sendMessage(request: ChatRequest): ChatResponse {
                callCount++
                // Disable autoRun after first execution step
                if (callCount == 1) fsmDao.upsert(fsmDao.states["s1"]!!.copy(autoRun = false))
                return fsmResponse("Step $callCount done")
            }
            override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
        }
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should report stop", content.contains("остановлен"))
        assertEquals(1, callCount)
    }

    // -------------------------------------------------------------------------
    // Error handling — bad input (no numbered steps)
    // -------------------------------------------------------------------------

    @Test
    fun `bad input - plan with no numbered steps sets FSM to ERROR`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(fsmResponse("I don't understand your request. Please clarify.")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "ыыыы")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue("Should show error message", content.contains("❌"))

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.ERROR, fsm.stage)
    }

    @Test
    fun `bad input - user message and planning response marked as isError`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(fsmResponse("No plan possible here.")))
        val agent = makeAgent(api)

        agent.sendMessage(session.id, "ыыыы")

        // The user message and the planning response should be marked as errors
        val errorMessages = messageRepo.messages.filter { it.isError }
        assertTrue("At least user msg and planning response should be marked as error",
            errorMessages.size >= 2)
    }

    @Test
    fun `bad input - error messages excluded from history on next request`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id))

        val api = SequentialApi(listOf(
            fsmResponse("No plan possible."),          // bad input response
            fsmResponse(planWith(1)),                   // good plan
            fsmResponse("Step 1 done"),
            fsmResponse("Validated"),
            fsmResponse("Done!")
        ))
        val agent = makeAgent(api)

        // First: bad input
        agent.sendMessage(session.id, "ыыыы")

        // Second: FSM is in ERROR, reset and restart
        agent.sendMessage(session.id, "build a calculator")

        // The second planning request should NOT contain the bad-input messages
        val secondPlanRequest = api.requests[1]
        val contents = secondPlanRequest.input.map { it.content }
        assertFalse("Error messages should not appear in next request",
            contents.any { it.contains("ыыыы") })
    }

    // -------------------------------------------------------------------------
    // ERROR stage → restart on next message
    // -------------------------------------------------------------------------

    @Test
    fun `ERROR stage is reset and planning starts fresh on next sendMessage`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id, stage = TaskStage.ERROR.name))

        val api = SequentialApi(listOf(fsmResponse(planWith(1))))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "build a calculator")
        assertTrue(result.isSuccess)

        // After reset, FSM transitioned to EXECUTION
        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // DONE stage — normal message flow
    // -------------------------------------------------------------------------

    @Test
    fun `DONE stage falls back to normal sendMessage`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(sessionId = session.id, stage = TaskStage.DONE.name))
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("Here is my follow-up answer")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "follow-up question")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertEquals("Here is my follow-up answer", content)
    }

    // -------------------------------------------------------------------------
    // sendMessageAutoRun
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessageAutoRun creates fresh FSM with autoRun and runs all stages`() = runTest {
        val session = setupSession()

        val api = SequentialApi(listOf(
            fsmResponse(planWith(1)),
            fsmResponse("Step 1 result"),
            fsmResponse("Validation ok"),
            fsmResponse("All done!")
        ))
        val agent = makeAgent(api)

        val result = agent.sendMessageAutoRun(session.id, "write hello world")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.DONE, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // continueFromCurrentStage
    // -------------------------------------------------------------------------

    @Test
    fun `continueFromCurrentStage continues from current FSM stage`() = runTest {
        val session = setupSession()
        fsmDao.upsert(TaskFsmEntity(
            sessionId = session.id, stage = TaskStage.EXECUTION.name,
            step = 1, stepCount = 1, expectedAction = "execute_step", autoRun = true
        ))
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(
            fsmResponse("Step 1 done"),
            fsmResponse("Validation ok"),
            fsmResponse("Done!")
        ))
        val agent = makeAgent(api)

        val result = agent.continueFromCurrentStage(session.id)
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.DONE, fsm.stage)
    }

    @Test
    fun `continueFromCurrentStage fails when no FSM exists`() = runTest {
        val session = setupSession()
        val api = SequentialApi(emptyList())
        val agent = makeAgent(api)

        val result = agent.continueFromCurrentStage(session.id)
        assertTrue(result.isFailure)
    }
}
