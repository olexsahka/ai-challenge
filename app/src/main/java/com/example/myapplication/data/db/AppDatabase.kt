package com.example.myapplication.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.myapplication.data.db.dao.BranchNodeDao
import com.example.myapplication.data.db.dao.FactDao
import com.example.myapplication.data.db.dao.MessageDao
import com.example.myapplication.data.db.dao.SessionDao
import com.example.myapplication.data.db.dao.SummaryDao
import com.example.myapplication.data.db.entity.BranchNodeEntity
import com.example.myapplication.data.db.entity.FactEntity
import com.example.myapplication.data.db.entity.MessageEntity
import com.example.myapplication.data.db.entity.SessionEntity
import com.example.myapplication.data.db.entity.SummaryEntity

@Database(entities = [SessionEntity::class, MessageEntity::class, SummaryEntity::class, FactEntity::class, BranchNodeEntity::class], version = 7, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun summaryDao(): SummaryDao
    abstract fun factDao(): FactDao
    abstract fun branchNodeDao(): BranchNodeDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .fallbackToDestructiveMigration()
                .build()
    }
}
