package com.exorcist.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_readings")
data class SensorReading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Время замера
    val timestamp: Long = System.currentTimeMillis(),
    
    // ID сессии (группирует замеры одной записи)
    val sessionId: Long = 0,
    
    // Магнитное поле (мкТл) по трём осям
    val magneticX: Float = 0f,
    val magneticY: Float = 0f,
    val magneticZ: Float = 0f,
    
    // Общее магнитное поле (векторная сумма)
    val magneticTotal: Float = 0f,
    
    // Ультразвук: доминирующая частота (Гц) и амплитуда
    val audioFrequency: Float = 0f,
    val audioAmplitude: Float = 0f,
    
    // Вибрация (ускорение) по трём осям (м/с²)
    val accelerationX: Float = 0f,
    val accelerationY: Float = 0f,
    val accelerationZ: Float = 0f,
    
    // Общее ускорение
    val accelerationTotal: Float = 0f,
    
    // Давление (гПа)
    val pressure: Float = 0f,
    
    // Освещённость (люкс)
    val light: Float = 0f,
    
    // Близость (см, 0 = что-то рядом)
    val proximity: Float = 0f
)
