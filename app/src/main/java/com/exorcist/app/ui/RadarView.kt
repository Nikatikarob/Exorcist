package com.exorcist.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
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

    // Коэффициент пульсации (0.8 - 1.2)
    private var pulseFactor = 1.0f

    init {
        // Запускаем бесконечную пульсацию
        val animator = ValueAnimator.ofFloat(0.8f, 1.2f, 0.8f)
        animator.duration = 1500 // 1.5 секунды на цикл
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { animation ->
            pulseFactor = animation.animatedValue as Float
            invalidate() // Перерисовываем каждый кадр
        }
        animator.start()
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

    private val centerGlowPaint = Paint().apply {
        color = Color.parseColor("#4000F5D4")
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

            // 1. Сетка (концентрические круги)
            for (i in 1..4) {
                val radius = maxRadius * i / 4f
                canvas.drawCircle(centerX, centerY, radius, gridPaint)
            }

            // 2. Линии направлений
            for (angle in 0..3) {
                val rad = Math.toRadians((angle * 90).toDouble())
                val x = centerX + maxRadius * cos(rad).toFloat()
                val y = centerY + maxRadius * sin(rad).toFloat()
                canvas.drawLine(centerX, centerY, x, y, gridPaint)
            }

            // 3. Свечение центра
            canvas.drawCircle(centerX, centerY, 20f * pulseFactor, centerGlowPaint)
            // 4. Центр (ты)
            canvas.drawCircle(centerX, centerY, 12f, centerPaint)

            // 5. Ауры аномалий с пульсацией
            for (r in readings) {
                val isMagSpike = r.anomalyStatus.contains("ВНИМАНИЕ") && r.anomalyReason.contains("магнитное")
                val isAudioSpike = r.anomalyStatus.contains("ВНИМАНИЕ") && r.anomalyReason.contains("акустический")
                val isCorrelation = r.anomalyStatus.contains("АНОМАЛИЯ")

                if (isMagSpike || isAudioSpike || isCorrelation) {
                    val azimuthRad = Math.toRadians(r.azimuth)
                    
                    val signalStrength = if (isCorrelation) {
                        (r.magneticTotal + r.audioAmplitude / 100f).coerceAtMost(100f)
                    } else if (isMagSpike) {
                        r.magneticTotal.coerceAtMost(100f)
                    } else {
                        (r.audioAmplitude / 10f).coerceAtMost(100f)
                    }
                    
                    val distance = (signalStrength / 100f) * maxRadius
                    
                    val x = centerX + distance * sin(azimuthRad).toFloat()
                    val y = centerY - distance * cos(azimuthRad).toFloat()
                    
                    // Пульсирующий размер
                    val baseRadius = if (isCorrelation) 20f else 12f
                    val radius = baseRadius * pulseFactor
                    
                    when {
                        isCorrelation -> {
                            auraPaint.color = Color.parseColor("#CCFF0000")
                            auraPaint.setShadowLayer(25f * pulseFactor, 0f, 0f, Color.parseColor("#FF0000"))
                        }
                        isMagSpike -> {
                            auraPaint.color = Color.parseColor("#80FF006E")
                            auraPaint.setShadowLayer(15f * pulseFactor, 0f, 0f, Color.parseColor("#FF006E"))
                        }
                        isAudioSpike -> {
                            auraPaint.color = Color.parseColor("#80FEE402")
                            auraPaint.setShadowLayer(15f * pulseFactor, 0f, 0f, Color.parseColor("#FEE402"))
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
