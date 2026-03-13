package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.db.entity.MemoryStrategy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for LLMAgent.sendMessage — persistence, title auto-set, failure cases.
 * Фаза 0 — baseline тесты (пункт 0.7 integration flow).
 */
class SendMessageTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var summaryDao: FakeSummaryDao
    private lateinit var factDao: FakeFactDao
    private lateinit var branchNodeDao: FakeBranchNodeDao

    @Before
    fun setup() {
        sessionDao = FakeSessionDao()
        messageDao = FakeMessageDao()
        summaryDao = FakeSummaryDao()
        factDao = FakeFactDao()
        branchNodeDao = FakeBranchNodeDao()
    }

    private fun makeAgent(
        api: com.example.myapplication.data.api.AnthropicApi,
        memoryContext: String = "",
        profileContext: String = ""
    ) = LLMAgent(
        api = api,
        sessionDao = sessionDao,
        messageDao = messageDao,
        memory = makeMockMemory(memoryContext),
        summaryDao = summaryDao,
        factDao = factDao,
        branchNodeDao = branchNodeDao,
        userProfileRepository = makeMockUserProfile(profileContext),
        taskFsmRepository = makeMockTaskFsmRepository()
    )

    // -------------------------------------------------------------------------
    // User message persistence
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessage persists user message before API call`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session

        // Use a capturing API to verify state mid-flow: before API response user msg must exist
        var userMsgFoundBeforeResponse = false
        val api = object : com.example.myapplication.data.api.AnthropicApi {
            override suspend fun sendMessage(
                request: com.example.myapplication.data.api.model.ChatRequest
            ): com.example.myapplication.data.api.model.ChatResponse {
                userMsgFoundBeforeResponse = messageDao.messages.any {
                    it.content == "hello world" && it.isFromUser
                }
                return simpleResponse("hi there")
            }
            override suspend fun getModels() =
                com.example.myapplication.data.api.model.ModelsResponse(`object` = "list", data = emptyList())
        }

        makeAgent(api).sendMessage(session.id, "hello world")

        assertTrue(userMsgFoundBeforeResponse)
    }

    // -------------------------------------------------------------------------
    // Assistant message persistence
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessage persists assistant message after successful response`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val api = CapturingAnthropicApi(simpleResponse("Assistant says hi"))

        makeAgent(api).sendMessage(session.id, "hello")

        val assistantMessages = messageDao.messages.filter { !it.isFromUser }
        assertEquals(1, assistantMessages.size)
        assertEquals("Assistant says hi", assistantMessages.first().content)
    }

    @Test
    fun `sendMessage returns success Result with correct content`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val api = CapturingAnthropicApi(simpleResponse("The answer is 42"))

        val result = makeAgent(api).sendMessage(session.id, "question")

        assertTrue(result.isSuccess)
        assertEquals("The answer is 42", result.getOrNull()!!.content)
    }

    @Test
    fun `sendMessage stores correct token counts in persisted message`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val api = CapturingAnthropicApi(
            com.example.myapplication.data.api.model.ChatResponse(
                id = "id",
                output = listOf(
                    com.example.myapplication.data.api.model.OutputItem(
                        "message",
                        listOf(com.example.myapplication.data.api.model.OutputContent("output_text", "answer"))
                    )
                ),
                usage = com.example.myapplication.data.api.model.UsageInfo(150, 75)
            )
        )

        makeAgent(api).sendMessage(session.id, "question")

        val assistantMsg = messageDao.messages.first { !it.isFromUser }
        assertEquals(150, assistantMsg.inputTokens)
        assertEquals(75, assistantMsg.outputTokens)
    }

    // -------------------------------------------------------------------------
    // Session title auto-set
    // -------------------------------------------------------------------------

    @Test
    fun `first assistant message sets session title from first sentence`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        sessionDao.assistantMessageCount = 1  // countAssistantMessages returns 1 → first message

        val responseText = "The capital of France is Paris. It is a beautiful city."
        makeAgent(CapturingAnthropicApi(simpleResponse(responseText)))
            .sendMessage(session.id, "Capital of France?")

        // LLMAgent splits on sentence boundary and includes trailing punctuation
        val title = sessionDao.sessions[session.id]!!.title
        assertTrue("Expected title to start with the first sentence, got: $title",
            title.startsWith("The capital of France is Paris"))
        assertTrue("Expected title <= 50 chars, got: $title", title.length <= 50)
    }

    @Test
    fun `title is truncated to 50 characters when no sentence boundary`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        sessionDao.assistantMessageCount = 1

        val longText = "A".repeat(100)
        makeAgent(CapturingAnthropicApi(simpleResponse(longText)))
            .sendMessage(session.id, "question")

        val title = sessionDao.sessions[session.id]!!.title
        assertTrue("Expected title <= 50 chars, got ${title.length}", title.length <= 50)
    }

    @Test
    fun `subsequent assistant messages do not change session title`() = runTest {
        val session = sessionOf(id = "session-1").also {
            sessionDao.sessions["session-1"] = it.copy(title = "Original Title")
        }
        sessionDao.assistantMessageCount = 2  // not the first message

        makeAgent(CapturingAnthropicApi(simpleResponse("New response that would change title")))
            .sendMessage(session.id, "follow-up")

        assertEquals("Original Title", sessionDao.sessions[session.id]!!.title)
    }

    // -------------------------------------------------------------------------
    // Error cases
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessage returns failure when session not found`() = runTest {
        val result = makeAgent(CapturingAnthropicApi(simpleResponse("OK")))
            .sendMessage("nonexistent-id", "hello")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Session not found"))
    }

    @Test
    fun `sendMessage returns failure when API throws`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session

        val result = makeAgent(ThrowingAnthropicApi(RuntimeException("network error")))
            .sendMessage(session.id, "hello")

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage returns failure when response has no output`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val emptyResponse = com.example.myapplication.data.api.model.ChatResponse(
            id = "id", output = emptyList(), usage = null
        )

        val result = makeAgent(CapturingAnthropicApi(emptyResponse))
            .sendMessage(session.id, "hello")

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage does not persist assistant message on API failure`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session

        makeAgent(ThrowingAnthropicApi(RuntimeException("fail")))
            .sendMessage(session.id, "hello")

        val assistantMessages = messageDao.messages.filter { !it.isFromUser }
        assertTrue("Expected no assistant messages on failure", assistantMessages.isEmpty())
    }

    // -------------------------------------------------------------------------
    // STICKY_FACTS — updateFacts called after sendMessage
    // -------------------------------------------------------------------------

    @Test
    fun `STICKY_FACTS strategy triggers fact extraction after response`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS)
        sessionDao.sessions[session.id] = session

        val multiApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("Sure, I'll remember that!"),
                simpleResponse("user_name: Alice\nlanguage: Kotlin")
            )
        )
        makeAgent(multiApi).sendMessage(session.id, "My name is Alice and I use Kotlin")

        // Verify facts were extracted and stored
        val facts = factDao.facts[session.id]
        assertNotNull(facts)
        assertTrue(facts!!.isNotEmpty())
    }

    @Test
    fun `non-STICKY_FACTS strategy does not make extra API call`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.FULL)
        sessionDao.sessions[session.id] = session

        val countingApi = CountingAnthropicApi(listOf(simpleResponse("OK")))
        makeAgent(countingApi).sendMessage(session.id, "hello")

        assertEquals(1, countingApi.callCount)
    }
}
