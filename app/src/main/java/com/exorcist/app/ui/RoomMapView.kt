package com.exorcist.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.exorcist.app.core.MapMark
import com.exorcist.app.core.PdrEngine
import kotlin.math.max
import kotlin.math.min

class RoomMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density
    private var engine: PdrEngine? = null

    private val grid = Paint().apply {
        color = Color.parseColor("#1C2C38"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val trace = Paint().apply {
        color = Color.parseColor("#00E5C0"); style = Paint.Style.STROKE
        strokeWidth = 2.2f * d; isAntiAlias = true
    }
    private val magP = Paint().apply {
        color = Color.parseColor("#FF2E88"); style = Paint.Style.STROKE
        strokeWidth = 2.4f * d; isAntiAlias = true
    }
    private val toneP = Paint().apply {
        color = Color.parseColor("#FFB020"); style = Paint.Style.STROKE
        strokeWidth = 2.4f * d; isAntiAlias = true
    }
    private val meP = Paint().apply {
        color = Color.parseColor("#7CFF3D"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val headP = Paint().apply {
        color = Color.parseColor("#7CFF3D"); style = Paint.Style.STROKE
        strokeWidth = 2f * d; isAntiAlias = true
    }
    private val txt = Paint().apply {
        color = Color.parseColor("#7E93A3"); textSize = 11f * d
        typeface = Typeface.MONOSPACE; isAntiAlias = true
    }
    private val lbl = Paint().apply {
        color = Color.parseColor("#E8E4DA"); textSize = 12f * d
        typeface = Typeface.MONOSPACE; isAntiAlias = true
    }
    private val pathObj = Path()

    fun bind(e: PdrEngine) {
        engine = e
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(Color.parseColor("#070C11"))
            val e = engine
            if (e == null) {
                canvas.drawText("НЕТ ДАННЫХ · НАЖМИ СТАРТ", 12f * d, h / 2f, txt)
                return
            }

            val pts = ArrayList<Pair<Float, Float>>()
            synchronized(e.path) { pts.addAll(e.path) }
            val mks = ArrayList<MapMark>()
            synchronized(e.marks) { mks.addAll(e.marks) }
            pts.add(Pair(e.posX, e.posY))

            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (p in pts) {
                minX = min(minX, p.first); maxX = max(maxX, p.first)
                minY = min(minY, p.second); maxY = max(maxY, p.second)
            }
            for (m in mks) {
                minX = min(minX, m.x); maxX = max(maxX, m.x)
                minY = min(minY, m.y); maxY = max(maxY, m.y)
            }
            minX -= 1f; maxX += 1f; minY -= 1f; maxY += 1f
            val spanX = max(maxX - minX, 1.5f)
            val spanY = max(maxY - minY, 1.5f)
            val pad = 14f * d
            val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f

            fun sx(x: Float) = w / 2f + (x - cx) * scale
            fun sy(y: Float) = h / 2f - (y - cy) * scale

            var gx = kotlin.math.floor(minX).toInt()
            while (gx <= kotlin.math.ceil(maxX)) {
                val x = sx(gx.toFloat())
                canvas.drawLine(x, 0f, x, h, grid)
                gx++
            }
            var gy = kotlin.math.floor(minY).toInt()
            while (gy <= kotlin.math.ceil(maxY)) {
                val y = sy(gy.toFloat())
                canvas.drawLine(0f, y, w, y, grid)
                gy++
            }
            canvas.drawText("клетка = 1 м", 8f * d, h - 8f * d, txt)

            pathObj.reset()
            for (i in pts.indices) {
                val x = sx(pts[i].first); val y = sy(pts[i].second)
                if (i == 0) pathObj.moveTo(x, y) else pathObj.lineTo(x, y)
            }
            canvas.drawPath(pathObj, trace)

            for (m in mks) {
                val x = sx(m.x); val y = sy(m.y)
                if (m.kind == MapMark.MAG) {
                    val r = (6f + m.value * 1.2f).coerceAtMost(26f) * d
                    canvas.drawCircle(x, y, r, magP)
                    canvas.drawCircle(x, y, r * 0.45f, magP)
                } else {
                    val r = 9f * d
                    canvas.drawCircle(x, y, r, toneP)
                    canvas.drawText(String.format(java.util.Locale.US, "%.1f", m.value), x + r + 3f * d, y + 4f * d, lbl)
                }
            }

            val mx = sx(e.posX); val my = sy(e.posY)
            canvas.drawCircle(mx, my, 6f * d, meP)
            val hr = Math.toRadians(e.headingDeg.toDouble())
            canvas.drawLine(mx, my, mx + 20f * d * sin(hr).toFloat(), my - 20f * d * cos(hr).toFloat(), headP)

            canvas.drawText("шагов " + e.steps + " · курс " + e.headingDeg.toInt() + "°", 8f * d, 16f * d, lbl)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}
