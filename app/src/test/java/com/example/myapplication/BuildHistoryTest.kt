package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import com.example.myapplication.data.db.entity.SummaryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.example.myapplication.data.api.AnthropicApi

/**
 * Tests for LLMAgent.buildHistory (все 5 стратегий памяти).
 * Фаза 0 — baseline тесты (пункт 0.2).
 *
 * Тестируем через sendMessage — единственный публичный путь через buildHistory.
 * Захватываем ChatRequest.input через FakeAnthropicApi.capturedRequests.
 */
class BuildHistoryTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var summaryDao: FakeSummaryDao
    private lateinit var factDao: FakeFactDao
    private lateinit var branchNodeDao: FakeBranchNodeDao
    private lateinit var api: CapturingAnthropicApi

    private fun makeAgent(apiOverride: AnthropicApi = api): LLMAgent = LLMAgent(
        api = apiOverride,
        sessionDao = sessionDao,
        messageDao = messageDao,
        memory = makeMockMemory(),
        summaryDao = summaryDao,
        factDao = factDao,
        branchNodeDao = branchNodeDao,
        userProfileRepository = makeMockUserProfile(),
        taskFsmRepository = makeMockTaskFsmRepository(),
        constraintsRepository = makeMockConstraintsRepository()
    )

    @Before
    fun setup() {
        sessionDao = FakeSessionDao()
        messageDao = FakeMessageDao()
        summaryDao = FakeSummaryDao()
        factDao = FakeFactDao()
        branchNodeDao = FakeBranchNodeDao()
        api = CapturingAnthropicApi(simpleResponse("OK"))
    }

    // -------------------------------------------------------------------------
    // FULL strategy
    // -------------------------------------------------------------------------

    @Test
    fun `FULL strategy returns all messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.FULL)
        sessionDao.sessions[session.id] = session
        // Pre-populate 4 messages
        repeat(4) { i ->
            messageDao.messages.add(makeUserMessage(i + 1, session.id))
            messageDao.messages.add(makeAssistantMessage(i + 1, session.id))
        }

        makeAgent().sendMessage(session.id, "new user message")

        val sentInput = api.lastRequest!!.input
        // 8 existing + 1 new user = 9 (LLMAgent inserts user msg before building history)
        assertEquals(9, sentInput.size)
    }

    @Test
    fun `FULL strategy preserves message order`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.FULL)
        sessionDao.sessions[session.id] = session
        messageDao.messages.add(makeUserMessage(1, session.id))
        messageDao.messages.add(makeAssistantMessage(1, session.id))
        messageDao.messages.add(makeUserMessage(2, session.id))

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
        sessionDao.sessions[session.id] = session
        // Add 10 messages (5 pairs)
        repeat(5) { i ->
            messageDao.messages.add(makeUserMessage(i + 1, session.id))
            messageDao.messages.add(makeAssistantMessage(i + 1, session.id))
        }

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        // Last 3 existing + 1 newly inserted user msg = 4 sent to API
        // But user msg is inserted THEN buildHistory takes last 3 from full list of 11
        assertEquals(3, sentInput.size)
    }

    @Test
    fun `SLIDING_WINDOW when history smaller than N returns all`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.SLIDING_WINDOW, slidingWindowN = 10)
        sessionDao.sessions[session.id] = session
        messageDao.messages.add(makeUserMessage(1, session.id))
        messageDao.messages.add(makeAssistantMessage(1, session.id))

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        // 2 existing + 1 new user message = 3, all within window of 10
        assertEquals(3, sentInput.size)
    }

    // -------------------------------------------------------------------------
    // STICKY_FACTS strategy
    // -------------------------------------------------------------------------

    @Test
    fun `STICKY_FACTS with no facts sends only recent messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS, stickyFactsN = 3)
        sessionDao.sessions[session.id] = session
        repeat(5) { i ->
            messageDao.messages.add(makeUserMessage(i + 1, session.id))
            messageDao.messages.add(makeAssistantMessage(i + 1, session.id))
        }
        // Use SequentialAnthropicApi to capture the FIRST request (main chat), not the updateFacts request
        val seqApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("Main answer"),       // main sendMessage response
                simpleResponse("fact: value")        // updateFacts response (if called)
            )
        )
        LLMAgent(seqApi, sessionDao, messageDao, makeMockMemory(), summaryDao, factDao,
            branchNodeDao, makeMockUserProfile(), makeMockTaskFsmRepository(), makeMockConstraintsRepository()).sendMessage(session.id, "new")

        val mainRequest = seqApi.requests.first()
        // No facts — only last stickyFactsN=3 from 11 messages = 3
        assertEquals(3, mainRequest.input.size)
        assertFalse(mainRequest.input.any { it.content.contains("[Known facts") })
    }

    @Test
    fun `STICKY_FACTS with facts prepends facts block`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS, stickyFactsN = 2)
        sessionDao.sessions[session.id] = session
        factDao.facts[session.id] = mutableListOf(
            FactEntity(session.id, "user_name", "Alice"),
            FactEntity(session.id, "goal", "build KMP app")
        )
        messageDao.messages.add(makeUserMessage(1, session.id))
        messageDao.messages.add(makeAssistantMessage(1, session.id))

        val seqApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("Main answer"),
                simpleResponse("user_name: Alice\ngoal: build KMP app")
            )
        )
        LLMAgent(seqApi, sessionDao, messageDao, makeMockMemory(), summaryDao, factDao,
            branchNodeDao, makeMockUserProfile(), makeMockTaskFsmRepository(), makeMockConstraintsRepository()).sendMessage(session.id, "new")

        val sentInput = seqApi.requests.first().input
        // facts user msg + facts assistant ack + last 2 existing + 1 new user = 5
        assertTrue(sentInput.size >= 3)
        // First message is synthetic facts injection
        assertTrue(sentInput[0].content.contains("[Known facts about this conversation]"))
        assertTrue(sentInput[0].content.contains("user_name: Alice"))
        assertTrue(sentInput[0].content.contains("goal: build KMP app"))
        // Second is assistant acknowledgement
        assertEquals("assistant", sentInput[1].role)
        assertTrue(sentInput[1].content.contains("Understood"))
    }

    @Test
    fun `STICKY_FACTS after sendMessage triggers updateFacts API call`() = runTest {
        // The updateFacts makes a second API call — verify 2 calls were made
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS, stickyFactsN = 3)
        sessionDao.sessions[session.id] = session

        val countingApi = CountingAnthropicApi(
            listOf(
                simpleResponse("Assistant answer"),  // main response
                simpleResponse("user_name: Alice\ngoal: build app")  // updateFacts response
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
        sessionDao.sessions[session.id] = session
        // 3 messages — below compressionN=5
        repeat(3) { i -> messageDao.messages.add(makeUserMessage(i + 1, session.id)) }

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        // 3 existing + 1 new user = 4, all sent because 4 <= compressionN=5
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
        sessionDao.sessions[session.id] = session
        // 8 messages (> compressionN=3)
        repeat(8) { i -> messageDao.messages.add(makeUserMessage(i + 1, session.id)) }

        val summaryApi = CapturingAnthropicApi(simpleResponse("OK"))
        // First API call = summary generation, second = main response
        val multiApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("This is the summary of older messages."),
                simpleResponse("Main answer")
            )
        )
        makeAgent(multiApi).sendMessage(session.id, "new question")

        val mainRequest = multiApi.requests.last()
        val sentInput = mainRequest.input
        // Should have: synthetic summary pair (2 msgs) + last compressionN=3 recent + 1 new user
        // = 2 + 3 + 1 = 6... but new user msg is inserted before buildHistory,
        // so recent = last 3 from 9 messages = 3, plus summary pair = 5
        assertTrue("Expected summary block", sentInput.any { it.content.contains("[Summary of earlier conversation]") })
        assertEquals("assistant", sentInput[1].role)
        assertTrue(sentInput[1].content.contains("Understood"))
    }

    @Test
    fun `COMPRESSION reuses cached summary when fresh`() = runTest {
        val session = sessionOf(
            strategy = MemoryStrategy.COMPRESSION,
            compressionEnabled = true,
            compressionN = 3,
            compressionM = 10  // high M means summary stays fresh for a long time
        )
        sessionDao.sessions[session.id] = session
        // 8 messages
        repeat(8) { i -> messageDao.messages.add(makeUserMessage(i + 1, session.id)) }
        // Pre-existing summary covering 5 messages (older = 8+1-3 = 6, coveredCount=6, delta=0 < M=10)
        summaryDao.summaries[session.id] = SummaryEntity(
            sessionId = session.id,
            summary = "Cached summary text",
            coveredMessageCount = 6
        )

        val countingApi = CountingAnthropicApi(listOf(simpleResponse("Main answer")))
        makeAgent(countingApi).sendMessage(session.id, "new")

        // Only 1 API call (main) — no summary regeneration because delta < M
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
            compressionM = 2  // regenerate after 2 new older messages
        )
        sessionDao.sessions[session.id] = session
        repeat(6) { i -> messageDao.messages.add(makeUserMessage(i + 1, session.id)) }
        // coveredMessageCount=3, now older=6+1-2=5, delta=5-3=2 >= M=2 → must regenerate
        summaryDao.summaries[session.id] = SummaryEntity(
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
        // New summary must be persisted
        assertNotNull(summaryDao.summaries[session.id])
        assertEquals("New fresh summary", summaryDao.summaries[session.id]!!.summary)
    }

    // -------------------------------------------------------------------------
    // BRANCHING strategy — buildHistory falls through to FULL for non-branch messages
    // -------------------------------------------------------------------------

    @Test
    fun `BRANCHING strategy uses full history for non-node messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.BRANCHING)
        sessionDao.sessions[session.id] = session
        messageDao.messages.add(makeUserMessage(1, session.id))
        messageDao.messages.add(makeAssistantMessage(1, session.id))

        makeAgent().sendMessage(session.id, "new")

        val sentInput = api.lastRequest!!.input
        // 2 existing + 1 new = 3
        assertEquals(3, sentInput.size)
    }
}

// ---------------------------------------------------------------------------
// Additional test infrastructure for BuildHistoryTest
// ---------------------------------------------------------------------------

fun simpleResponse(text: String) = ChatResponse(
    id = "id",
    output = listOf(OutputItem("message", listOf(OutputContent("output_text", text)))),
    usage = UsageInfo(10, 20)
)

class CapturingAnthropicApi(private val response: ChatResponse) : AnthropicApi {
    var lastRequest: ChatRequest? = null
    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        lastRequest = request
        return response
    }
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}

class CountingAnthropicApi(private val responses: List<ChatResponse>) : AnthropicApi {
    var callCount = 0
    val requests = mutableListOf<ChatRequest>()
    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        requests.add(request)
        return responses.getOrElse(callCount) { responses.last() }.also { callCount++ }
    }
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}

class SequentialAnthropicApi(private val responses: List<ChatResponse>) : AnthropicApi {
    private var idx = 0
    val requests = mutableListOf<ChatRequest>()
    override suspend fun sendMessage(request: ChatRequest): ChatResponse {
        requests.add(request)
        return responses[idx++]
    }
    override suspend fun getModels() = ModelsResponse(`object` = "list", data = emptyList())
}
