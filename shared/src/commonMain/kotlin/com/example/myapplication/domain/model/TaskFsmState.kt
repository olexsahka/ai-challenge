package com.example.myapplication.domain.model

enum class TaskStage { PLANNING, EXECUTION, VALIDATION, DONE, ERROR }

data class TaskFsmState(
    val sessionId: String,
    val stage: TaskStage = TaskStage.PLANNING,
    val step: Int = 1,
    val stepCount: Int = 0,
    val expectedAction: String = "generate_plan",
    val paused: Boolean = false,
    val autoRun: Boolean = false,
    val savedStage: TaskStage? = null,
    val savedStep: Int? = null,
    val savedExpectedAction: String? = null
)
