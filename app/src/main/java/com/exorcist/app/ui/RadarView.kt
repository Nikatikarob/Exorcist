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
import kotlin.math.PI

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

    // Индикатор силы сигнала (0.0 - 1.0)
    var signalStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var pulseFactor = 1.0f

    init {
        val animator = ValueAnimator.ofFloat(0.8f, 1.2f, 0.8f)
        animator.duration = 1500
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { animation ->
            pulseFactor = animation.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val sectorPaint = Paint().apply {
        color = Color.parseColor("#3300F5D4")
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

    private val beaconPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val indicatorPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val indicatorBgPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
    }

    // 8 секторов
    private val numSectors = 8

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        try {
            val width = width.toFloat()
            val height = height.toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val maxRadius = (width.coerceAtMost(height) / 2f) - 80f // Оставляем место для индикатора

            // 1. Концентрические круги
            for (i in 1..4) {
                val radius = maxRadius * i / 4f
                canvas.drawCircle(centerX, centerY, radius, gridPaint)
            }

            // 2. Секторы (8 линий через 45°)
            for (i in 0 until numSectors) {
                val angle = (i * 360f / numSectors)
                val rad = Math.toRadians(angle.toDouble())
                val x = centerX + maxRadius * cos(rad).toFloat()
                val y = centerY + maxRadius * sin(rad).toFloat()
                canvas.drawLine(centerX, centerY, x, y, sectorPaint)
            }

            // 3. Свечение центра
            canvas.drawCircle(centerX, centerY, 20f * pulseFactor, centerGlowPaint)
            canvas.drawCircle(centerX, centerY, 12f, centerPaint)

            // 4. КЛАСТЕРИЗАЦИЯ: считаем аномалии по секторам
            val sectorCounts = IntArray(numSectors)
            val sectorMagSum = FloatArray(numSectors)
            val sectorAudioSum = FloatArray(numSectors)
            val sectorHasCorrelation = BooleanArray(numSectors)

            for (r in readings) {
                val isMagSpike = r.anomalyStatus.contains("ВНИМАНИЕ") && r.anomalyReason.contains("магнитное")
                val isAudioSpike = r.anomalyStatus.contains("ВНИМАНИЕ") && r.anomalyReason.contains("акустический")
                val isCorrelation = r.anomalyStatus.contains("АНОМАЛИЯ")

                if (isMagSpike || isAudioSpike || isCorrelation) {
                    // Определяем сектор по азимуту
                    val azimuth = r.azimuth.toFloat()
                    var sector = ((azimuth / 360f) * numSectors).toInt()
                    sector = ((sector % numSectors) + numSectors) % numSectors

                    sectorCounts[sector]++
                    sectorMagSum[sector] += r.magneticTotal
                    sectorAudioSum[sector] += r.audioAmplitude
                    if (isCorrelation) sectorHasCorrelation[sector] = true
                }
            }

            // 5. Рисуем МАЯКИ (только в секторах с достаточным количеством сигналов)
            val threshold = 3 // Минимум 3 сигнала в секторе = Маяк
            
            for (i in 0 until numSectors) {
                if (sectorCounts[i] >= threshold) {
                    val angle = (i * 360f / numSectors) + (360f / numSectors / 2f) // Центр сектора
                    val rad = Math.toRadians(angle.toDouble())
                    
                    // Расстояние зависит от средней силы сигнала
                    val avgMag = sectorMagSum[i] / sectorCounts[i]
                    val avgAudio = sectorAudioSum[i] / sectorCounts[i]
                    val signalStrength = if (sectorHasCorrelation[i]) {
                        (avgMag + avgAudio / 100f).coerceAtMost(100f)
                    } else if (avgMag > avgAudio / 10f) {
                        avgMag.coerceAtMost(100f)
                    } else {
                        (avgAudio / 10f).coerceAtMost(100f)
                    }
                    
                    val distance = (signalStrength / 100f) * maxRadius * 0.8f
                    
                    val x = centerX + distance * sin(rad).toFloat()
                    val y = centerY - distance * cos(rad).toFloat()
                    
                    // Размер Маяка зависит от количества сигналов
                    val baseRadius = 25f + (sectorCounts[i] * 3f).coerceAtMost(30f)
                    val radius = baseRadius * pulseFactor
                    
                    // Цвет Маяка
                    when {
                        sectorHasCorrelation[i] -> {
                            beaconPaint.color = Color.parseColor("#EEFF0000")
                            beaconPaint.setShadowLayer(30f * pulseFactor, 0f, 0f, Color.parseColor("#FF0000"))
                        }
                        avgMag > avgAudio / 10f -> {
                            beaconPaint.color = Color.parseColor("#AAFF006E")
                            beaconPaint.setShadowLayer(20f * pulseFactor, 0f, 0f, Color.parseColor("#FF006E"))
                        }
                        else -> {
                            beaconPaint.color = Color.parseColor("#AAFEE402")
                            beaconPaint.setShadowLayer(20f * pulseFactor, 0f, 0f, Color.parseColor("#FEE402"))
                        }
                    }
                    
                    canvas.drawCircle(x, y, radius, beaconPaint)
                    beaconPaint.clearShadowLayer()
                }
            }

            // 6. Индикатор силы сигнала (полоска внизу)
            val indicatorHeight = 40f
            val indicatorY = height - indicatorHeight - 20f
            val indicatorWidth = width - 40f
            val indicatorX = 20f
            
            // Фон
            canvas.drawRect(indicatorX, indicatorY, indicatorX + indicatorWidth, indicatorY + indicatorHeight, indicatorBgPaint)
            
            // Заполнение
            val fillWidth = indicatorWidth * signalStrength
            val fillColor = when {
                signalStrength > 0.7f -> Color.parseColor("#FF0000")
                signalStrength > 0.4f -> Color.parseColor("#FEE402")
                else -> Color.parseColor("#00F5D4")
            }
            indicatorPaint.color = fillColor
            canvas.drawRect(indicatorX, indicatorY, indicatorX + fillWidth, indicatorY + indicatorHeight, indicatorPaint)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
