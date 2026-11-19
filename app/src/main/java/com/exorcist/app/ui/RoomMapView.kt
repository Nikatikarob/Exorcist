package com.exorcist.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.exorcist.app.core.MapMark
import com.exorcist.app.core.PdrEngine
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RoomMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density
    private var engine: PdrEngine? = null

    private val grid = Paint().apply {
        color = Color.parseColor("#1C2C38"); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val wallFill = Paint().apply {
        color = Color.parseColor("#142E7BFF"); style = Paint.Style.FILL
    }
    private val wallStroke = Paint().apply {
        color = Color.parseColor("#2E7BFF"); style = Paint.Style.STROKE
        strokeWidth = 2.6f * d; isAntiAlias = true
    }
    private val lineP = Paint().apply {
        color = Color.parseColor("#FF2E88"); style = Paint.Style.STROKE
        strokeWidth = 2f * d; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
    }
    private val clusFill = Paint().apply {
        color = Color.parseColor("#28FFB020"); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val clusStroke = Paint().apply {
        color = Color.parseColor("#FFB020"); style = Paint.Style.STROKE
        strokeWidth = 1.4f * d; isAntiAlias = true
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
    private val wallObj = Path()

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

            val wp = e.wallPoly
            val lns = e.magLines
            val cls = e.clusters

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
            if (wp != null) for (p in wp.pts) {
                minX = min(minX, p.first); maxX = max(maxX, p.first)
                minY = min(minY, p.second); maxY = max(maxY, p.second)
            }
            for (l in lns) {
                minX = min(minX, l.x1); maxX = max(maxX, l.x2)
                minY = min(minY, l.y1); maxY = max(maxY, l.y2)
                minX = min(minX, l.x2); maxX = max(maxX, l.x1)
                minY = min(minY, l.y2); maxY = max(maxY, l.y1)
            }
            for (c in cls) {
                minX = min(minX, c.x); maxX = max(maxX, c.x)
                minY = min(minY, c.y); maxY = max(maxY, c.y)
            }
            minX -= 1f; maxX += 1f; minY -= 1f; maxY += 1f
            val spanX = max(maxX - minX, 1.5f)
            val spanY = max(maxY - minY, 1.5f)
            val pad = 14f * d
            val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY)
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f

            var gx = kotlin.math.floor(minX).toInt()
            while (gx <= kotlin.math.ceil(maxX)) {
                val x = w / 2f + (gx - cx) * scale
                canvas.drawLine(x, 0f, x, h, grid)
                gx++
            }
            var gy = kotlin.math.floor(minY).toInt()
            while (gy <= kotlin.math.ceil(maxY)) {
                val y = h / 2f - (gy - cy) * scale
                canvas.drawLine(0f, y, w, y, grid)
                gy++
            }

            if (wp != null && wp.pts.size >= 3) {
                wallObj.reset()
                for (i in wp.pts.indices) {
                    val x = w / 2f + (wp.pts[i].first - cx) * scale
                    val y = h / 2f - (wp.pts[i].second - cy) * scale
                    if (i == 0) wallObj.moveTo(x, y) else wallObj.lineTo(x, y)
                }
                wallObj.close()
                canvas.drawPath(wallObj, wallFill)
                canvas.drawPath(wallObj, wallStroke)
            }

            for (l in lns) {
                canvas.drawLine(
                    w / 2f + (l.x1 - cx) * scale, h / 2f - (l.y1 - cy) * scale,
                    w / 2f + (l.x2 - cx) * scale, h / 2f - (l.y2 - cy) * scale, lineP
                )
            }

            for (c in cls) {
                val x = w / 2f + (c.x - cx) * scale
                val y = h / 2f - (c.y - cy) * scale
                val r = max(0.35f * scale, 10f * d)
                canvas.drawCircle(x, y, r, clusFill)
                canvas.drawCircle(x, y, r, clusStroke)
                canvas.drawText("×" + c.count, x + r + 3f * d, y + 4f * d, lbl)
            }

            pathObj.reset()
            for (i in pts.indices) {
                val x = w / 2f + (pts[i].first - cx) * scale
                val y = h / 2f - (pts[i].second - cy) * scale
                if (i == 0) pathObj.moveTo(x, y) else pathObj.lineTo(x, y)
            }
            canvas.drawPath(pathObj, trace)

            for (m in mks) {
                val x = w / 2f + (m.x - cx) * scale
                val y = h / 2f - (m.y - cy) * scale
                if (m.kind == MapMark.MAG) {
                    val r = (5f + m.value * 1.0f).coerceAtMost(22f) * d
                    canvas.drawCircle(x, y, r, magP)
                } else {
                    canvas.drawCircle(x, y, 9f * d, toneP)
                    canvas.drawText(String.format(java.util.Locale.US, "%.1f", m.value), x + 11f * d, y + 4f * d, lbl)
                }
            }

            val mx = w / 2f + (e.posX - cx) * scale
            val my = h / 2f - (e.posY - cy) * scale
            canvas.drawCircle(mx, my, 6f * d, meP)
            val hr = Math.toRadians(e.headingDeg.toDouble())
            canvas.drawLine(mx, my, mx + 20f * d * sin(hr).toFloat(), my - 20f * d * cos(hr).toFloat(), headP)

            canvas.drawText("шагов " + e.steps + " · курс " + e.headingDeg.toInt() + "°", 8f * d, 16f * d, lbl)
            canvas.drawText("клетка 1 м · синий контур · пунктир магнит · янтарь кластеры", 8f * d, h - 8f * d, txt)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}
