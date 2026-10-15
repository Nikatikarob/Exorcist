package com.exorcist.app.core

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.max
import kotlin.math.sqrt

class SpatialTracker : SensorEventListener {
    
    // Текущая 3D-позиция (в метрах)
    var x = 0.0
    var y = 0.0
    var z = 0.0
        private set
    
    // Ориентация (в градусах)
    var azimuth = 0.0  // Поворот вокруг вертикальной оси (0-360°)
    var pitch = 0.0    // Наклон вперёд-назад (-90 до 90°)
    var roll = 0.0     // Наклон влево-вправо (-180 до 180°)
        private set
    
    // Скорость (м/с)
    var velocityX = 0.0
    var velocityY = 0.0
    var velocityZ = 0.0
        private set
    
    // Фильтрация
    private val alpha = 0.8f  // Коэффициент фильтра низких частот (0-1, чем больше - тем плавнее)
    
    // Гравитация и магнитное поле для расчёта ориентации
    private var gravity = FloatArray(3)
    private var geomagnetic = FloatArray(3)
    private var rotationMatrix = FloatArray(9)
    private var orientationAngles = FloatArray(3)
    
    // Акселерометр для отслеживания движения
    private var linearAcceleration = FloatArray(3)
    private var lastTimestamp = 0L
    
    // Порог для детекции шагов/движения
    private val movementThreshold = 0.5f
    
    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Применяем фильтр низких частот для сглаживания
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                
                // Линейное ускорение (без гравитации)
                linearAcceleration[0] = event.values[0] - gravity[0]
                linearAcceleration[1] = event.values[1] - gravity[1]
                linearAcceleration[2] = event.values[2] - gravity[2]
                
                // Интегрируем ускорение для получения скорости и позиции
                val currentTime = System.nanoTime()
                if (lastTimestamp != 0L) {
                    val dt = (currentTime - lastTimestamp) / 1_000_000_000.0
                    
                    // Обновляем скорость (v = v0 + a*t)
                    velocityX += linearAcceleration[0] * dt
                    velocityY += linearAcceleration[1] * dt
                    velocityZ += linearAcceleration[2] * dt
                    
                    // Применяем затухание (трение) для стабилизации
                    velocityX *= 0.95
                    velocityY *= 0.95
                    velocityZ *= 0.95
                    
                    // Обновляем позицию (s = s0 + v*t)
                    x += velocityX * dt
                    y += velocityY * dt
                    z += velocityZ * dt
                }
                lastTimestamp = currentTime
            }
            
            Sensor.TYPE_MAGNETIC_FIELD -> {
                geomagnetic[0] = alpha * geomagnetic[0] + (1 - alpha) * event.values[0]
                geomagnetic[1] = alpha * geomagnetic[1] + (1 - alpha) * event.values[1]
                geomagnetic[2] = alpha * geomagnetic[2] + (1 - alpha) * event.values[2]
                
                // Рассчитываем ориентацию
                updateOrientation()
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                // Гироскоп для более точного отслеживания поворотов
                if (lastTimestamp != 0L) {
                    val dt = (System.nanoTime() - lastTimestamp) / 1_000_000_000.0
                    
                    azimuth += Math.toDegrees(event.values[2].toDouble()) * dt
                    pitch += Math.toDegrees(event.values[0].toDouble()) * dt
                    roll += Math.toDegrees(event.values[1].toDouble()) * dt
                    
                    // Нормализуем углы
                    azimuth = ((azimuth % 360) + 360) % 360
                    pitch = pitch.coerceIn(-90.0, 90.0)
                    roll = ((roll % 360) + 360) % 360
                    if (roll > 180) roll -= 360
                }
            }
        }
    }
    
    private fun updateOrientation() {
        // Рассчитываем матрицу вращения и углы ориентации
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            gravity,
            geomagnetic
        )
        
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // Конвертируем в градусы
            azimuth = Math.toDegrees(orientationAngles[0].toDouble())
            pitch = Math.toDegrees(orientationAngles[1].toDouble())
            roll = Math.toDegrees(orientationAngles[2].toDouble())
            
            // Нормализуем
            azimuth = ((azimuth % 360) + 360) % 360
            pitch = pitch.coerceIn(-90.0, 90.0)
            roll = ((roll % 360) + 360) % 360
            if (roll > 180) roll -= 360
        }
    }
    
    // Проверка, движется ли телефон
    fun isMoving(): Boolean {
        val speed = sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ)
        return speed > movementThreshold
    }
    
    // Сброс позиции (например, при начале новой сессии)
    fun reset() {
        x = 0.0
        y = 0.0
        z = 0.0
        velocityX = 0.0
        velocityY = 0.0
        velocityZ = 0.0
        gravity = FloatArray(3)
        geomagnetic = FloatArray(3)
        linearAcceleration = FloatArray(3)
        lastTimestamp = 0L
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Игнорируем
    }
}
