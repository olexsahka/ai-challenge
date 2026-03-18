package com.example.myapplication.agent

import com.example.myapplication.data.db.dao.TaskFsmDao
import com.example.myapplication.data.db.entity.TaskFsmEntity
import com.example.myapplication.data.db.entity.TaskStage as EntityTaskStage
import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import com.example.myapplication.domain.repository.TaskFsmRepository as TaskFsmRepositoryInterface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Mapping functions between Room entity and domain model
private fun TaskFsmEntity.toDomain(): TaskFsmState = TaskFsmState(
    sessionId = sessionId,
    stage = runCatching { TaskStage.valueOf(stage) }.getOrDefault(TaskStage.PLANNING),
    step = step,
    stepCount = stepCount,
    expectedAction = expectedAction,
    paused = paused,
    autoRun = autoRun,
    savedStage = savedStage?.let { runCatching { TaskStage.valueOf(it) }.getOrNull() },
    savedStep = savedStep,
    savedExpectedAction = savedExpectedAction
)

private fun TaskFsmState.toEntity(): TaskFsmEntity = TaskFsmEntity(
    sessionId = sessionId,
    stage = stage.name,
    step = step,
    stepCount = stepCount,
    expectedAction = expectedAction,
    paused = paused,
    autoRun = autoRun,
    savedStage = savedStage?.name,
    savedStep = savedStep,
    savedExpectedAction = savedExpectedAction
)

class TaskFsmRepository(private val dao: TaskFsmDao) : TaskFsmRepositoryInterface {

    override fun observe(sessionId: String): Flow<TaskFsmState?> =
        dao.observeBySession(sessionId).map { it?.toDomain() }

    override suspend fun upsert(state: TaskFsmState) = dao.upsert(state.toEntity())

    override suspend fun get(sessionId: String): TaskFsmState? =
        dao.getBySession(sessionId)?.toDomain()

    override suspend fun getOrCreate(sessionId: String): TaskFsmState {
        return dao.getBySession(sessionId)?.toDomain()
            ?: TaskFsmState(sessionId = sessionId).also { dao.upsert(it.toEntity()) }
    }

    override suspend fun transitionTo(
        sessionId: String,
        stage: TaskStage,
        step: Int,
        expectedAction: String,
        stepCount: Int?
    ) {
        val current = dao.getBySession(sessionId) ?: TaskFsmEntity(sessionId = sessionId)
        dao.upsert(current.copy(
            stage = stage.name,
            step = step,
            expectedAction = expectedAction,
            stepCount = stepCount ?: current.stepCount
        ))
    }

    override suspend fun markDone(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(stage = TaskStage.DONE.name, expectedAction = "finalize"))
    }

    override suspend fun validationFailed(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(stage = TaskStage.EXECUTION.name, expectedAction = "execute_step"))
    }

    override suspend fun pause(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        if (current.paused) return
        dao.upsert(current.copy(
            paused = true,
            autoRun = false,
            savedStage = current.stage,
            savedStep = current.step,
            savedExpectedAction = current.expectedAction,
            expectedAction = "wait"
        ))
    }

    override suspend fun resume(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        if (!current.paused) return
        dao.upsert(current.copy(
            paused = false,
            stage = current.savedStage ?: current.stage,
            step = current.savedStep ?: current.step,
            expectedAction = current.savedExpectedAction ?: current.expectedAction,
            savedStage = null,
            savedStep = null,
            savedExpectedAction = null
        ))
    }

    override suspend fun enableAutoRun(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: TaskFsmEntity(sessionId = sessionId)
        dao.upsert(current.copy(autoRun = true, paused = false))
    }

    override suspend fun disableAutoRun(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(autoRun = false))
    }

    override suspend fun reset(sessionId: String) {
        dao.upsert(TaskFsmEntity(sessionId = sessionId))
    }

    override suspend fun deleteBySession(sessionId: String) = dao.deleteBySession(sessionId)

    override suspend fun setError(sessionId: String) {
        val current = dao.getBySession(sessionId) ?: return
        dao.upsert(current.copy(stage = TaskStage.ERROR.name, autoRun = false, expectedAction = "retry"))
    }

    override fun toInstructionsBlock(
        fsm: TaskFsmState,
        taskMemoryName: String?,
        taskMemoryDescription: String?,
        taskMemoryEnabled: Boolean,
        constraintsRules: String?,
        constraintsEnabled: Boolean
    ): String {
        val sb = StringBuilder()
        if (taskMemoryEnabled) {
            if (!taskMemoryName.isNullOrBlank()) sb.appendLine("Task: ${taskMemoryName.trim()}")
            if (!taskMemoryDescription.isNullOrBlank()) sb.appendLine("Description: ${taskMemoryDescription.trim()}")
            sb.appendLine()
        }
        if (constraintsEnabled && !constraintsRules.isNullOrBlank()) {
            sb.appendLine("Agent constraints (MUST NEVER violate):")
            sb.appendLine(constraintsRules.trim())
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
  stage: ${fsm.stage.name.lowercase()}
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
