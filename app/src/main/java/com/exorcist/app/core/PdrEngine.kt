package com.exorcist.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class MapMark(val x: Float, val y: Float, val kind: Int, val value: Float, val tMs: Long, val conf: Int = 1) {
    companion object {
        const val MAG = 0
        const val TONE = 1
    }
}

class WallPoly(val pts: ArrayList<Pair<Float, Float>>, val closed: Boolean)
class MagLine(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
class Cluster(val x: Float, val y: Float, val count: Int, val strength: Float)

class PdrEngine(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val geoSensor = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
    private val gravSensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    @Volatile var strideBaseM: Float = 0.65f
    @Volatile var magThreshold: Float = 4f
    @Volatile var toneLocked: Boolean = false
    @Volatile var standoffM: Float = 0.45f

    @Volatile var wallPoly: WallPoly? = null
        private set
    @Volatile var magLines: List<MagLine> = emptyList()
        private set
    @Volatile var clusters: List<Cluster> = emptyList()
        private set
    var closureApplied = false
        private set
    var closureGapM = 0f
        private set

    var posX = 0f
        private set
    var posY = 0f
        private set
    var headingDeg = 0f
        private set
    var fusedHeadingDeg = 0f
        private set
    var steps = 0
        private set
    var magDev = 0f
        private set
    var acVar = 0f
        private set
    var gripClass = 0
        private set
    var gripChanges = 0
        private set
    var strideAvg = 0f
        private set
    val path = ArrayList<Pair<Float, Float>>()
    val marks = ArrayList<MapMark>()

    var stepRegistered = false; private set
    var rotRegistered = false; private set
    var geoRegistered = false; private set
    var magRegistered = false; private set
    var gravRegistered = false; private set
    var linRegistered = false; private set
    var stepEvents = 0; private set
    var rotEvents = 0; private set
    var geoEvents = 0; private set
    var magEvents = 0; private set

    private var headingRad = 0f
    private var geoOffsetRad = 0f
    private var baseMag = Float.NaN
    private var magPrev = Float.NaN
    private var devSince = 0L
    private var acSince = 0L
    private var lastMarkMag = 0L
    private var lastMarkTone = 0L
    private var linMax = -1e9f
    private var linMin = 1e9f
    private var strideSum = 0f
    private var strideN = 0
    private var lastGrip = 0

    fun start() {
        stop()
        posX = 0f; posY = 0f; steps = 0
        headingRad = 0f; headingDeg = 0f; fusedHeadingDeg = 0f
        geoOffsetRad = 0f
        baseMag = Float.NaN; magPrev = Float.NaN
        devSince = 0L; acSince = 0L
        lastMarkMag = 0L; lastMarkTone = 0L
        linMax = -1e9f; linMin = 1e9f
        strideSum = 0f; strideN = 0; strideAvg = strideBaseM
        gripClass = 0; lastGrip = 0; gripChanges = 0
        acVar = 0f
        closureApplied = false; closureGapM = 0f
        wallPoly = null; magLines = emptyList(); clusters = emptyList()
        stepEvents = 0; rotEvents = 0; geoEvents = 0; magEvents = 0
        synchronized(path) { path.clear(); path.add(Pair(0f, 0f)) }
        synchronized(marks) { marks.clear() }

        stepRegistered = stepSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) } ?: false
        rotRegistered = rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
        geoRegistered = geoSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false
        magRegistered = magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
        gravRegistered = gravSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } ?: false
        linRegistered = linSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: false
    }

    fun stop() {
        analyze()
        try { sm.unregisterListener(this) } catch (e: Exception) {}
    }

    override fun onSensorChanged(e: SensorEvent?) {
        val ev = e ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> { rotEvents++; updateRelative(ev.values) }
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> { geoEvents++; updateGeoOffset(ev.values) }
            Sensor.TYPE_STEP_DETECTOR -> { stepEvents++; onStep() }
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> { magEvents++; onMag(ev.values) }
            Sensor.TYPE_GRAVITY -> onGravity(ev.values)
            Sensor.TYPE_LINEAR_ACCELERATION -> onLin(ev.values)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun projectHeading(x: Float, y: Float, z: Float, w: Float): Float {
        val topE = 2f * (x * y - w * z)
        val topN = 1f - 2f * (x * x + z * z)
        val backE = -2f * (x * z + w * y)
        val backN = -2f * (y * z - w * x)
        val topH = hypot(topE, topN)
        val backH = hypot(backE, backN)
        return if (topH >= backH) atan2(topE.toDouble(), topN.toDouble()).toFloat()
        else atan2(backE.toDouble(), backN.toDouble()).toFloat()
    }

    private fun updateRelative(v: FloatArray) {
        if (v.size < 4) return
        val h = projectHeading(v[0], v[1], v[2], v[3])
        val diff = wrap(h - headingRad)
        headingRad += 0.25f * diff
        headingDeg = toDeg(headingRad)
        fusedHeadingDeg = toDeg(headingRad + geoOffsetRad)
    }

    private fun updateGeoOffset(v: FloatArray) {
        if (v.size < 4) return
        val geoH = projectHeading(v[0], v[1], v[2], v[3])
        val innov = wrap(geoH - (headingRad + geoOffsetRad))
        if (abs(innov) < 0.45f) geoOffsetRad += 0.02f * innov
        fusedHeadingDeg = toDeg(headingRad + geoOffsetRad)
    }

    private fun onGravity(v: FloatArray) {
        if (v.size < 3) return
        val g = if (abs(v[2]) > 7f) 1 else if (abs(v[1]) > 7f) 2 else 3
        gripClass = g
        if (lastGrip != 0 && g != lastGrip && steps > 3) gripChanges++
        lastGrip = g
    }

    private fun onLin(v: FloatArray) {
        if (v.size < 3) return
        val m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (m > linMax) linMax = m
        if (m < linMin) linMin = m
    }

    private fun onStep() {
        var stride = strideAvg
        val a = linMax - linMin
        if (a > 1f && linRegistered) {
            val w = 0.40f * sqrt(sqrt(a))
            stride = w.coerceIn(0.40f, 0.90f)
        }
        linMax = -1e9f
        linMin = 1e9f
        strideSum += stride
        strideN++
        strideAvg = strideSum / strideN

        steps++
        posX += stride * sin(headingRad)
        posY += stride * cos(headingRad)
        synchronized(path) { path.add(Pair(posX, posY)) }
        if (steps % 4 == 0) analyze()
    }

    private fun onMag(v: FloatArray) {
        if (v.size < 6) return
        val cx = v[0] - v[3]
        val cy = v[1] - v[4]
        val cz = v[2] - v[5]
        val m = sqrt(cx * cx + cy * cy + cz * cz)

        if (baseMag.isNaN()) baseMag = m else baseMag += 0.002f * (m - baseMag)
        val dev = m - baseMag
        magDev = dev

        if (!magPrev.isNaN()) {
            val d = m - magPrev
            acVar = 0.95f * acVar + 0.05f * d * d
        }
        magPrev = m

        val now = System.currentTimeMillis()
        val acHot = acVar > 0.35f
        acSince = if (acHot) { if (acSince == 0L) now else acSince } else 0L

        if (abs(dev) > magThreshold) {
            if (devSince == 0L) {
                devSince = now
            } else if (now - devSince > 250L && now - lastMarkMag > 1500L) {
                var conf = 1
                if (toneLocked) conf++
                if (acHot) conf++
                if (abs(dev) > 2f * magThreshold) conf++
                addMark(MapMark.MAG, abs(dev), conf)
                lastMarkMag = now
                devSince = now
            }
        } else {
            devSince = 0L
        }
    }

    fun noteTone(khz: Float) {
        val now = System.currentTimeMillis()
        if (now - lastMarkTone > 2000L) {
            var conf = 2
            if (abs(magDev) > magThreshold) conf++
            addMark(MapMark.TONE, khz, conf)
            lastMarkTone = now
        }
    }

    private fun addMark(kind: Int, value: Float, conf: Int) {
        synchronized(marks) { marks.add(MapMark(posX, posY, kind, value, System.currentTimeMillis(), conf)) }
    }

    // ================= АНАЛИЗ ФОРМЫ =================

    private fun lengthOf(p: List<Pair<Float, Float>>): Float {
        var l = 0f
        for (i in 1 until p.size) l += hypot(p[i].first - p[i - 1].first, p[i].second - p[i - 1].second)
        return l
    }

    private fun dirOf(p: List<Pair<Float, Float>>, i: Int, j: Int): Float? {
        if (i < 0 || j >= p.size) return null
        val dx = p[j].first - p[i].first
        val dy = p[j].second - p[i].second
        if (hypot(dx, dy) < 0.15f) return null
        return atan2(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun analyze() {
        try {
            val raw = ArrayList<Pair<Float, Float>>()
            synchronized(path) { raw.addAll(path) }
            if (raw.size < 8) {
                wallPoly = null
                return
            }
            val len = lengthOf(raw)
            val first = raw[0]
            val last = raw[raw.size - 1]
            val gap = hypot(last.first - first.first, last.second - first.second)
            closureGapM = gap

            var pts = raw
            closureApplied = false
            if (len > 5f && gap > 0.05f && gap < 0.25f * len) {
                val cvx = last.first - first.first
                val cvy = last.second - first.second
                val n = raw.size
                pts = ArrayList(n)
                for (i in 0 until n) {
                    val t = i.toFloat() / (n - 1)
                    pts.add(Pair(raw[i].first - cvx * t, raw[i].second - cvy * t))
                }
                closureApplied = true
            }

            // Углы
            val corners = ArrayList<Int>()
            corners.add(0)
            var lastCorner = 0
            for (i in 2 until pts.size - 2) {
                val a = dirOf(pts, i - 2, i) ?: continue
                val b = dirOf(pts, i, i + 2) ?: continue
                val d = abs(wrap(b - a))
                if (d > 1.05f) {
                    var dist = 0f
                    for (k in lastCorner until i) dist += hypot(pts[k + 1].first - pts[k].first, pts[k + 1].second - pts[k].second)
                    if (dist > 1.2f) {
                        corners.add(i)
                        lastCorner = i
                    }
                }
            }
            corners.add(pts.size - 1)

            val poly = ArrayList<Pair<Float, Float>>()
            for (c in corners) {
                val p = pts[c]
                if (poly.isEmpty() || hypot(p.first - poly[poly.size - 1].first, p.second - poly[poly.size - 1].second) > 0.5f) {
                    poly.add(p)
                }
            }
            if (poly.size < 3) {
                wallPoly = null
            } else {
                val mks = ArrayList<MapMark>()
                synchronized(marks) { mks.addAll(mks@ mks) }
                val lines = fitLines(mks.filter { it.kind == MapMark.MAG }.map { Pair(it.x, it.y) })
                val off = offsetAndSnap(poly, standoffM, lines)
                wallPoly = WallPoly(off, closureApplied)
                magLines = lines
                clusters = clusterRest(mks, lines)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun offsetAndSnap(poly: List<Pair<Float, Float>>, d: Float, lines: List<MagLine>): ArrayList<Pair<Float, Float>> {
        val n = poly.size
        val out = ArrayList<Pair<Float, Float>>()
        var area = 0f
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            area += a.first * b.second - b.first * a.second
        }
        val ccw = area > 0f
        val ex = FloatArray(n); val ey = FloatArray(n)
        val ox = FloatArray(n); val oy = FloatArray(n)
        val px = FloatArray(n); val py = FloatArray(n)
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            var dx = b.first - a.first; var dy = b.second - a.second
            val l = hypot(dx, dy)
            if (l < 1e-6f) { dx = 1f; dy = 0f } else { dx /= l; dy /= l }
            ex[i] = dx; ey[i] = dy
            var nx = ey[i]; var ny = -ex[i]
            if (!ccw) { nx = -nx; ny = -ny }
            ox[i] = nx; oy[i] = ny
            var dd = d
            val mx = a.first + dx * l / 2f
            val my = a.second + dy * l / 2f
            for (ln in lines) {
                var ux = ln.x2 - ln.x1; var uy = ln.y2 - ln.y1
                val ul = hypot(ux, uy)
                if (ul < 0.5f) continue
                ux /= ul; uy /= ul
                val ang = abs(wrap(atan2(ux.toDouble(), uy.toDouble()).toFloat() - atan2(dx.toDouble(), dy.toDouble()).toFloat()))
                val parallel = ang < 0.35f || ang > Math.PI.toFloat() - 0.35f
                if (!parallel) continue
                val delta = (ln.x1 - mx) * nx + (ln.y1 - my) * ny
                if (delta > 0.05f && delta < 1.5f) { dd = delta; break }
            }
            px[i] = a.first + nx * dd
            py[i] = a.second + ny * dd
        }
        for (i in 0 until n) {
            val j = (i - 1 + n) % n
            val cross = ex[j] * ey[i] - ey[j] * ex[i]
            if (abs(cross) < 0.2f) {
                out.add(Pair((px[i] + px[j]) / 2f, (py[i] + py[j]) / 2f))
            } else {
                val wx = px[i] - px[j]
                val wy = py[i] - py[j]
                val t = (wx * ey[i] - wy * ex[i]) / cross
                out.add(Pair(px[j] + t * ex[j], py[j] + t * ey[j]))
            }
        }
        return out
    }

    private fun fitLines(pts: List<Pair<Float, Float>>): List<MagLine> {
        val res = ArrayList<MagLine>()
        val pool = ArrayList(pts)
        val rnd = java.util.Random(7)
        repeat(3) {
            if (pool.size < 3) return@repeat
            var best: ArrayList<Int>? = null
            repeat(60) {
                if (pool.size < 2) return@repeat
                val i = rnd.nextInt(pool.size)
                val j = rnd.nextInt(pool.size)
                if (i == j) return@repeat
                val a = pool[i]; val b = pool[j]
                var ux = b.first - a.first; var uy = b.second - a.second
                val l = hypot(ux, uy)
                if (l < 0.6f) return@repeat
                ux /= l; uy /= l
                val inl = ArrayList<Int>()
                for (k in pool.indices) {
                    val p = pool[k]
                    val wx = p.first - a.first; val wy = p.second - a.second
                    if (abs(wx * uy - wy * ux) < 0.25f) inl.add(k)
                }
                if (inl.size >= 3 && (best == null || inl.size > best.size)) best = inl
            }
            val bs = best ?: return@repeat
            var fi = 0; var fj = 0
            var md = -1f
            for (x in bs.indices) for (y in x + 1 until bs.size) {
                val dd = hypot(pool[bs[x]].first - pool[bs[y]].first, pool[bs[x]].second - pool[bs[y]].second)
                if (dd > md) { md = dd; fi = bs[x]; fj = bs[y] }
            }
            if (md >= 1.0f) res.add(MagLine(pool[fi].first, pool[fi].second, pool[fj].first, pool[fj].second))
            val sorted = bs.sortedDescending()
            for (k in sorted) pool.removeAt(k)
        }
        return res
    }

    private fun clusterRest(mks: List<MapMark>, lines: List<MagLine>): List<Cluster> {
        val rest = ArrayList<MapMark>()
        for (m in mks) {
            var onLine = false
            for (ln in lines) {
                var ux = ln.x2 - ln.x1; var uy = ln.y2 - ln.y1
                val l = hypot(ux, uy)
                if (l < 0.5f) continue
                ux /= l; uy /= l
                val wx = m.x - ln.x1; val wy = m.y - ln.y1
                val t = (wx * ux + wy * uy).coerceIn(0f, l)
                val px = ln.x1 + ux * t; val py = ln.y1 + uy * t
                if (hypot(m.x - px, m.y - py) < 0.3f) { onLine = true; break }
            }
            if (!onLine) rest.add(m)
        }
        val cx = ArrayList<Float>(); val cy = ArrayList<Float>()
        val cn = ArrayList<Int>(); val cs = ArrayList<Float>()
        for (m in rest) {
            var idx = -1
            for (k in cx.indices) {
                if (hypot(m.x - cx[k], m.y - cy[k]) < 0.6f) { idx = k; break }
            }
            if (idx < 0) {
                cx.add(m.x); cy.add(m.y); cn.add(1); cs.add(m.value)
            } else {
                val n = cn[idx]
                cx[idx] = (cx[idx] * n + m.x) / (n + 1)
                cy[idx] = (cy[idx] * n + m.y) / (n + 1)
                cs[idx] = (cs[idx] * n + m.value) / (n + 1)
                cn[idx] = n + 1
            }
        }
        val out = ArrayList<Cluster>()
        for (k in cx.indices) if (cn[k] >= 2) out.add(Cluster(cx[k], cy[k], cn[k], cs[k]))
        return out
    }

    private fun wrap(a: Float): Float {
        var r = a
        while (r > Math.PI) r -= (2.0 * Math.PI).toFloat()
        while (r < -Math.PI) r += (2.0 * Math.PI).toFloat()
        return r
    }

    private fun toDeg(rad: Float): Float {
        var d = Math.toDegrees(rad.toDouble()).toFloat()
        if (d < 0f) d += 360f
        return d
    }

    fun pathLengthM(): Float = lengthOf(ArrayList(path))

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("=== КАРТА КОМНАТЫ · СЛИЯНИЕ + КОНТУР ===")
        sb.appendLine("Шагов: " + steps + " · путь: " +
                String.format(java.util.Locale.US, "%.1f", pathLengthM()) + " м · шаг ср: " +
                String.format(java.util.Locale.US, "%.2f", strideAvg) + " м")
        sb.appendLine("Регистрация: шаг=" + ok(stepRegistered) + " курс=" + ok(rotRegistered) +
                " гео=" + ok(geoRegistered) + " маг=" + ok(magRegistered) +
                " хват=" + ok(gravRegistered) + " лин=" + ok(linRegistered))
        sb.appendLine("Курс: относит " + headingDeg.toInt() + "° · абсолют " + fusedHeadingDeg.toInt() + "°")
        sb.appendLine("Хват: " + gripName(gripClass) + " · смен: " + gripChanges +
                " · AC-вариация: " + String.format(java.util.Locale.US, "%.3f", acVar))
        sb.appendLine("Замыкание: " + (if (closureApplied) "ПРИМЕНЕНО" else "нет") +
                " · невязка " + String.format(java.util.Locale.US, "%.2f", closureGapM) + " м")
        val wp = wallPoly
        if (wp == null) {
            sb.appendLine("Контур: не построено (мало углов или шагов)")
        } else {
            sb.appendLine("Контур стен: " + wp.pts.size + " угла, вынос " +
                    String.format(java.util.Locale.US, "%.2f", standoffM) + " м")
            for (p in wp.pts) {
                sb.appendLine("   угол x=" + String.format(java.util.Locale.US, "%.2f", p.first) +
                        " y=" + String.format(java.util.Locale.US, "%.2f", p.second))
            }
        }
        sb.appendLine("Магнитных прямых: " + magLines.size)
        for (l in magLines) {
            sb.appendLine("   линия (" + String.format(java.util.Locale.US, "%.2f", l.x1) + "," +
                    String.format(java.util.Locale.US, "%.2f", l.y1) + ")–(" +
                    String.format(java.util.Locale.US, "%.2f", l.x2) + "," +
                    String.format(java.util.Locale.US, "%.2f", l.y2) + ")")
        }
        sb.appendLine("Кластеров объектов: " + clusters.size)
        for (c in clusters) {
            sb.appendLine("   кластер x=" + String.format(java.util.Locale.US, "%.2f", c.x) +
                    " y=" + String.format(java.util.Locale.US, "%.2f", c.y) +
                    " · меток " + c.count + " · сила " + String.format(java.util.Locale.US, "%.1f", c.strength))
        }
        sb.appendLine("Меток: " + marks.size)
        synchronized(marks) {
            for (m in marks) {
                val kind = if (m.kind == MapMark.MAG) "МАГНИТ" else "ТОН   "
                sb.appendLine("   " + kind + " x=" + String.format(java.util.Locale.US, "%.2f", m.x) +
                        " y=" + String.format(java.util.Locale.US, "%.2f", m.y) +
                        " · " + String.format(java.util.Locale.US, "%.1f", m.value) +
                        (if (m.kind == MapMark.MAG) " мкТл" else " кГц") +
                        " · уверенность " + m.conf + "/4")
            }
        }
        return sb.toString()
    }

    private fun ok(b: Boolean) = if (b) "OK" else "НЕТ"

    private fun gripName(g: Int) = when (g) {
        1 -> "плоско"
        2 -> "вертикально"
        3 -> "наклон/прочий"
        else -> "не определён"
    }
}
