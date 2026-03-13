package com.example.myapplication

import com.example.myapplication.agent.LLMAgent
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.MemoryStrategy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for LLMAgent.buildBranchHistory and sendMessageToNode.
 * Фаза 0 — baseline тесты (пункт 0.6).
 *
 * buildBranchHistory — приватный метод, тестируем через sendMessageToNode.
 * Захватываем ChatRequest.input через CapturingAnthropicApi.
 */
class BuildBranchHistoryTest {

    private lateinit var sessionDao: FakeSessionDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var summaryDao: FakeSummaryDao
    private lateinit var factDao: FakeFactDao
    private lateinit var branchNodeDao: FakeBranchNodeDao
    private lateinit var api: CapturingAnthropicApi

    @Before
    fun setup() {
        sessionDao = FakeSessionDao()
        messageDao = FakeMessageDao()
        summaryDao = FakeSummaryDao()
        factDao = FakeFactDao()
        branchNodeDao = FakeBranchNodeDao()
        api = CapturingAnthropicApi(simpleResponse("Branch response"))
    }

    private fun makeAgent() = LLMAgent(
        api = api,
        sessionDao = sessionDao,
        messageDao = messageDao,
        memory = makeMockMemory(),
        summaryDao = summaryDao,
        factDao = factDao,
        branchNodeDao = branchNodeDao,
        userProfileRepository = makeMockUserProfile(),
        taskFsmRepository = makeMockTaskFsmRepository()
    )

    private fun branchNode(id: String, sessionId: String, parentId: String? = null, label: String = "Node") =
        BranchNodeEntity(
            id = id,
            sessionId = sessionId,
            parentId = parentId,
            label = label,
            createdAt = id.hashCode().toLong()
        )

    // -------------------------------------------------------------------------
    // Single node (root only)
    // -------------------------------------------------------------------------

