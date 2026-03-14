package com.example.myapplication.agent

import com.example.myapplication.data.db.dao.TaskFsmDao
import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.data.db.entity.TaskStage
import com.example.myapplication.data.repository.Constraints
import com.example.myapplication.data.repository.TaskMemory
import kotlinx.coroutines.flow.Flow

class TaskFsmRepository(private val dao: TaskFsmDao) {

    fun observe(sessionId: String): Flow<TaskFsmEntity?> = dao.observeBySession(sessionId)

    suspend fun upsert(entity: TaskFsmEntity) = dao.upsert(entity)

    suspend fun get(sessionId: String): TaskFsmEntity? = dao.getBySession(sessionId)

    suspend fun getOrCreate(sessionId: String): TaskFsmEntity {
        return dao.getBySession(sessionId) ?: TaskFsmEntity(sessionId = sessionId).also { dao.upsert(it) }
    }

    suspend fun transitionTo(sessionId: String, stage: TaskStage, step: Int, expectedAction: String, stepCount: Int? = null) {
        val current = dao.getBySession(sessionId) ?: TaskFsmEntity(sessionId = sessionId)
        dao.upsert(current.copy(
            stage = stage.name,
            step = step,
            expectedAction = expectedAction,
            stepCount = stepCount ?: current.stepCount
        ))
    }

    suspend fun markDone(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(stage = TaskStage.DONE.name, expectedAction = "finalize"))
    }

    suspend fun validationFailed(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(stage = TaskStage.EXECUTION.name, expectedAction = "execute_step"))
    }

    suspend fun pause(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        if (current.paused) return
        dao.upsert(
            current.copy(
                paused = true,
                autoRun = false,
                savedStage = current.stage,
                savedStep = current.step,
                savedExpectedAction = current.expectedAction,
                expectedAction = "wait"
            )
        )
    }

    suspend fun resume(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        if (!current.paused) return
        dao.upsert(
            current.copy(
                paused = false,
                stage = current.savedStage ?: current.stage,
                step = current.savedStep ?: current.step,
                expectedAction = current.savedExpectedAction ?: current.expectedAction,
                savedStage = null,
                savedStep = null,
                savedExpectedAction = null
            )
        )
    }

    suspend fun enableAutoRun(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: TaskFsmEntity(sessionId = sessionId)
        dao.upsert(current.copy(autoRun = true, paused = false))
    }

    suspend fun disableAutoRun(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(autoRun = false))
    }

    suspend fun reset(sessionId: String) {
        dao.upsert(TaskFsmEntity(sessionId = sessionId))
    }

    suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)

    suspend fun setError(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(stage = TaskStage.ERROR.name, autoRun = false, expectedAction = "retry"))
    }

    fun toInstructionsBlock(
        fsm: TaskFsmEntity,
        taskMemory: TaskMemory? = null,
        constraints: Constraints? = null
    ): String {
        val sb = StringBuilder()
        if (taskMemory != null && taskMemory.enabled) {
            if (taskMemory.name.isNotBlank()) sb.appendLine("Task: ${taskMemory.name.trim()}")
            if (taskMemory.description.isNotBlank()) sb.appendLine("Description: ${taskMemory.description.trim()}")
            sb.appendLine()
        }
        if (constraints != null && constraints.enabled && constraints.rules.isNotBlank()) {
            sb.appendLine("Agent constraints (MUST NEVER violate):")
            sb.appendLine(constraints.rules.trim())
            sb.appendLine()
            sb.appendLine("Constraint check rules:")
            sb.appendLine("- Before every action: verify it does not violate any constraint above.")
            sb.appendLine("- After every action: verify the result does not violate any constraint above.")
            sb.appendLine("- If a violation is detected at any point: stop immediately, set stage=error, and report:")
            sb.appendLine("  ERROR: action violates constraint \"<constraint description>\"")
            sb.appendLine("  Options: 1) Update constraints to allow this action. 2) Suggest an alternative request that does not violate constraints.")
            sb.appendLine()
        }
        sb.append("""
Current task state:
  stage: ${fsm.stage.lowercase()}
  step: ${fsm.step}
  expected_action: ${fsm.expectedAction}
  paused: ${fsm.paused}

Rules:
- Only perform the action defined in expected_action.
- After completing the action, update the state accordingly.
- Always include the updated state in your response in this format:

State:
stage: <stage>
step: <step>
expected_action: <action>

Action result:
<result of action>
""".trimIndent())
        return sb.toString()
    }
}
