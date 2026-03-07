package com.example.myapplication.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.myapplication.data.db.entity.BranchNodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BranchNodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: BranchNodeEntity)

    @Query("SELECT * FROM branch_nodes WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<BranchNodeEntity>>

    @Query("SELECT * FROM branch_nodes WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySession(sessionId: String): List<BranchNodeEntity>

    @Query("SELECT * FROM branch_nodes WHERE id = :id")
    suspend fun getById(id: String): BranchNodeEntity?

    @Query("UPDATE branch_nodes SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: String, label: String)
}
