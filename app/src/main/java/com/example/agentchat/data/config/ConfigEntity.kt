package com.example.agentchat.data.config

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_configs")
data class ConfigEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val protocol: String,
    val enabled: Boolean,
    val supportsVision: Boolean,
    val supportsFiles: Boolean,
    val isDefault: Boolean,
    val profilePrompt: String = "",
)
