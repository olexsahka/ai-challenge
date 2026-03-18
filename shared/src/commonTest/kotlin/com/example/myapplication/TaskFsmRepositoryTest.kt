package com.example.myapplication

import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import com.example.myapplication.domain.repository.TaskFsmRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskFsmRepositoryTest {

    private lateinit var repo: TaskFsmRepository

    @BeforeTest
    fun setup() {
        repo = FakeTaskFsmRepository()
    }

    // -------------------------------------------------------------------------
    // getOrCreate
    // -------------------------------------------------------------------------

    @Test
    fun `getOrCreate creates new FSM with default PLANNING stage`() = runTest {
        val fsm = repo.getOrCreate("session-1")
        assertEquals(TaskStage.PLANNING, fsm.stage)
        assertEquals(1, fsm.step)
        assertEquals("generate_plan", fsm.expectedAction)
        assertFalse(fsm.paused)
        assertFalse(fsm.autoRun)
    }

    @Test
    fun `getOrCreate returns existing FSM if already present`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "session-1", stage = TaskStage.EXECUTION, step = 3))
        val fsm = repo.getOrCreate("session-1")
        assertEquals(TaskStage.EXECUTION, fsm.stage)
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
        assertEquals(TaskStage.EXECUTION, fsm.stage)
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
        repo.upsert(TaskFsmState(sessionId = "session-1", stepCount = 4))
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
        assertEquals(TaskStage.DONE, fsm.stage)
        assertEquals("finalize", fsm.expectedAction)
    }

    // -------------------------------------------------------------------------
    // pause / resume
    // -------------------------------------------------------------------------

    @Test
    fun `pause saves current state and sets paused true`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", stage = TaskStage.EXECUTION, step = 3, expectedAction = "execute_step"))
        repo.pause("s1")
        val fsm = repo.get("s1")!!
        assertTrue(fsm.paused)
        assertFalse(fsm.autoRun)
        assertEquals(TaskStage.EXECUTION, fsm.savedStage)
        assertEquals(3, fsm.savedStep)
        assertEquals("execute_step", fsm.savedExpectedAction)
        assertEquals("wait", fsm.expectedAction)
    }

    @Test
    fun `pause is idempotent when already paused`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", paused = true, expectedAction = "wait", savedStage = TaskStage.EXECUTION))
        repo.pause("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.EXECUTION, fsm.savedStage) // unchanged
    }

    @Test
    fun `resume restores saved state and clears paused`() = runTest {
        repo.upsert(TaskFsmState(
            sessionId = "s1",
            paused = true,
            expectedAction = "wait",
            savedStage = TaskStage.EXECUTION,
            savedStep = 2,
            savedExpectedAction = "execute_step"
        ))
        repo.resume("s1")
        val fsm = repo.get("s1")!!
        assertFalse(fsm.paused)
        assertEquals(TaskStage.EXECUTION, fsm.stage)
        assertEquals(2, fsm.step)
        assertEquals("execute_step", fsm.expectedAction)
        assertNull(fsm.savedStage)
        assertNull(fsm.savedStep)
        assertNull(fsm.savedExpectedAction)
    }

    @Test
    fun `resume is no-op when not paused`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", stage = TaskStage.EXECUTION, step = 3, paused = false))
        repo.resume("s1")
        val fsm = repo.get("s1")!!
        assertEquals(3, fsm.step) // unchanged
    }

    // -------------------------------------------------------------------------
    // enableAutoRun / disableAutoRun
    // -------------------------------------------------------------------------

    @Test
    fun `enableAutoRun sets autoRun true and clears paused`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", paused = true, autoRun = false))
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
        assertEquals(TaskStage.PLANNING, fsm.stage)
    }

    @Test
    fun `disableAutoRun sets autoRun false`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", autoRun = true))
        repo.disableAutoRun("s1")
        val fsm = repo.get("s1")!!
        assertFalse(fsm.autoRun)
    }

    // -------------------------------------------------------------------------
    // setError
    // -------------------------------------------------------------------------

    @Test
    fun `setError sets stage to ERROR and disables autoRun`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", stage = TaskStage.PLANNING, autoRun = true))
        repo.setError("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.ERROR, fsm.stage)
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
        repo.upsert(TaskFsmState(sessionId = "s1", stage = TaskStage.VALIDATION))
        repo.validationFailed("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.EXECUTION, fsm.stage)
        assertEquals("execute_step", fsm.expectedAction)
    }

    // -------------------------------------------------------------------------
    // reset / deleteBySession
    // -------------------------------------------------------------------------

    @Test
    fun `reset returns FSM to initial PLANNING state`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1", stage = TaskStage.DONE, step = 5, autoRun = true))
        repo.reset("s1")
        val fsm = repo.get("s1")!!
        assertEquals(TaskStage.PLANNING, fsm.stage)
        assertEquals(1, fsm.step)
        assertFalse(fsm.autoRun)
    }

    @Test
    fun `deleteBySession removes FSM from store`() = runTest {
        repo.upsert(TaskFsmState(sessionId = "s1"))
        repo.deleteBySession("s1")
        assertNull(repo.get("s1"))
    }

    // -------------------------------------------------------------------------
    // toInstructionsBlock
    // -------------------------------------------------------------------------

    @Test
    fun `toInstructionsBlock contains stage step and expectedAction`() = runTest {
        repo.getOrCreate("s1")
        repo.transitionTo("s1", TaskStage.EXECUTION, 2, "execute_step")
        val updatedFsm = repo.get("s1")!!
        val block = repo.toInstructionsBlock(updatedFsm, null, null, false, null, false)
        assertTrue(block.contains("stage: execution"))
        assertTrue(block.contains("step: 2"))
        assertTrue(block.contains("expected_action: execute_step"))
    }

    @Test
    fun `toInstructionsBlock includes task name and description when enabled`() = runTest {
        val fsm = repo.getOrCreate("s1")
        val block = repo.toInstructionsBlock(fsm, "My Task", "Build an app", true, null, false)
        assertTrue(block.contains("Task: My Task"))
        assertTrue(block.contains("Description: Build an app"))
    }

    @Test
    fun `toInstructionsBlock omits task info when disabled`() = runTest {
        val fsm = repo.getOrCreate("s1")
        val block = repo.toInstructionsBlock(fsm, "My Task", "Build an app", false, null, false)
        assertFalse(block.contains("Task: My Task"))
    }

    @Test
    fun `toInstructionsBlock omits task info when null`() = runTest {
        val fsm = repo.getOrCreate("s1")
        val block = repo.toInstructionsBlock(fsm, null, null, true, null, false)
        assertFalse(block.contains("Task:"))
    }
}
