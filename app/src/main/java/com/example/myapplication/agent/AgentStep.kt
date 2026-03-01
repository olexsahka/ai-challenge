package com.example.myapplication.agent

enum class AgentStepType {
    THOUGHT,
    ACTION,
    OBSERVATION,
    MEMORY_STORE,
    MEMORY_RECALL,
    FINAL_ANSWER
}

data class AgentStep(
    val type: AgentStepType,
    val content: String,
    val id: String = java.util.UUID.randomUUID().toString()
)
