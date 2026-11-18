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
import com.exorcist.app.core.SonarFrame
import kotlin.math.min

class SonarScopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object { private const val BINS = 240; private const val ROWS = 90 }

    private val d = resources.displayMetrics.density
    private val ring = IntArray(BINS * ROWS)
    private val ordered = IntArray(BINS * ROWS)
    private val bmp = Bitmap.createBitmap(BINS, ROWS, Bitmap.Config.ARGB_8888)
    private var head = 0
    private var frame: SonarFrame? = null
    private var maxCm = 300f

    private val trace = Paint().apply {
        color = Color.parseColor("#00E5C0"); style = Paint.Style.STROKE
        strokeWidth = 1.7f * d; isAntiAlias = true
    }
    private val fill = Paint().apply { color = Color.parseColor("#2200E5C0"); style = Paint.Style.FILL }
    private val grid = Paint().apply {
        color = Color.parseColor("#1C2C38"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val target = Paint().apply {
        color = Color.parseColor("#7CFF3D"); style = Paint.Style.STROKE; strokeWidth = 1.8f * d
    }
    private val weak = Paint().apply {
        color = Color.parseColor("#FFB020"); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val txt = Paint().apply {
        color = Color.parseColor("#7E93A3"); textSize = 11f * d
        typeface = Typeface.MONOSPACE; isAntiAlias = true
    }
    private val big = Paint().apply {
        color = Color.parseColor("#7CFF3D"); textSize = 13f * d
        typeface = Typeface.MONOSPACE; isAntiAlias = true
    }
    private val bmpP = Paint().apply { isFilterBitmap = false }
    private val path = Path()

    init { ring.fill(Color.rgb(7, 12, 17)) }

    fun submit(f: SonarFrame?, rangeCm: Float) {
        maxCm = rangeCm
        frame = f
        if (f != null && f.profile.size == BINS) {
            val base = head * BINS
            for (x in 0 until BINS) ring[base + x] = pal(f.profile[x])
            head = (head + 1) % ROWS
        }
        invalidate()
    }

    fun clear() {
        ring.fill(Color.rgb(7, 12, 17)); head = 0; frame = null; invalidate()
    }

    private fun pal(t: Float): Int {
        val v = t.coerceIn(0f, 1f)
        return when {
            v < 0.30f -> { val u = v / 0.30f; Color.rgb((7 + 12 * u).toInt(), (14 + 40 * u).toInt(), (30 + 70 * u).toInt()) }
            v < 0.60f -> { val u = (v - 0.30f) / 0.30f; Color.rgb(18, (54 + 175 * u).toInt(), (100 + 105 * u).toInt()) }
            v < 0.85f -> { val u = (v - 0.60f) / 0.25f; Color.rgb((18 + 237 * u).toInt(), (229 - 55 * u).toInt(), (205 - 175 * u).toInt()) }
            else -> { val u = (v - 0.85f) / 0.15f; Color.rgb(255, (174 - 115 * u).toInt(), (30 + 20 * u).toInt()) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(Color.parseColor("#070C11"))

            val padL = 30f * d
            val padR = 8f * d
            val padT = 6f * d
            val lab = 15f * d
            val gw = w - padL - padR
            val gh = (h - padT - lab - 10f * d) * 0.52f
            val bot = padT + gh
            val axis = bot + lab
            val wfT = axis + 6f * d
            val wfB = h - 6f * d

            for (i in 0..4) {
                val y = padT + gh * i / 4f
                canvas.drawLine(padL, y, w - padR, y, grid)
            }
            for (i in 0..5) {
                val x = padL + gw * i / 5f
                canvas.drawLine(x, padT, x, bot, grid)
                canvas.drawText((maxCm * i / 5f).toInt().toString(), x - 8f * d, axis - 3f * d, txt)
            }
            canvas.drawText("см", w - padR - 18f * d, axis - 3f * d, txt)

            val f = frame
            if (f != null && f.profile.size == BINS) {
                path.reset()
                for (x in 0 until BINS) {
                    val px = padL + gw * x / (BINS - 1f)
                    val py = bot - gh * f.profile[x].coerceIn(0f, 1f)
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                canvas.drawPath(path, trace)
                path.lineTo(padL + gw, bot)
                path.lineTo(padL, bot)
                path.close()
                canvas.drawPath(path, fill)

                if (f.heard) {
                    val frac = (f.rangeCm / maxCm).coerceIn(0f, 1f)
                    val px = padL + gw * frac
                    val p = if (f.confidence > 0.5f) target else weak
                    canvas.drawLine(px, padT, px, bot, p)
                    val lbl = f.rangeCm.toInt().toString() + " см"
                    val tw = p.measureText(lbl)
                    p.textSize = 13f * d
                    canvas.drawText(lbl, min(max(px - tw / 2f, padL), padL + gw - tw), padT + 14f * d, p)
                }
            } else {
                canvas.drawText("НЕТ ДАННЫХ · НАЖМИ СТАРТ", padL + 6f * d, padT + gh / 2f, txt)
            }

            for (r in 0 until ROWS) {
                val src = ((head - 1 - r) % ROWS + ROWS) % ROWS
                System.arraycopy(ring, src * BINS, ordered, r * BINS, BINS)
            }
            bmp.setPixels(ordered, 0, BINS, 0, 0, BINS, ROWS)
            canvas.drawBitmap(bmp, null,
                Rect(padL.toInt(), wfT.toInt(), (w - padR).toInt(), wfB.toInt()), bmpP)
            canvas.drawText("история посылки", padL + 4f * d, wfT + 12f * d, big)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
