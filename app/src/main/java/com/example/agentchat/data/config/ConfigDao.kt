package com.example.agentchat.data.config

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM model_configs ORDER BY isDefault DESC, displayName ASC")
    fun observeAll(): Flow<List<ConfigEntity>>

    @Query("SELECT * FROM model_configs")
    suspend fun findAll(): List<ConfigEntity>

    @Query("SELECT * FROM model_configs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ConfigEntity?

    @Query("DELETE FROM model_configs")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ConfigEntity)

    @Query("UPDATE model_configs SET isDefault = 0")
    suspend fun clearDefaults()

    @Query("UPDATE model_configs SET isDefault = 1 WHERE id = :id AND enabled = 1")
    suspend fun setDefault(id: String)

    @Query("UPDATE model_configs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM model_configs WHERE id = :id")
    suspend fun delete(id: String)
}
