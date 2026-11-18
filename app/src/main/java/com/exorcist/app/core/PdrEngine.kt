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

class MapMark(val x: Float, val y: Float, val kind: Int, val value: Float, val tMs: Long) {
    companion object {
        const val MAG = 0
        const val TONE = 1
    }
}

class PdrEngine(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

    @Volatile var strideM: Float = 0.65f
    @Volatile var magThreshold: Float = 4f

    var posX = 0f
        private set
    var posY = 0f
        private set
    var headingDeg = 0f
        private set
    var steps = 0
        private set
    var magDev = 0f
        private set
    val path = ArrayList<Pair<Float, Float>>()
    val marks = ArrayList<MapMark>()

    private var headingRad = 0f
    private var baseMag = Float.NaN
    private var devSince = 0L
    private var lastMarkMag = 0L
    private var lastMarkTone = 0L

    fun start() {
        stop()
        posX = 0f; posY = 0f; steps = 0
        headingRad = 0f; headingDeg = 0f
        baseMag = Float.NaN; devSince = 0L
        lastMarkMag = 0L; lastMarkTone = 0L
        synchronized(path) { path.clear(); path.add(Pair(0f, 0f)) }
        synchronized(marks) { marks.clear() }
        stepSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        try { sm.unregisterListener(this) } catch (e: Exception) {}
    }

    override fun onSensorChanged(e: SensorEvent?) {
        val ev = e ?: return
        when (ev.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> updateHeading(ev.values)
            Sensor.TYPE_STEP_DETECTOR -> onStep()
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> onMag(ev.values)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun updateHeading(v: FloatArray) {
        if (v.size < 4) return
        val x = v[0]; val y = v[1]; val z = v[2]; val w = v[3]

        val topE = 2f * (x * y - w * z)
        val topN = 1f - 2f * (x * x + z * z)
        val backE = -2f * (x * z + w * y)
        val backN = -2f * (y * z - w * x)

        val topH = hypot(topE, topN)
        val backH = hypot(backE, backN)

        val h = if (topH >= backH) atan2(topE, topN) else atan2(backE, backN)

        var diff = h - headingRad
        while (diff > Math.PI) diff -= 2.0 * Math.PI
        while (diff < -Math.PI) diff += 2.0 * Math.PI
        headingRad = (headingRad + 0.25f * diff).toFloat()
        var deg = Math.toDegrees(headingRad.toDouble()).toFloat()
        if (deg < 0f) deg += 360f
        headingDeg = deg
    }

    private fun onStep() {
        steps++
        posX += strideM * sin(headingRad)
        posY += strideM * cos(headingRad)
        synchronized(path) { path.add(Pair(posX, posY)) }
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

        val now = System.currentTimeMillis()
        if (abs(dev) > magThreshold) {
            if (devSince == 0L) {
                devSince = now
            } else if (now - devSince > 250L && now - lastMarkMag > 1500L) {
                addMark(MapMark.MAG, abs(dev))
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
            addMark(MapMark.TONE, khz)
            lastMarkTone = now
        }
    }

    private fun addMark(kind: Int, value: Float) {
        synchronized(marks) { marks.add(MapMark(posX, posY, kind, value, System.currentTimeMillis())) }
    }

    fun pathLengthM(): Float {
        var l = 0f
        synchronized(path) {
            for (i in 1 until path.size) {
                val a = path[i - 1]; val b = path[i]
                l += hypot(b.first - a.first, b.second - a.second)
            }
        }
        return l
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("=== КАРТА КОМНАТЫ ===")
        sb.appendLine("Шагов: " + steps + " · длина пути: " +
                String.format(java.util.Locale.US, "%.1f", pathLengthM()) + " м · шаг " + strideM + " м")
        sb.appendLine("Меток: " + marks.size)
        synchronized(marks) {
            for (m in marks) {
                if (m.kind == MapMark.MAG) {
                    sb.appendLine("   МАГНИТ  x=" + String.format(java.util.Locale.US, "%.2f", m.x) +
                            " y=" + String.format(java.util.Locale.US, "%.2f", m.y) +
                            " · " + String.format(java.util.Locale.US, "%.1f", m.value) + " мкТл сверх базлайна")
                } else {
                    sb.appendLine("   ТОН     x=" + String.format(java.util.Locale.US, "%.2f", m.x) +
                            " y=" + String.format(java.util.Locale.US, "%.2f", m.y) +
                            " · " + String.format(java.util.Locale.US, "%.2f", m.value) + " кГц")
                }
            }
        }
        return sb.toString()
    }
}
