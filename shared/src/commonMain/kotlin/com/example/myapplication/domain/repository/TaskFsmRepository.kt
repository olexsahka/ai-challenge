package com.example.myapplication.domain.repository

import com.example.myapplication.domain.model.TaskFsmState
import com.example.myapplication.domain.model.TaskStage
import kotlinx.coroutines.flow.Flow

interface TaskFsmRepository {
    fun observe(sessionId: String): Flow<TaskFsmState?>
    suspend fun get(sessionId: String): TaskFsmState?
    suspend fun getOrCreate(sessionId: String): TaskFsmState
    suspend fun upsert(state: TaskFsmState)
    suspend fun transitionTo(
        sessionId: String,
        stage: TaskStage,
        step: Int,
        expectedAction: String,
        stepCount: Int? = null
    )
    suspend fun markDone(sessionId: String)
    suspend fun validationFailed(sessionId: String)
    suspend fun pause(sessionId: String)
    suspend fun resume(sessionId: String)
    suspend fun enableAutoRun(sessionId: String)
    suspend fun disableAutoRun(sessionId: String)
    suspend fun reset(sessionId: String)
    suspend fun setError(sessionId: String)
    suspend fun deleteBySession(sessionId: String)
    fun toInstructionsBlock(
        fsm: TaskFsmState,
        taskMemoryName: String?,
        taskMemoryDescription: String?,
        taskMemoryEnabled: Boolean,
        constraintsRules: String?,
        constraintsEnabled: Boolean
    ): String
}
