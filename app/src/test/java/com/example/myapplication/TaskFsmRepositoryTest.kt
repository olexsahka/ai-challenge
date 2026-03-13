package com.example.myapplication

import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.data.db.entity.TaskStage
import com.example.myapplication.data.repository.TaskMemory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TaskFsmRepository — state transitions, pause/resume, autoRun, error handling.
 */
class TaskFsmRepositoryTest {

    private lateinit var dao: FakeTaskFsmDao
    private lateinit var repo: com.example.myapplication.agent.TaskFsmRepository

    @Before
    fun setup() {
        dao = FakeTaskFsmDao()
        repo = com.example.myapplication.agent.TaskFsmRepository(dao)
    }

    // -------------------------------------------------------------------------
    // getOrCreate
    // -------------------------------------------------------------------------

    @Test
    fun `getOrCreate creates new FSM with default PLANNING stage`() = runTest {
        val fsm = repo.getOrCreate("session-1")
        assertEquals(TaskStage.PLANNING.name, fsm.stage)
        assertEquals(1, fsm.step)
        assertEquals("generate_plan", fsm.expectedAction)
        assertFalse(fsm.paused)
        assertFalse(fsm.autoRun)
    }

    @Test
    fun `getOrCreate returns existing FSM if already present`() = runTest {
        val existing = TaskFsmEntity(sessionId = "session-1", stage = TaskStage.EXECUTION.name, step = 3)
        dao.upsert(existing)
        val fsm = repo.getOrCreate("session-1")
        assertEquals(TaskStage.EXECUTION.name, fsm.stage)
        assertEquals(3, fsm.step)
    }

    // -------------------------------------------------------------------------
    // transitionTo
    // -------------------------------------------------------------------------

    @Test
    fun `transitionTo updates stage step and expectedAction`() = runTest {
        repo.getOrCreate("session-1")
        repo.transitionTo("session-1", TaskStage.EXECUTION, 2, "execute_step")
        val fsm = repo.get("session-1")!!
        assertEquals(TaskStage.EXECUTION.name, fsm.stage)
        assertEquals(2, fsm.step)
        assertEquals("execute_step", fsm.expectedAction)
    }

    @Test
    fun `transitionTo updates stepCount when provided`() = runTest {
        repo.getOrCreate("session-1")
        repo.transitionTo("session-1", TaskStage.EXECUTION, 1, "execute_step", stepCount = 5)
        val fsm = repo.get("session-1")!!
        assertEquals(5, fsm.stepCount)
    }

