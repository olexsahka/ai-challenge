package com.example.myapplication.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myapplication.data.db.dao.BranchNodeDao
import com.example.myapplication.data.db.dao.FactDao
import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.dao.RagChunkDao
import com.example.myapplication.data.db.dao.RagVocabularyDao
import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.db.dao.TaskFsmDao
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.data.db.entity.RagChunkEntity
import com.example.myapplication.data.db.entity.RagVocabularyEntity
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity
import com.example.myapplication.data.db.entity.TaskFsmEntity

@Database(entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class, FactEntity::class, BranchNodeEntity::class, TaskFsmEntity::class, RagChunkEntity::class, RagVocabularyEntity::class], version = 11, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao
    abstract fun factDao(): FactDao
    abstract fun branchNodeDao(): BranchNodeDao
    abstract fun taskFsmDao(): TaskFsmDao
    abstract fun ragChunkDao(): RagChunkDao
    abstract fun ragVocabularyDao(): RagVocabularyDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .fallbackToDestructiveMigration()
                .build()
    }
}
