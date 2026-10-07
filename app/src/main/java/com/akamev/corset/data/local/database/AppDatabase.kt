package com.akamev.corset.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.akamev.corset.data.local.database.dao.ChatDao
import com.akamev.corset.data.local.database.dao.DailySummaryDao
import com.akamev.corset.data.local.database.dao.PostureDao
import com.akamev.corset.data.local.database.entity.ChatMessageEntity
import com.akamev.corset.data.local.database.entity.ChatSessionEntity
import com.akamev.corset.data.local.database.entity.DailySummaryEntity
import com.akamev.corset.data.local.database.entity.PostureEntity

@Database(
    entities = [
        PostureEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        DailySummaryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun postureDao(): PostureDao
    abstract fun chatDao(): ChatDao
    abstract fun dailySummaryDao(): DailySummaryDao

    companion object {
        private const val DATABASE_NAME = "corset_app.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