    @Test
    fun `transitionTo preserves existing stepCount when not provided`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "session-1", stepCount = 4))
        repo.transitionTo("session-1", TaskStage.EXECUTION, 2, "execute_step")
        val fsm = repo.get("session-1")!!
        assertEquals(4, fsm.stepCount)
    }

    // -------------------------------------------------------------------------
    // markDone
    // -------------------------------------------------------------------------

    @Test
    fun `markDone sets stage to DONE and expectedAction to finalize`() = runTest {
        repo.getOrCreate("session-1")
        repo.markDone("session-1")
        val fsm = repo.get("session-1")!!
        assertEquals(TaskStage.DONE.name, fsm.stage)
        assertEquals("finalize", fsm.expectedAction)
    }

    // -------------------------------------------------------------------------
    // pause / resume
    // -------------------------------------------------------------------------

    @Test
    fun `pause saves current state and sets paused true`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", stage = TaskStage.EXECUTION.name, step = 3, expectedAction = "execute_step"))
        repo.pause("s1")
        val fsm = repo.get("s1")!!
        assertTrue(fsm.paused)
        assertFalse(fsm.autoRun)
        assertEquals(TaskStage.EXECUTION.name, fsm.savedStage)
        assertEquals(3, fsm.savedStep)
        assertEquals("execute_step", fsm.savedExpectedAction)
        assertEquals("wait", fsm.expectedAction)
    }

    @Test
    fun `pause is idempotent when already paused`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", paused = true, expectedAction = "wait", savedStage = TaskStage.EXECUTION.name))
        repo.pause("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.EXECUTION.name, fsm.savedStage) // unchanged
    }

    @Test
    fun `resume restores saved state and clears paused`() = runTest {
        dao.upsert(TaskFsmEntity(
            sessionId = "s1",
            paused = true,
            expectedAction = "wait",
            savedStage = TaskStage.EXECUTION.name,
            savedStep = 2,
            savedExpectedAction = "execute_step"
        ))
        repo.resume("s1")
        val fsm = repo.get("s1")!!
        assertFalse(fsm.paused)
        assertEquals(TaskStage.EXECUTION.name, fsm.stage)
        assertEquals(2, fsm.step)
        assertEquals("execute_step", fsm.expectedAction)
        assertNull(fsm.savedStage)
        assertNull(fsm.savedStep)
        assertNull(fsm.savedExpectedAction)
    }

    @Test
    fun `resume is no-op when not paused`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", stage = TaskStage.EXECUTION.name, step = 3, paused = false))
        repo.resume("s1")
        val fsm = repo.get("s1")!!
        assertEquals(3, fsm.step) // unchanged
    }

    // -------------------------------------------------------------------------
    // enableAutoRun / disableAutoRun
    // -------------------------------------------------------------------------

    @Test
    fun `enableAutoRun sets autoRun true and clears paused`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", paused = true, autoRun = false))
        repo.enableAutoRun("s1")
        val fsm = repo.get("s1")!!
        assertTrue(fsm.autoRun)
        assertFalse(fsm.paused)
    }

    @Test
    fun `enableAutoRun creates FSM if not exists`() = runTest {
        repo.enableAutoRun("s1")
        val fsm = repo.get("s1")!!
        assertTrue(fsm.autoRun)
        assertEquals(TaskStage.PLANNING.name, fsm.stage)
    }

    @Test
    fun `disableAutoRun sets autoRun false`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", autoRun = true))
        repo.disableAutoRun("s1")
        val fsm = repo.get("s1")!!
        assertFalse(fsm.autoRun)
    }

    // -------------------------------------------------------------------------
    // setError
    // -------------------------------------------------------------------------

    @Test
    fun `setError sets stage to ERROR and disables autoRun`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", stage = TaskStage.PLANNING.name, autoRun = true))
        repo.setError("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.ERROR.name, fsm.stage)
        assertFalse(fsm.autoRun)
        assertEquals("retry", fsm.expectedAction)
    }

    @Test
    fun `setError is no-op when FSM does not exist`() = runTest {
        repo.setError("nonexistent") // should not throw
        assertNull(repo.get("nonexistent"))
    }

    // -------------------------------------------------------------------------
    // validationFailed
    // -------------------------------------------------------------------------

    @Test
    fun `validationFailed reverts stage to EXECUTION`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", stage = TaskStage.VALIDATION.name))
        repo.validationFailed("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.EXECUTION.name, fsm.stage)
        assertEquals("execute_step", fsm.expectedAction)
    }

    // -------------------------------------------------------------------------
    // reset / deleteBySession
    // -------------------------------------------------------------------------

    @Test
    fun `reset returns FSM to initial PLANNING state`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1", stage = TaskStage.DONE.name, step = 5, autoRun = true))
        repo.reset("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.PLANNING.name, fsm.stage)
        assertEquals(1, fsm.step)
        assertFalse(fsm.autoRun)
    }

    @Test
    fun `deleteBySession removes FSM from store`() = runTest {
        dao.upsert(TaskFsmEntity(sessionId = "s1"))
        repo.deleteBySession("s1")
        assertNull(repo.get("s1"))
    }

    // -------------------------------------------------------------------------
    // toInstructionsBlock
    // -------------------------------------------------------------------------

    @Test
    fun `toInstructionsBlock contains stage step and expectedAction`() {
        val fsm = TaskFsmEntity(sessionId = "s1", stage = TaskStage.EXECUTION.name, step = 2, expectedAction = "execute_step")
        val block = repo.toInstructionsBlock(fsm)
        assertTrue(block.contains("stage: execution"))
        assertTrue(block.contains("step: 2"))
        assertTrue(block.contains("expected_action: execute_step"))
    }

    @Test
    fun `toInstructionsBlock includes task name and description when enabled`() {
        val fsm = TaskFsmEntity(sessionId = "s1")
        val task = TaskMemory(name = "My Task", description = "Build an app", enabled = true)
        val block = repo.toInstructionsBlock(fsm, task)
        assertTrue(block.contains("Task: My Task"))
        assertTrue(block.contains("Description: Build an app"))
    }

    @Test
    fun `toInstructionsBlock omits task info when disabled`() {
        val fsm = TaskFsmEntity(sessionId = "s1")
        val task = TaskMemory(name = "My Task", description = "Build an app", enabled = false)
        val block = repo.toInstructionsBlock(fsm, task)
        assertFalse(block.contains("Task: My Task"))
    }

    @Test
    fun `toInstructionsBlock omits task info when null`() {
        val fsm = TaskFsmEntity(sessionId = "s1")
        val block = repo.toInstructionsBlock(fsm, null)
        assertFalse(block.contains("Task:"))
    }
}
