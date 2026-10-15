package com.exorcist.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_readings")
data class SensorReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: Long = 0,
    
    val magneticX: Float = 0f,
    val magneticY: Float = 0f,
    val magneticZ: Float = 0f,
    val magneticTotal: Float = 0f,
    
    val audioFrequency: Float = 0f,
    val audioAmplitude: Float = 0f,
    
    val accelerationX: Float = 0f,
    val accelerationY: Float = 0f,
    val accelerationZ: Float = 0f,
    val accelerationTotal: Float = 0f,
    
    val pressure: Float = 0f,
    val light: Float = 0f,
    val proximity: Float = 0f,
    
    // Новые поля для 3D-позиции и ориентации
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val posZ: Double = 0.0,
    val azimuth: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    
    // Поля для ИИ-анализа
    val anomalyStatus: String = "НОРМА",
    val anomalyReason: String = ""
)
