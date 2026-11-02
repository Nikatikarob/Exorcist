package com.exorcist.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.exorcist.app.data.SensorReading
import kotlin.math.cos
import kotlin.math.sin

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var readings: List<SensorReading> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val centerPaint = Paint().apply {
        color = Color.parseColor("#00F5D4")
        style = Paint.Style.FILL
    }

    private val auraPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        try {
            val width = width.toFloat()
            val height = height.toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val maxRadius = (width.coerceAtMost(height) / 2f) - 60f

            // 1. Рисуем сетку (концентрические круги)
            for (i in 1..4) {
                val radius = maxRadius * i / 4f
                canvas.drawCircle(centerX, centerY, radius, gridPaint)
            }

            // 2. Рисуем линии направлений (0°, 90°, 180°, 270°)
            for (angle in 0..3) {
                val rad = Math.toRadians((angle * 90).toDouble())
                val x = centerX + maxRadius * cos(rad).toFloat()
                val y = centerY + maxRadius * sin(rad).toFloat()
                canvas.drawLine(centerX, centerY, x, y, gridPaint)
            }

            // 3. Рисуем центр (ты)
            canvas.drawCircle(centerX, centerY, 12f, centerPaint)

            // 4. Рисуем ауры аномалий
            for (r in readings) {
                val isMagSpike = r.anomalyStatus.contains("ВНИМАНИЕ") && r.anomalyReason.contains("магнитное")
                val isAudioSpike = r.anomalyStatus.contains("ВНИМАНИЕ") && r.anomalyReason.contains("акустический")
                val isCorrelation = r.anomalyStatus.contains("АНОМАЛИЯ")

                if (isMagSpike || isAudioSpike || isCorrelation) {
                    // Азимут в радианах (0° = север, по часовой стрелке)
                    val azimuthRad = Math.toRadians(r.azimuth)
                    
                    // Расстояние от центра зависит от силы сигнала
                    val signalStrength = if (isCorrelation) {
                        (r.magneticTotal + r.audioAmplitude / 100f).coerceAtMost(100f)
                    } else if (isMagSpike) {
                        r.magneticTotal.coerceAtMost(100f)
                    } else {
                        (r.audioAmplitude / 10f).coerceAtMost(100f)
                    }
                    
                    val distance = (signalStrength / 100f) * maxRadius
                    
                    // Вычисляем координаты точки
                    val x = centerX + distance * sin(azimuthRad).toFloat()
                    val y = centerY - distance * cos(azimuthRad).toFloat()
                    
                    // Размер и цвет ауры
                    val radius = if (isCorrelation) 20f else 12f
                    
                    when {
                        isCorrelation -> {
                            auraPaint.color = Color.parseColor("#CCFF0000")
                            auraPaint.setShadowLayer(25f, 0f, 0f, Color.parseColor("#FF0000"))
                        }
                        isMagSpike -> {
                            auraPaint.color = Color.parseColor("#80FF006E")
                            auraPaint.setShadowLayer(15f, 0f, 0f, Color.parseColor("#FF006E"))
                        }
                        isAudioSpike -> {
                            auraPaint.color = Color.parseColor("#80FEE402")
                            auraPaint.setShadowLayer(15f, 0f, 0f, Color.parseColor("#FEE402"))
                        }
                    }
                    
                    canvas.drawCircle(x, y, radius, auraPaint)
                    auraPaint.clearShadowLayer()
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
