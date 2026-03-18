package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.api.model.ChatRequest
import com.example.myapplication.data.api.model.ChatResponse
import com.example.myapplication.data.api.model.ModelsResponse
import com.example.myapplication.data.api.model.OutputContent
import com.example.myapplication.data.api.model.OutputItem
import com.example.myapplication.data.api.model.UsageInfo
import com.example.myapplication.domain.api.LLMApiClient
import com.example.myapplication.domain.model.MemoryStrategy
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SendMessageTest {

    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var messageRepo: FakeMessageRepository
    private lateinit var summaryRepo: FakeSummaryRepository
    private lateinit var factRepo: FakeFactRepository
    private lateinit var branchNodeRepo: FakeBranchNodeRepository

    @BeforeTest
    fun setup() {
        sessionRepo = FakeSessionRepository()
        messageRepo = FakeMessageRepository()
        summaryRepo = FakeSummaryRepository()
        factRepo = FakeFactRepository()
        branchNodeRepo = FakeBranchNodeRepository()
    }

    private fun makeAgent(
        api: LLMApiClient,
        memoryContext: String = "",
        profileContext: String = ""
    ) = LLMAgent(
        api = api,
        sessionRepo = sessionRepo,
        messageRepo = messageRepo,
        memory = makeFakeMemory(contextString = memoryContext),
        summaryRepo = summaryRepo,
        factRepo = factRepo,
        branchNodeRepo = branchNodeRepo,
        userProfileRepository = makeFakeUserProfile(contextString = profileContext),
        taskFsmRepository = FakeTaskFsmRepository(),
        constraintsRepository = makeFakeConstraintsRepository(),
        clock = FakeClock(),
        uuidGenerator = FakeUuidGenerator(),
        dateFormatter = FakeDateFormatter()
    )

    // -------------------------------------------------------------------------
    // User message persistence
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessage persists user message before API call`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session

        var userMsgFoundBeforeResponse = false
        val api = object : LLMApiClient {
            override suspend fun sendMessage(request: ChatRequest): ChatResponse {
                userMsgFoundBeforeResponse = messageRepo.messages.any {
                    it.content == "hello world" && it.isFromUser
                }
                return simpleResponse("hi there")
            }
            override suspend fun getModels() =
                ModelsResponse(`object` = "list", data = emptyList())
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
        sessionRepo.sessions[session.id] = session
        val api = CapturingAnthropicApi(simpleResponse("Assistant says hi"))

        makeAgent(api).sendMessage(session.id, "hello")

        val assistantMessages = messageRepo.messages.filter { !it.isFromUser }
        assertEquals(1, assistantMessages.size)
        assertEquals("Assistant says hi", assistantMessages.first().content)
    }

    @Test
    fun `sendMessage returns success Result with correct content`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session
        val api = CapturingAnthropicApi(simpleResponse("The answer is 42"))

        val result = makeAgent(api).sendMessage(session.id, "question")

        assertTrue(result.isSuccess)
        assertEquals("The answer is 42", result.getOrNull()!!.content)
    }

    @Test
    fun `sendMessage stores correct token counts in persisted message`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session
        val api = CapturingAnthropicApi(
            ChatResponse(
                id = "id",
                output = listOf(OutputItem("message", listOf(OutputContent("output_text", "answer")))),
                usage = UsageInfo(150, 75)
            )
        )

        makeAgent(api).sendMessage(session.id, "question")

        val assistantMsg = messageRepo.messages.first { !it.isFromUser }
        assertEquals(150, assistantMsg.inputTokens)
        assertEquals(75, assistantMsg.outputTokens)
    }

    // -------------------------------------------------------------------------
    // Session title auto-set
    // -------------------------------------------------------------------------

    @Test
    fun `first assistant message sets session title from first sentence`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session
        sessionRepo.assistantMessageCount = 1

        val responseText = "The capital of France is Paris. It is a beautiful city."
        makeAgent(CapturingAnthropicApi(simpleResponse(responseText)))
            .sendMessage(session.id, "Capital of France?")

        val title = sessionRepo.sessions[session.id]!!.title
        assertTrue(title.startsWith("The capital of France is Paris"),
            "Expected title to start with the first sentence, got: $title")
        assertTrue(title.length <= 50, "Expected title <= 50 chars, got: $title")
    }

    @Test
    fun `title is truncated to 50 characters when no sentence boundary`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session
        sessionRepo.assistantMessageCount = 1

        val longText = "A".repeat(100)
        makeAgent(CapturingAnthropicApi(simpleResponse(longText)))
            .sendMessage(session.id, "question")

        val title = sessionRepo.sessions[session.id]!!.title
        assertTrue(title.length <= 50, "Expected title <= 50 chars, got ${title.length}")
    }

    @Test
    fun `subsequent assistant messages do not change session title`() = runTest {
        val session = sessionOf(id = "session-1").also {
            sessionRepo.sessions["session-1"] = it.copy(title = "Original Title")
        }
        sessionRepo.assistantMessageCount = 2

        makeAgent(CapturingAnthropicApi(simpleResponse("New response that would change title")))
            .sendMessage(session.id, "follow-up")

        assertEquals("Original Title", sessionRepo.sessions[session.id]!!.title)
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
        sessionRepo.sessions[session.id] = session

        val result = makeAgent(ThrowingAnthropicApi(RuntimeException("network error")))
            .sendMessage(session.id, "hello")

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage returns failure when response has no output`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session
        val emptyResponse = ChatResponse(id = "id", output = emptyList(), usage = null)

        val result = makeAgent(CapturingAnthropicApi(emptyResponse))
            .sendMessage(session.id, "hello")

        assertTrue(result.isFailure)
    }

    @Test
    fun `sendMessage does not persist assistant message on API failure`() = runTest {
        val session = sessionOf()
        sessionRepo.sessions[session.id] = session

        makeAgent(ThrowingAnthropicApi(RuntimeException("fail")))
            .sendMessage(session.id, "hello")

        val assistantMessages = messageRepo.messages.filter { !it.isFromUser }
        assertTrue(assistantMessages.isEmpty(), "Expected no assistant messages on failure")
    }

    // -------------------------------------------------------------------------
    // STICKY_FACTS — updateFacts called after sendMessage
    // -------------------------------------------------------------------------

    @Test
    fun `STICKY_FACTS strategy triggers fact extraction after response`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.STICKY_FACTS)
        sessionRepo.sessions[session.id] = session

        val multiApi = SequentialAnthropicApi(
            listOf(
                simpleResponse("Sure, I'll remember that!"),
                simpleResponse("user_name: Alice\nlanguage: Kotlin")
            )
        )
        makeAgent(multiApi).sendMessage(session.id, "My name is Alice and I use Kotlin")

        val facts = factRepo.facts[session.id]
        assertNotNull(facts)
        assertTrue(facts!!.isNotEmpty())
    }

    @Test
    fun `non-STICKY_FACTS strategy does not make extra API call`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.FULL)
        sessionRepo.sessions[session.id] = session

        val countingApi = CountingAnthropicApi(listOf(simpleResponse("OK")))
        makeAgent(countingApi).sendMessage(session.id, "hello")

        assertEquals(1, countingApi.callCount)
    }
}
