package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.domain.model.FactData
import com.example.myapplication.domain.model.MemoryStrategy
import com.example.myapplication.domain.model.SummaryData
import com.example.myapplication.domain.api.LLMApiClient
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildHistoryTest {

    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var factRepo: FakeFactRepository
    private lateinit var branchNodeRepo: FakeBranchNodeRepository
    private lateinit var api: CapturingAnthropicApi

    private fun makeAgent(apiOverride: LLMApiClient = api): LLMAgent = LLMAgent(
        api = apiOverride,
        sessionRepo = sessionRepo,
        messageRepo = messageRepo,
        memory = makeFakeMemory(),
        summaryRepo = summaryRepo,
        factRepo = factRepo,
        branchNodeRepo = branchNodeRepo,
        userProfileRepository = makeFakeUserProfile(),
        taskFsmRepository = FakeTaskFsmRepository(),
        constraintsRepository = makeFakeConstraintsRepository(),
        clock = FakeClock(),
        uuidGenerator = FakeUuidGenerator(),
        dateFormatter = FakeDateFormatter()
    )

    @BeforeTest
    fun setup() {
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        summaryRepo = FakeSummaryRepository()
        factRepo = FakeFactRepository()
        branchNodeRepo = FakeBranchNodeRepository()
        api = CapturingAnthropicApi(simpleResponse("OK"))
    }

    // -------------------------------------------------------------------------
    // FULL strategy
    // -------------------------------------------------------------------------

    @Test
    fun `FULL strategy returns all messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.FULL)
        sessionRepo.sessions[session.id] = session
        repeat(4) { i ->
            messageRepo.messages.add(makeUserMessage(i + 1, session.id))
            messageRepo.messages.add(makeAssistantMessage(i + 1, session.id))
        }

        makeAgent().sendMessage(session.id, "new user message")

        val sentInput = api.lastRequest!!.input
        assertEquals(9, sentInput.size)
    }

    @Test
    fun `FULL strategy preserves message order`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.FULL)
        sessionRepo.sessions[session.id] = session
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))
        messageRepo.messages.add(makeUserMessage(2, session.id))

        makeAgent().sendMessage(session.id, "fourth")

        val sentInput = api.lastRequest!!.input
        assertEquals("user", sentInput[0].role)
        assertEquals("User message 1", sentInput[0].content)
        assertEquals("assistant", sentInput[1].role)
        assertEquals("user", sentInput[2].role)
        assertEquals("User message 2", sentInput[2].content)
    }

    // -------------------------------------------------------------------------
    // SLIDING_WINDOW strategy
    // -------------------------------------------------------------------------

    @Test
    fun `SLIDING_WINDOW returns only last N messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.SLIDING_WINDOW, slidingWindowN = 3)
        sessionRepo.sessions[session.id] = session
        repeat(5) { i ->
            messageRepo.messages.add(makeUserMessage(i + 1, session.id))
            messageRepo.messages.add(makeAssistantMessage(i + 1, session.id))
        }

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        assertEquals(3, sentInput.size)
    }

    @Test
    fun `SLIDING_WINDOW when history smaller than N returns all`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.SLIDING_WINDOW, slidingWindowN = 10)
        sessionRepo.sessions[session.id] = session
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        assertEquals(3, sentInput.size)
    }

    // -------------------------------------------------------------------------
    // STICKY_FACTS strategy
    // -------------------------------------------------------------------------

    @Test
    fun `STICKY_FACTS with no facts sends only recent messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS, stickyFactsN = 3)
        sessionRepo.sessions[session.id] = session
        repeat(5) { i ->
            messageRepo.messages.add(makeUserMessage(i + 1, session.id))
            messageRepo.messages.add(makeAssistantMessage(i + 1, session.id))
        }
        val seqApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("Main answer"),
                simpleResponse("fact: value")
            )
        )
        LLMAgent(seqApi, sessionRepo, messageRepo, makeFakeMemory(), summaryRepo, factRepo,
            branchNodeRepo, makeFakeUserProfile(), FakeTaskFsmRepository(), makeFakeConstraintsRepository(),
            FakeClock(), FakeUuidGenerator(), FakeDateFormatter()).sendMessage(session.id, "new")

        val mainRequest = seqApi.requests.first()
        assertEquals(3, mainRequest.input.size)
        assertFalse(mainRequest.input.any { it.content.contains("[Known facts") })
    }

    @Test
    fun `STICKY_FACTS with facts prepends facts block`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS, stickyFactsN = 2)
        sessionRepo.sessions[session.id] = session
        factRepo.facts[session.id] = mutableListOf(
            FactData(session.id, "user_name", "Alice"),
            FactData(session.id, "goal", "build KMP app")
        )
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))

        val seqApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("Main answer"),
                simpleResponse("user_name: Alice\ngoal: build KMP app")
            )
        )
        LLMAgent(seqApi, sessionRepo, messageRepo, makeFakeMemory(), summaryRepo, factRepo,
            branchNodeRepo, makeFakeUserProfile(), FakeTaskFsmRepository(), makeFakeConstraintsRepository(),
            FakeClock(), FakeUuidGenerator(), FakeDateFormatter()).sendMessage(session.id, "new")

        val sentInput = seqApi.requests.first().input
        assertTrue(sentInput.size >= 3)
        assertTrue(sentInput[0].content.contains("[Known facts about this conversation]"))
        assertTrue(sentInput[0].content.contains("user_name: Alice"))
        assertTrue(sentInput[0].content.contains("goal: build KMP app"))
        assertEquals("assistant", sentInput[1].role)
        assertTrue(sentInput[1].content.contains("Understood"))
    }

    @Test
    fun `STICKY_FACTS after sendMessage triggers updateFacts API call`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS, stickyFactsN = 3)
        sessionRepo.sessions[session.id] = session

        val countingApi = CountingAnthropicApi(
            listOf(
                simpleResponse("Assistant answer"),
                simpleResponse("user_name: Alice\ngoal: build app")
            )
        )
        makeAgent(countingApi).sendMessage(session.id, "My name is Alice")

        assertEquals(2, countingApi.callCount)
    }

    // -------------------------------------------------------------------------
    // COMPRESSION strategy
    // -------------------------------------------------------------------------

    @Test
    fun `COMPRESSION when history below threshold returns full history`() = runTest {
        val session = sessionOf(
            strategy = MemoryStrategy.COMPRESSION,
            compressionEnabled = true,
            compressionN = 5,
            compressionM = 6
        )
        sessionRepo.sessions[session.id] = session
        repeat(3) { i -> messageRepo.messages.add(makeUserMessage(i + 1, session.id)) }

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        assertEquals(4, sentInput.size)
        assertFalse(sentInput.any { it.content.contains("[Summary") })
    }

    @Test
    fun `COMPRESSION when above threshold sends summary plus recent N`() = runTest {
        val session = sessionOf(
            strategy = MemoryStrategy.COMPRESSION,
            compressionEnabled = true,
            compressionN = 3,
            compressionM = 6
        )
        sessionRepo.sessions[session.id] = session
        repeat(8) { i -> messageRepo.messages.add(makeUserMessage(i + 1, session.id)) }

        val multiApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("This is the summary of older messages."),
                simpleResponse("Main answer")
            )
        )
        makeAgent(multiApi).sendMessage(session.id, "new question")

        val mainRequest = multiApi.requests.last()
        val sentInput = mainRequest.input
        assertTrue(sentInput.any { it.content.contains("[Summary of earlier conversation]") })
        assertEquals("assistant", sentInput[1].role)
        assertTrue(sentInput[1].content.contains("Understood"))
    }

    @Test
    fun `COMPRESSION reuses cached summary when fresh`() = runTest {
        val session = sessionOf(
            strategy = MemoryStrategy.COMPRESSION,
            compressionEnabled = true,
            compressionN = 3,
            compressionM = 10
        )
        sessionRepo.sessions[session.id] = session
        repeat(8) { i -> messageRepo.messages.add(makeUserMessage(i + 1, session.id)) }
        summaryRepo.summaries[session.id] = SummaryData(
            sessionId = session.id,
            summary = "Cached summary text",
            coveredMessageCount = 6
        )

        val countingApi = CountingAnthropicApi(listOf(simpleResponse("Main answer")))
        makeAgent(countingApi).sendMessage(session.id, "new")

        assertEquals(1, countingApi.callCount)

        val sentInput = countingApi.requests.first().input
        assertTrue(sentInput.any { it.content.contains("Cached summary text") })
    }

    @Test
    fun `COMPRESSION regenerates summary when M threshold crossed`() = runTest {
        val session = sessionOf(
            strategy = MemoryStrategy.COMPRESSION,
            compressionEnabled = true,
            compressionN = 2,
            compressionM = 2
        )
        sessionRepo.sessions[session.id] = session
        repeat(6) { i -> messageRepo.messages.add(makeUserMessage(i + 1, session.id)) }
        summaryRepo.summaries[session.id] = SummaryData(
            sessionId = session.id,
            summary = "Old summary",
            coveredMessageCount = 3
        )

        val multiApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("New fresh summary"),
                simpleResponse("Main answer")
            )
        )
        makeAgent(multiApi).sendMessage(session.id, "new")

        assertEquals(2, multiApi.requests.size)
        assertNotNull(summaryRepo.summaries[session.id])
        assertEquals("New fresh summary", summaryRepo.summaries[session.id]!!.summary)
    }

    // -------------------------------------------------------------------------
    // BRANCHING strategy
    // -------------------------------------------------------------------------

    @Test
    fun `BRANCHING strategy uses full history for non-node messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.BRANCHING)
        sessionRepo.sessions[session.id] = session
        messageRepo.messages.add(makeUserMessage(1, session.id))
        messageRepo.messages.add(makeAssistantMessage(1, session.id))

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        assertEquals(3, sentInput.size)
    }
}
