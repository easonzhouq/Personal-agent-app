package com.example.agentchat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.agentchat.data.config.ConfigDao
import com.example.agentchat.data.config.ConfigEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class, ConfigEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun configDao(): ConfigDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS model_configs (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, baseUrl TEXT NOT NULL, modelName TEXT NOT NULL, protocol TEXT NOT NULL, enabled INTEGER NOT NULL, supportsVision INTEGER NOT NULL, supportsFiles INTEGER NOT NULL, isDefault INTEGER NOT NULL)")
            }
        }
    }
}
