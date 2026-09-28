package com.example.agentchat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.agentchat.data.config.ConfigDao
import com.example.agentchat.data.config.ConfigEntity
import com.example.agentchat.data.rag.KnowledgeChunkEntity
import com.example.agentchat.data.rag.KnowledgeDao
import com.example.agentchat.data.rag.KnowledgeSourceEntity
import com.example.agentchat.data.skill.SkillDao
import com.example.agentchat.data.skill.SkillEntity

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, AttachmentEntity::class, ConfigEntity::class, KnowledgeSourceEntity::class, KnowledgeChunkEntity::class, SkillEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun configDao(): ConfigDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun skillDao(): SkillDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS model_configs (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, baseUrl TEXT NOT NULL, modelName TEXT NOT NULL, protocol TEXT NOT NULL, enabled INTEGER NOT NULL, supportsVision INTEGER NOT NULL, supportsFiles INTEGER NOT NULL, isDefault INTEGER NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_sources (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, contentUri TEXT NOT NULL, importedAt INTEGER NOT NULL, chunkCount INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS knowledge_chunks (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, sourceName TEXT NOT NULL, chunkIndex INTEGER NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(sourceId) REFERENCES knowledge_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_chunks_sourceId ON knowledge_chunks(sourceId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_knowledge_chunks_sourceId_chunkIndex ON knowledge_chunks(sourceId, chunkIndex)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE model_configs ADD COLUMN profilePrompt TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS skills (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, version TEXT NOT NULL, triggers TEXT NOT NULL, toolNames TEXT NOT NULL, instructions TEXT NOT NULL, enabled INTEGER NOT NULL, requiresConfirmation INTEGER NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }
    }
}
