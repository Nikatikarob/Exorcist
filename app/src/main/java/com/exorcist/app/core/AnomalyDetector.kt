package com.exorcist.app.core

import kotlin.math.sqrt

class AnomalyDetector {
    
    // Скользящее среднее и стандартное отклонение для магнитного поля
    private var magSum = 0.0
    private var magSquaredSum = 0.0
    private var magCount = 0
    
    // Скользящее среднее и стандартное отклонение для аудио
    private var audioSum = 0.0
    private var audioSquaredSum = 0.0
    private var audioCount = 0
    
    // Порог чувствительности (сколько стандартных отклонений считать аномалией)
    private val threshold = 2.5 
    
    // Фаза калибровки (первые N замеров игнорируем и просто собираем статистику)
    private val calibrationSamples = 15
    private var isCalibrated = false

    data class DetectionResult(
        val isAnomaly: Boolean,
        val severity: String, // "НОРМА", "ВНИМАНИЕ", "АНОМАЛИЯ"
        val reason: String
    )

    fun analyze(magneticTotal: Float, audioAmplitude: Float): DetectionResult {
        if (!isCalibrated) {
            magSum += magneticTotal
            magSquaredSum += (magneticTotal * magneticTotal)
            magCount++
            
            audioSum += audioAmplitude
            audioSquaredSum += (audioAmplitude * audioAmplitude)
            audioCount++
            
            if (magCount >= calibrationSamples) {
                isCalibrated = true
            }
            return DetectionResult(false, "КАЛИБРОВКА...", "Сбор данных о фоне комнаты")
        }

        val magMean = magSum / magCount
        val magVariance = (magSquaredSum / magCount) - (magMean * magMean)
        val magStdDev = sqrt(maxOf(0.0, magVariance))
        
        val audioMean = audioSum / audioCount
        val audioVariance = (audioSquaredSum / audioCount) - (audioMean * audioMean)
        val audioStdDev = sqrt(maxOf(0.0, audioVariance))

        // Проверяем отклонения
        val isMagSpike = magStdDev > 0 && (magneticTotal > magMean + (threshold * magStdDev))
        val isAudioSpike = audioStdDev > 0 && (audioAmplitude > audioMean + (threshold * audioStdDev))

        return when {
            isMagSpike && isAudioSpike -> {
                DetectionResult(true, "⚠️ АНОМАЛИЯ", "Обнаружена активная электроника (Магнит + Ультразвук)")
            }
            isMagSpike -> {
                DetectionResult(false, "ВНИМАНИЕ", "Локальное магнитное возмущение")
            }
            isAudioSpike -> {
                DetectionResult(false, "ВНИМАНИЕ", "Высокочастотный акустический всплеск")
            }
            else -> {
                DetectionResult(false, "НОРМА", "Фоновые значения в пределах нормы")
            }
        }
    }
    
    fun reset() {
        magSum = 0.0; magSquaredSum = 0.0; magCount = 0
        audioSum = 0.0; audioSquaredSum = 0.0; audioCount = 0
        isCalibrated = false
    }
}
