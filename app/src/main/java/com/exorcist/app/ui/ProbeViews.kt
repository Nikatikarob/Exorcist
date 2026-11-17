package com.exorcist.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.exorcist.app.core.AudioFrame
import kotlin.math.min

class SpectrumWaterfallView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object { private const val BINS = 256; private const val ROWS = 130 }

    private val d = resources.displayMetrics.density
    private val ring = IntArray(BINS * ROWS)
    private val ordered = IntArray(BINS * ROWS)
    private val bmp = Bitmap.createBitmap(BINS, ROWS, Bitmap.Config.ARGB_8888)
    private var head = 0
    private var frame: AudioFrame? = null

    private val line = Paint().apply {
        color = Color.parseColor("#00E5C0")
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        isAntiAlias = true
    }
    private val grid = Paint().apply {
        color = Color.parseColor("#1C2C38")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val mark = Paint().apply {
        color = Color.parseColor("#FF2E88")
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
    }
    private val mark2 = Paint().apply {
        color = Color.parseColor("#FF3B30")
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
    }
    private val txt = Paint().apply {
        color = Color.parseColor("#7E93A3")
        textSize = 11f * d
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val hot = Paint().apply {
        color = Color.parseColor("#FF6B6B")
        textSize = 12f * d
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    private val bmpP = Paint().apply { isFilterBitmap = false }
    private val path = Path()

    init { ring.fill(Color.rgb(7, 12, 17)) }

    fun submit(f: AudioFrame?) {
        frame = f
        if (f != null && f.display.size == BINS) {
            val base = head * BINS
            for (x in 0 until BINS) ring[base + x] = colorFor(f.display[x])
            head = (head + 1) % ROWS
        }
        invalidate()
    }

    fun clear() {
        ring.fill(Color.rgb(7, 12, 17))
        head = 0
        frame = null
        invalidate()
    }

    private fun colorFor(db: Float): Int {
        val t = ((db + 110f) / 90f).coerceIn(0f, 1f)
        return when {
            t < 0.28f -> {
                val u = t / 0.28f
                Color.rgb((7 + 12 * u).toInt(), (14 + 34 * u).toInt(), (30 + 62 * u).toInt())
            }
            t < 0.55f -> {
                val u = (t - 0.28f) / 0.27f
                Color.rgb(18, (48 + 180 * u).toInt(), (92 + 110 * u).toInt())
            }
            t < 0.80f -> {
                val u = (t - 0.55f) / 0.25f
                Color.rgb((8 + 247 * u).toInt(), (228 - 52 * u).toInt(), (202 - 172 * u).toInt())
            }
            else -> {
                val u = (t - 0.80f) / 0.20f
                Color.rgb(255, (176 - 120 * u).toInt(), (30 + 18 * u).toInt())
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(Color.parseColor("#070C11"))

            val pad = 6f * d
            val lab = 15f * d
            val specH = (h - pad * 3 - lab) * 0.44f
            val top = pad
            val bot = top + specH
            val axis = bot + lab
            val wfT = axis + pad
            val wfB = h - pad
            val nyq = frame?.nyquist ?: 24000f
            val gw = w - pad * 2

            for (i in 0..4) {
                val y = top + specH * i / 4f
                canvas.drawLine(pad, y, w - pad, y, grid)
            }

            var k = 5000f
            while (k < nyq) {
                val x = pad + gw * (k / nyq)
                canvas.drawLine(x, bot, x, bot + 4f * d, grid)
                canvas.drawText((k / 1000).toInt().toString() + "k", x + 2f * d, axis - 3f * d, txt)
                k += if (nyq > 30000f) 8000f else 5000f
            }

            val x15 = pad + gw * min(15000f / nyq, 1f)
            canvas.drawLine(x15, top, x15, bot, mark)
            canvas.drawText("15k", x15 + 3f * d, top + 12f * d, hot)
            if (nyq > 26000f) {
                val x24 = pad + gw * (24000f / nyq)
                canvas.drawLine(x24, top, x24, bot, mark2)
                canvas.drawText("24k", x24 + 3f * d, top + 12f * d, hot)
            }

            val f = frame
            if (f != null && f.display.size == BINS) {
                path.reset()
                for (x in 0 until BINS) {
                    val px = pad + gw * x / (BINS - 1f)
                    val t = ((f.display[x] + 110f) / 90f).coerceIn(0f, 1f)
                    val py = bot - specH * t
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                canvas.drawPath(path, line)
                if (f.isTone) {
                    val px = pad + gw * (f.peakHz / nyq).coerceIn(0f, 1f)
                    canvas.drawLine(px, top, px, bot, mark2)
                }
            } else {
                canvas.drawText("НЕТ ПОТОКА", pad + 8f * d, top + specH / 2f, txt)
            }

            for (r in 0 until ROWS) {
                val src = ((head - 1 - r) % ROWS + ROWS) % ROWS
                System.arraycopy(ring, src * BINS, ordered, r * BINS, BINS)
            }
            bmp.setPixels(ordered, 0, BINS, 0, 0, BINS, ROWS)
            canvas.drawBitmap(bmp, null, Rect(pad.toInt(), wfT.toInt(), (w - pad).toInt(), wfB.toInt()), bmpP)
            canvas.drawLine(x15, wfT, x15, wfB, mark)
            if (nyq > 26000f) {
                val x24 = pad + gw * (24000f / nyq)
                canvas.drawLine(x24, wfT, x24, wfB, mark2)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density
    private var data: FloatArray = FloatArray(0)
    private var accent: Int = Color.parseColor("#00E5C0")
    private val p = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        isAntiAlias = true
    }
    private val base = Paint().apply {
        color = Color.parseColor("#1C2C38")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val path = Path()

    fun submit(values: FloatArray, color: Int) {
        data = values
        accent = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawLine(0f, h - 1f, w, h - 1f, base)
            if (data.size < 2) return
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            for (v in data) {
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mx - mn < 1e-6f) mx = mn + 1f
            path.reset()
            for (i in data.indices) {
                val x = w * i / (data.size - 1f)
                val y = h - 3f * d - (h - 6f * d) * ((data[i] - mn) / (mx - mn))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            p.color = accent
            canvas.drawPath(path, p)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
