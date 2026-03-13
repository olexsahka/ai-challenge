package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.TaskFsmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskFsmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskFsmEntity)

    @Query("SELECT * FROM task_fsm WHERE sessionId = :sessionId")
    suspend fun getBySession(sessionId: String): TaskFsmEntity?

    @Query("SELECT * FROM task_fsm WHERE sessionId = :sessionId")
    fun observeBySession(sessionId: String): Flow<TaskFsmEntity?>

    @Query("DELETE FROM task_fsm WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
