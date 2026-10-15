package com.exorcist.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.exorcist.app.data.SensorReading
import kotlin.math.max

class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var readings: List<SensorReading> = emptyList()
        set(value) {
            field = value
            invalidate() // Перерисовать при получении данных
        }

    private val pathPaint = Paint().apply {
        color = Color.parseColor("#00F5D4") // Бирюзовый
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val auraPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

        override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        try {
            if (readings.isEmpty()) return

            val width = width.toFloat()
            val height = height.toFloat()
            val padding = 40f

            // Находим границы пути, чтобы масштабировать его под экран
            var minX = readings.minOf { it.posX }.toFloat()
            var maxX = readings.maxOf { it.posX }.toFloat()
            var minY = readings.minOf { it.posY }.toFloat()
            var maxY = readings.maxOf { it.posY }.toFloat()

            // Добавляем отступы, чтобы путь не прилипал к краям
            val rangeX = max(maxX - minX, 1f)
            val rangeY = max(maxY - minY, 1f)
            minX -= rangeX * 0.1f
            maxX += rangeX * 0.1f
            minY -= rangeY * 0.1f
            maxY += rangeY * 0.1f

            val scaleX = (width - 2 * padding) / (maxX - minX)
            val scaleY = (height - 2 * padding) / (maxY - minY)
            val scale = minOf(scaleX, scaleY)

            val offsetX = (width - (maxX - minX) * scale) / 2 - minX * scale
            val offsetY = (height - (maxY - minY) * scale) / 2 - minY * scale

            // Функция преобразования координат
            fun mapX(x: Double) = (x.toFloat() * scale + offsetX).coerceIn(0f, width)
            fun mapY(y: Double) = (y.toFloat() * scale + offsetY).coerceIn(0f, height)

            // 1. Рисуем сетку (фон радара)
            val gridPaint = Paint().apply {
                color = Color.parseColor("#1A1A2E")
                strokeWidth = 1f
                style = Paint.Style.STROKE
            }
            for (i in 0..10) {
                val x = padding + (width - 2 * padding) * i / 10f
                val y = padding + (height - 2 * padding) * i / 10f
                canvas.drawLine(x, padding, x, height - padding, gridPaint)
                canvas.drawLine(padding, y, width - padding, y, gridPaint)
            }

            // 2. Рисуем путь и ауры
            for (i in readings.indices) {
                val r = readings[i]
                val cx = mapX(r.posX)
                val cy = mapY(r.posY)

                // Рисуем линию пути
                if (i > 0) {
                    val prev = readings[i - 1]
                    canvas.drawLine(mapX(prev.posX), mapY(prev.posY), cx, cy, pathPaint)
                }

                // Проверяем на аномалии и рисуем ауры
                val isMagSpike = r.magneticTotal > 60f
                val isAudioSpike = r.audioAmplitude > 500f

                if (isMagSpike || isAudioSpike) {
                    val radius = 15f + (r.magneticTotal / 5f).coerceAtMost(30f)
                    
                    when {
                        isMagSpike && isAudioSpike -> {
                            auraPaint.color = Color.parseColor("#80FF006E")
                            auraPaint.setShadowLayer(20f, 0f, 0f, Color.parseColor("#FF006E"))
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
                    canvas.drawCircle(cx, cy, radius, auraPaint)
                    auraPaint.clearShadowLayer()
                }
            }

            // 3. Рисуем текущую позицию (точка в конце)
            val last = readings.last()
            canvas.drawCircle(mapX(last.posX), mapY(last.posY), 8f, Paint().apply { 
                color = Color.WHITE 
                style = Paint.Style.FILL 
            })
        } catch (e: Exception) {
            // Если что-то пошло не так, просто не рисуем ничего
            e.printStackTrace()
        }
    }
