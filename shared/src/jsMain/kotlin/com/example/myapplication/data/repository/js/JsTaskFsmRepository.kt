package com.example.myapplication.data.repository.js

import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import com.example.myapplication.domain.repository.TaskFsmRepository
import com.example.myapplication.platform.KeyValueStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class TaskFsmJson(
    val sessionId: String,
    val stage: String = "PLANNING",
    val step: Int = 1,
    val stepCount: Int = 0,
    val expectedAction: String = "generate_plan",
    val paused: Boolean = false,
    val autoRun: Boolean = false,
    val savedStage: String? = null,
    val savedStep: Int? = null,
    val savedExpectedAction: String? = null
)

private fun TaskFsmState.toJson() = TaskFsmJson(
    sessionId, stage.name, step, stepCount, expectedAction,
    paused, autoRun, savedStage?.name, savedStep, savedExpectedAction
)

private fun TaskFsmJson.toDomain() = TaskFsmState(
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

class JsTaskFsmRepository(private val storage: KeyValueStorage) : TaskFsmRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val flows = mutableMapOf<String, MutableStateFlow<TaskFsmState?>>()

    private fun key(sessionId: String) = "fsm_$sessionId"

    private fun load(sessionId: String): TaskFsmState? =
        storage.getString(key(sessionId))
            ?.let { runCatching { json.decodeFromString<TaskFsmJson>(it).toDomain() }.getOrNull() }

    private fun save(state: TaskFsmState) {
        storage.putString(key(state.sessionId), json.encodeToString(state.toJson()))
        flows.getOrPut(state.sessionId) { MutableStateFlow(null) }.value = state
    }

    override fun observe(sessionId: String): Flow<TaskFsmState?> =
        flows.getOrPut(sessionId) { MutableStateFlow(load(sessionId)) }

    override suspend fun get(sessionId: String): TaskFsmState? = load(sessionId)

    override suspend fun getOrCreate(sessionId: String): TaskFsmState =
        load(sessionId) ?: TaskFsmState(sessionId = sessionId).also { save(it) }

    override suspend fun upsert(state: TaskFsmState) = save(state)

    override suspend fun transitionTo(
        sessionId: String, stage: TaskStage, step: Int,
        expectedAction: String, stepCount: Int?
    ) {
        val current = load(sessionId) ?: TaskFsmState(sessionId = sessionId)
        save(current.copy(
            stage = stage,
            step = step,
            expectedAction = expectedAction,
            stepCount = stepCount ?: current.stepCount
        ))
    }

    override suspend fun markDone(sessionId: String) {
        val current = load(sessionId) ?: return
        save(current.copy(stage = TaskStage.DONE, expectedAction = "finalize"))
    }

    override suspend fun validationFailed(sessionId: String) {
        val current = load(sessionId) ?: return
        save(current.copy(stage = TaskStage.EXECUTION, expectedAction = "execute_step"))
    }

    override suspend fun pause(sessionId: String) {
        val current = load(sessionId) ?: return
        if (current.paused) return
        save(current.copy(
            paused = true,
            autoRun = false,
            savedStage = current.stage,
            savedStep = current.step,
            savedExpectedAction = current.expectedAction,
            expectedAction = "wait"
        ))
    }

    override suspend fun resume(sessionId: String) {
        val current = load(sessionId) ?: return
        if (!current.paused) return
        save(current.copy(
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
        val current = load(sessionId) ?: TaskFsmState(sessionId = sessionId)
        save(current.copy(autoRun = true, paused = false))
    }

    override suspend fun disableAutoRun(sessionId: String) {
        val current = load(sessionId) ?: return
        save(current.copy(autoRun = false))
    }

    override suspend fun reset(sessionId: String) = save(TaskFsmState(sessionId = sessionId))

    override suspend fun setError(sessionId: String) {
        val current = load(sessionId) ?: return
        save(current.copy(stage = TaskStage.ERROR, autoRun = false, expectedAction = "retry"))
    }

    override suspend fun deleteBySession(sessionId: String) {
        storage.remove(key(sessionId))
        flows[sessionId]?.value = null
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
