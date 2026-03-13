package com.example.myapplication.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TaskStage { PLANNING, EXECUTION, VALIDATION, DONE, ERROR }

@Entity(
    tableName = "task_fsm",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class TaskFsmEntity(
    @PrimaryKey val sessionId: String,
    val stage: String = TaskStage.PLANNING.name,
    val step: Int = 1,
    val stepCount: Int = 0,
    val expectedAction: String = "generate_plan",
    val paused: Boolean = false,
    val autoRun: Boolean = false,
    val savedStage: String? = null,
    val savedStep: Int? = null,
    val savedExpectedAction: String? = null
)
