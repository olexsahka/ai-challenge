package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.db.entity.MemoryStrategy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for LLMAgent.buildInstructions (системный промпт + memory + userProfile).
 * Фаза 0 — baseline тесты (пункт 0.3).
 *
 * buildInstructions — приватный метод, тестируем через sendMessage:
 * захватываем ChatRequest.instructions через CapturingAnthropicApi.
 */
class BuildInstructionsTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var api: CapturingAnthropicApi

    @Before
    fun setup() {
        sessionDao = FakeSessionDao()
        messageDao = FakeMessageDao()
        api = CapturingAnthropicApi(simpleResponse("OK"))
    }

    private fun makeAgent(
        memoryContext: String = "",
        profileContext: String = ""
    ): LLMAgent = LLMAgent(
        api = api,
        sessionDao = sessionDao,
        messageDao = messageDao,
        memory = makeMockMemory(memoryContext),
        summaryDao = FakeSummaryDao(),
        factDao = FakeFactDao(),
        branchNodeDao = FakeBranchNodeDao(),
        userProfileRepository = makeMockUserProfile(profileContext)
    )

    // -------------------------------------------------------------------------
    // systemPrompt only
    // -------------------------------------------------------------------------

    @Test
    fun `systemPrompt is included in instructions`() = runTest {
        val session = sessionOf(systemPrompt = "You are a helpful assistant.")
        sessionDao.sessions[session.id] = session

        makeAgent().sendMessage(session.id, "hello")

        val instructions = api.lastRequest!!.instructions
        assertNotNull(instructions)
        assertTrue(instructions!!.contains("You are a helpful assistant."))
    }

    @Test
    fun `blank systemPrompt alone results in null instructions`() = runTest {
        val session = sessionOf(systemPrompt = "")
        sessionDao.sessions[session.id] = session

        makeAgent().sendMessage(session.id, "hello")

        assertNull(api.lastRequest!!.instructions)
    }

    @Test
    fun `whitespace-only systemPrompt results in null instructions`() = runTest {
        val session = sessionOf(systemPrompt = "   ")
        sessionDao.sessions[session.id] = session

        makeAgent().sendMessage(session.id, "hello")

        assertNull(api.lastRequest!!.instructions)
    }

    // -------------------------------------------------------------------------
    // memory only
    // -------------------------------------------------------------------------

    @Test
    fun `memory context is appended to instructions`() = runTest {
        val session = sessionOf(systemPrompt = "")
        sessionDao.sessions[session.id] = session

        makeAgent(memoryContext = "Stored memories:\n- lang: Kotlin")
            .sendMessage(session.id, "hello")

        val instructions = api.lastRequest!!.instructions
        assertNotNull(instructions)
        assertTrue(instructions!!.contains("Stored memories:"))
        assertTrue(instructions.contains("- lang: Kotlin"))
    }

    @Test
    fun `empty memory context does not add instructions`() = runTest {
        val session = sessionOf(systemPrompt = "")
        sessionDao.sessions[session.id] = session

        makeAgent(memoryContext = "").sendMessage(session.id, "hello")

        assertNull(api.lastRequest!!.instructions)
    }

    // -------------------------------------------------------------------------
    // userProfile only
    // -------------------------------------------------------------------------

    @Test
    fun `user profile context is appended to instructions`() = runTest {
        val session = sessionOf(systemPrompt = "")
        sessionDao.sessions[session.id] = session

        makeAgent(profileContext = "User profile:\nAlice, senior developer")
            .sendMessage(session.id, "hello")

        val instructions = api.lastRequest!!.instructions
        assertNotNull(instructions)
        assertTrue(instructions!!.contains("User profile:"))
        assertTrue(instructions.contains("Alice, senior developer"))
    }

    @Test
    fun `empty user profile does not add instructions`() = runTest {
        val session = sessionOf(systemPrompt = "")
        sessionDao.sessions[session.id] = session

        makeAgent(profileContext = "").sendMessage(session.id, "hello")

        assertNull(api.lastRequest!!.instructions)
    }

    // -------------------------------------------------------------------------
    // All three combined
    // -------------------------------------------------------------------------

    @Test
    fun `systemPrompt memory and profile all appear in instructions`() = runTest {
        val session = sessionOf(systemPrompt = "Be concise.")
        sessionDao.sessions[session.id] = session

        makeAgent(
            memoryContext = "Stored memories:\n- key: value",
            profileContext = "User profile:\nBob"
        ).sendMessage(session.id, "hello")

        val instructions = api.lastRequest!!.instructions!!
        assertTrue(instructions.contains("Be concise."))
        assertTrue(instructions.contains("Stored memories:"))
        assertTrue(instructions.contains("User profile:"))
        assertTrue(instructions.contains("Bob"))
    }

    @Test
    fun `sections are separated by blank line`() = runTest {
        val session = sessionOf(systemPrompt = "Prompt.")
        sessionDao.sessions[session.id] = session

        makeAgent(memoryContext = "Stored memories:\n- k: v")
            .sendMessage(session.id, "hello")

        val instructions = api.lastRequest!!.instructions!!
        assertTrue(instructions.contains("Prompt.\n\nStored memories:"))
    }

    // -------------------------------------------------------------------------
    // Model and temperature passthrough
    // -------------------------------------------------------------------------

    @Test
    fun `model from session is sent in request`() = runTest {
        val session = sessionOf(model = "gpt-4o")
        sessionDao.sessions[session.id] = session

        makeAgent().sendMessage(session.id, "hello")

        assertEquals("gpt-4o", api.lastRequest!!.model)
    }

    @Test
    fun `temperature 1_0 is sent as null`() = runTest {
        val session = sessionOf(temperature = 1.0f)
        sessionDao.sessions[session.id] = session

        makeAgent().sendMessage(session.id, "hello")

        assertNull(api.lastRequest!!.temperature)
    }

    @Test
    fun `temperature other than 1_0 is sent`() = runTest {
        val session = sessionOf(temperature = 0.7f)
        sessionDao.sessions[session.id] = session

        makeAgent().sendMessage(session.id, "hello")

        assertEquals(0.7f, api.lastRequest!!.temperature)
    }
}
