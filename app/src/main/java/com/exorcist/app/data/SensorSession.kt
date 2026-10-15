package com.exorcist.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_sessions")
data class SensorSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0,
    val isActive: Boolean = true,
    val readingCount: Int = 0,
    val locationName: String = ""
)
