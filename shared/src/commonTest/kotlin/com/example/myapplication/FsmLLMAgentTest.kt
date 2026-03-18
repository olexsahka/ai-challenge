package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.data.repository.TaskMemory
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.domain.model.TaskStage
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.BeforeTest

class FsmLLMAgentTest {

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
        val profile = makeFakeUserProfile()
        profile.taskMemory = TaskMemory(name = "app", description = "", enabled = true)
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
            constraintsRepository = makeFakeConstraintsRepository(),
            clock = FakeClock(),
            uuidGenerator = FakeUuidGenerator(),
            dateFormatter = FakeDateFormatter()
        )
    }

    private fun setupSession(id: String = "s1") = sessionOf(id = id).also {
        sessionRepo.sessions[id] = it
    }

    // -------------------------------------------------------------------------
    // Manual step-by-step (autoRun=false)
    // -------------------------------------------------------------------------

    @Test
    fun `manual mode - planning creates FSM and asks to proceed to step 1`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(sessionId = session.id)

        val api = SequentialApi(listOf(fsmResponse(planWith(3))))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "build a mario game")

        assertTrue(result.isSuccess)
        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("Приступить к шагу 1"), "Should ask to proceed to step 1")

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
        assertEquals(1, fsm.step)
        assertEquals(3, fsm.stepCount)
    }

    @Test
    fun `manual mode - execution step 1 asks to proceed to step 2`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.EXECUTION,
            step = 1, stepCount = 3, expectedAction = "execute_step"
        )
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("Step 1 done")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("Приступить к шагу 2"), "Should ask to proceed to step 2")

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
        assertEquals(2, fsm.step)
    }

    @Test
    fun `manual mode - last execution step asks to proceed to validation`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.EXECUTION,
            step = 2, stepCount = 2, expectedAction = "execute_step"
        )
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("Step 2 done")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("валидации"), "Should ask to proceed to validation")

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.VALIDATION, fsm.stage)
    }

    @Test
    fun `manual mode - validation passes and asks to finalize`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.VALIDATION,
            step = 1, stepCount = 2, expectedAction = "validate_results"
        )
        messageRepo.messages.add(makeUserMessage(1, session.id))

        val api = SequentialApi(listOf(fsmResponse("All good, no errors")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("Завершить"), "Should ask to finalize")

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.DONE, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // Auto-run (autoRun=true)
    // -------------------------------------------------------------------------

    @Test
    fun `autoRun executes planning and all steps and validation and done`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, autoRun = true
        )

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
        assertTrue(content.contains("🏁"), "Should show done stage")

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.DONE, fsm.stage)
        assertEquals(5, api.requests.size)
    }

    @Test
    fun `autoRun stops when disableAutoRun called between steps`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.EXECUTION,
            step = 1, stepCount = 3, expectedAction = "execute_step", autoRun = true
        )
        messageRepo.messages.add(makeUserMessage(1, session.id))

        var callCount = 0
        val api = object : LLMApiClient {
            override suspend fun sendMessage(request: ChatRequest): ChatResponse {
                callCount++
                if (callCount == 1) {
                    val current = fsmRepo.states["s1"]!!
                    fsmRepo.states["s1"] = current.copy(autoRun = false)
                }
                return fsmResponse("Step $callCount done")
            }
            override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
        }
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "да")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("остановлен"), "Should report stop")
        assertEquals(1, callCount)
    }

    // -------------------------------------------------------------------------
    // Error handling — bad input (no numbered steps)
    // -------------------------------------------------------------------------

    @Test
    fun `bad input - plan with no numbered steps sets FSM to ERROR`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(sessionId = session.id)

        val api = SequentialApi(listOf(fsmResponse("I don't understand your request. Please clarify.")))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "ыыыы")
        assertTrue(result.isSuccess)

        val content = result.getOrNull()?.content ?: ""
        assertTrue(content.contains("❌"), "Should show error message")

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.ERROR, fsm.stage)
    }

    @Test
    fun `bad input - user message and planning response marked as isError`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(sessionId = session.id)

        val api = SequentialApi(listOf(fsmResponse("No plan possible here.")))
        val agent = makeAgent(api)

        agent.sendMessage(session.id, "ыыыы")

        val errorMessages = messageRepo.messages.filter { it.isError }
        assertTrue(errorMessages.size >= 2,
            "At least user msg and planning response should be marked as error")
    }

    @Test
    fun `bad input - error messages excluded from history on next request`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(sessionId = session.id)

        val api = SequentialApi(listOf(
            fsmResponse("No plan possible."),
            fsmResponse(planWith(1)),
            fsmResponse("Step 1 done"),
            fsmResponse("Validated"),
            fsmResponse("Done!")
        ))
        val agent = makeAgent(api)

        agent.sendMessage(session.id, "ыыыы")
        agent.sendMessage(session.id, "build a calculator")

        val secondPlanRequest = api.requests[1]
        val contents = secondPlanRequest.input.map { it.content }
        assertFalse(contents.any { it.contains("ыыыы") },
            "Error messages should not appear in next request")
    }

    // -------------------------------------------------------------------------
    // ERROR stage → restart on next message
    // -------------------------------------------------------------------------

    @Test
    fun `ERROR stage is reset and planning starts fresh on next sendMessage`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.ERROR
        )

        val api = SequentialApi(listOf(fsmResponse(planWith(1))))
        val agent = makeAgent(api)

        val result = agent.sendMessage(session.id, "build a calculator")
        assertTrue(result.isSuccess)

        val fsm = fsmRepo.get(session.id)!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
    }

    // -------------------------------------------------------------------------
    // DONE stage — normal message flow
    // -------------------------------------------------------------------------

    @Test
    fun `DONE stage falls back to normal sendMessage`() = runTest {
        val session = setupSession()
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.DONE
        )
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
        fsmRepo.states[session.id] = com.example.myapplication.domain.model.TaskFsmState(
            sessionId = session.id, stage = TaskStage.EXECUTION,
            step = 1, stepCount = 1, expectedAction = "execute_step", autoRun = true
        )
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