    @Test
    fun `single root node sends only its own messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.BRANCHING)
        sessionDao.sessions[session.id] = session

        val root = branchNode("root-1", session.id)
        branchNodeDao.nodes.add(root)
        messageDao.messages.add(makeUserMessage(1, session.id, "root-1"))
        messageDao.messages.add(makeAssistantMessage(1, session.id, "root-1"))

        makeAgent().sendMessageToNode(session.id, "root-1", "new message in root")

        val sentInput = api.lastRequest!!.input
        // 2 existing root messages + 1 new user = 3
        assertEquals(3, sentInput.size)
        assertEquals("User message 1", sentInput[0].content)
        assertEquals("Assistant reply 1", sentInput[1].content)
        assertEquals("new message in root", sentInput[2].content)
    }

    // -------------------------------------------------------------------------
    // Chain of nodes (root → child)
    // -------------------------------------------------------------------------

    @Test
    fun `child node includes root messages then child messages`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.BRANCHING)
        sessionDao.sessions[session.id] = session

        val root = branchNode("root-1", session.id)
        val child = branchNode("child-1", session.id, parentId = "root-1")
        branchNodeDao.nodes.addAll(listOf(root, child))

        // Root messages
        messageDao.messages.add(makeUserMessage(1, session.id, "root-1"))
        messageDao.messages.add(makeAssistantMessage(1, session.id, "root-1"))
        // Child messages
        messageDao.messages.add(makeUserMessage(2, session.id, "child-1"))
        messageDao.messages.add(makeAssistantMessage(2, session.id, "child-1"))

        makeAgent().sendMessageToNode(session.id, "child-1", "new in child")

        val sentInput = api.lastRequest!!.input
        // 2 root + 2 child + 1 new user = 5
        assertEquals(5, sentInput.size)
        // Order: root first, then child
        assertEquals("User message 1", sentInput[0].content)
        assertEquals("Assistant reply 1", sentInput[1].content)
        assertEquals("User message 2", sentInput[2].content)
        assertEquals("Assistant reply 2", sentInput[3].content)
        assertEquals("new in child", sentInput[4].content)
    }

    // -------------------------------------------------------------------------
    // Chain of 3 nodes (root → child → grandchild)
    // -------------------------------------------------------------------------

    @Test
    fun `chain of 3 nodes concatenates messages root first grandchild last`() = runTest {
        val session = sessionOf(strategy = MemoryStrategy.BRANCHING)
        sessionDao.sessions[session.id] = session

        val root = branchNode("n-root", session.id)
        val child = branchNode("n-child", session.id, parentId = "n-root")
        val grandchild = branchNode("n-grand", session.id, parentId = "n-child")
        branchNodeDao.nodes.addAll(listOf(root, child, grandchild))

        messageDao.messages.add(makeUserMessage(1, session.id, "n-root"))
        messageDao.messages.add(makeUserMessage(2, session.id, "n-child"))
        messageDao.messages.add(makeUserMessage(3, session.id, "n-grand"))

        makeAgent().sendMessageToNode(session.id, "n-grand", "new")

        val sentInput = api.lastRequest!!.input
        // 3 existing + 1 new = 4
        assertEquals(4, sentInput.size)
        assertEquals("User message 1", sentInput[0].content)
        assertEquals("User message 2", sentInput[1].content)
        assertEquals("User message 3", sentInput[2].content)
        assertEquals("new", sentInput[3].content)
    }

    // -------------------------------------------------------------------------
    // sendMessageToNode — persistence & result
    // -------------------------------------------------------------------------

    @Test
    fun `sendMessageToNode persists user message with correct branchNodeId`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val node = branchNode("node-42", session.id)
        branchNodeDao.nodes.add(node)

        makeAgent().sendMessageToNode(session.id, "node-42", "branch question")

        val userMsg = messageDao.messages.first { it.isFromUser && it.branchNodeId == "node-42" }
        assertEquals("branch question", userMsg.content)
    }

    @Test
    fun `sendMessageToNode persists assistant message with correct branchNodeId`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val node = branchNode("node-42", session.id)
        branchNodeDao.nodes.add(node)

        makeAgent().sendMessageToNode(session.id, "node-42", "hello")

        val assistantMsg = messageDao.messages.first { !it.isFromUser && it.branchNodeId == "node-42" }
        assertEquals("Branch response", assistantMsg.content)
    }

    @Test
    fun `sendMessageToNode returns success Result`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val node = branchNode("node-1", session.id)
        branchNodeDao.nodes.add(node)

        val result = makeAgent().sendMessageToNode(session.id, "node-1", "hello")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `sendMessageToNode returns failure when session not found`() = runTest {
        val node = branchNode("node-1", "nonexistent-session")
        branchNodeDao.nodes.add(node)

        val result = makeAgent().sendMessageToNode("nonexistent-session", "node-1", "hello")

        assertTrue(result.isFailure)
    }

    // -------------------------------------------------------------------------
    // First message auto-labels the node
    // -------------------------------------------------------------------------

    @Test
    fun `first message to node auto-sets node label from user text`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val node = branchNode("node-1", session.id, label = "Branch 1")
        branchNodeDao.nodes.add(node)

        makeAgent().sendMessageToNode(session.id, "node-1", "What is the capital of France?")

        val updatedNode = branchNodeDao.nodes.first { it.id == "node-1" }
        assertTrue(
            "Expected node label to be updated from user text, got: ${updatedNode.label}",
            updatedNode.label != "Branch 1"
        )
        assertTrue(updatedNode.label.contains("What is the capital"))
    }

    @Test
    fun `second message to node does not change node label`() = runTest {
        val session = sessionOf()
        sessionDao.sessions[session.id] = session
        val node = branchNode("node-1", session.id, label = "My Branch")
        branchNodeDao.nodes.add(node)
        // Pre-populate with one message to make it not the first
        messageDao.messages.add(makeUserMessage(1, session.id, "node-1"))

        makeAgent().sendMessageToNode(session.id, "node-1", "follow-up question")

        val updatedNode = branchNodeDao.nodes.first { it.id == "node-1" }
        assertEquals("My Branch", updatedNode.label)
    }
}
