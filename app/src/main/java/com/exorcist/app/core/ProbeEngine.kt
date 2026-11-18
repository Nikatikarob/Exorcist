package com.exorcist.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ChannelState(
    val key: String,
    val title: String,
    val hardware: String,
    val unit: String,
    val spec: String,
    val note: String,
    val accent: Int,
    val sensorType: Int
) {
    var values: FloatArray = FloatArray(0)
    var minV: Float = Float.MAX_VALUE
    var maxV: Float = -Float.MAX_VALUE
    var events: Int = 0
    var lastMs: Long = 0
    var failed: Boolean = false
    var rateUsed: String = "—"
    var baseStep: Float = Float.NaN
    val history: FloatArray = FloatArray(60)
    var histLen: Int = 0
    private var histHead: Int = 0

    fun push(v: Float) {
        if (v < minV) minV = v
        if (v > maxV) maxV = v
        history[histHead] = v
        histHead = (histHead + 1) % history.size
        if (histLen < history.size) histLen++
    }

    fun ordered(): FloatArray {
        val out = FloatArray(histLen)
        for (i in 0 until histLen) {
            val src = ((histHead - histLen + i) % history.size + history.size) % history.size
            out[i] = history[src]
        }
        return out
    }

    fun primary(): Float = if (values.isNotEmpty()) values[0] else Float.NaN

    fun stepsDelta(): Float {
        if (baseStep.isNaN() || values.isEmpty()) return Float.NaN
        return values[0] - baseStep
    }
}

class SensorProbe(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val map = LinkedHashMap<String, ChannelState>()
    private val bound = ArrayList<Sensor>()
    private val allNames = ArrayList<String>()
    private val proxDump = ArrayList<String>()

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val st = map[e.sensor.name] ?: return
            synchronized(map) {
                val n = minOf(6, e.values.size)
                val arr = FloatArray(n)
                for (i in 0 until n) arr[i] = e.values[i]
                st.values = arr
                st.events++
                st.lastMs = System.currentTimeMillis()
                if (st.baseStep.isNaN() && st.hardware == "step_count") st.baseStep = arr[0]
                st.push(arr[0])
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun trim(v: Float): String =
        if (abs(v) >= 100f) v.toInt().toString() else String.format(java.util.Locale.US, "%.4f", v)

    private fun classify(s: Sensor): ChannelState? {
        val st = s.stringType.lowercase()
        val nm = s.name.lowercase()
        val hz = if (s.minDelay > 0) (1_000_000f / s.minDelay).toInt().toString() + " Гц" else "по событию"
        val base = "type=" + s.type + " · диап " + trim(s.maximumRange) +
                " · шаг " + trim(s.resolution) + " · " + hz

        val isProx = st.contains("proximity") || s.type == Sensor.TYPE_PROXIMITY
        if (isProx) {
            val ell = nm.contains("elliptic")
            return ChannelState(
                s.name,
                if (ell) "Elliptic Proximity (УЗ)" else "Proximity прочий",
                s.name, "ед.", base,
                if (ell) "КЛЮЧЕВОЙ. Води рукой 5→150 см, смотри min/max. Совпадёт с см — готовый дальномер."
                else "Оптический, для сравнения.",
                if (ell) 0xFF7CFF3D.toInt() else 0xFF7E93A3.toInt(),
                s.type
            )
        }
        return when {
            st.contains("flicker") -> ChannelState(s.name, "Мерцание света", s.name, "Гц", base,
                "values[0] — частота. 100 Гц = 2×50, сеть.", 0xFFFFB020.toInt(), s.type)
            st.contains("cct") -> ChannelState(s.name, "Цветовая температура", s.name, "K", base,
                "Бывают выбросы >20000 K — мусор, отсекать.", 0xFF2E7BFF.toInt(), s.type)
            st.contains("rear_light") -> ChannelState(s.name, "Задняя освещённость", s.name, "лк", base,
                "Свет со стороны сцены. База для день/ночь.", 0xFF00E5C0.toInt(), s.type)
            st.contains("ambient_light_scene") -> ChannelState(s.name, "Сцена освещения", s.name, "ед.", base,
                "Класс сцены от вендора.", 0xFF0FF0FC.toInt(), s.type)
            st.contains("sar") -> ChannelState(s.name, "Ёмкостный SAR", s.name, "ед.", base,
                "Рабочее значение в values[1], не в values[0]. Состояния 0 и 5.", 0xFFFF2E88.toInt(), s.type)
            st.contains("af_ccl") -> ChannelState(s.name, "AF CCL (wide)", s.name, "ед.", base,
                "Молчит без камеры. Проверь с включённой камерой.", 0xFFA855F7.toInt(), s.type)
            st.contains("gyro_hs") -> ChannelState(s.name, "Гироскоп HS", s.name, "рад/с", base,
                "Закрыт вендором на всех частотах.", 0xFFA855F7.toInt(), s.type)
            s.type == Sensor.TYPE_GYROSCOPE -> ChannelState(s.name, "Гироскоп 200 Гц", s.name, "рад/с", base,
                "Рабочий канал вибрации через контакт.", 0xFFA855F7.toInt(), s.type)
            s.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> ChannelState(s.name, "Магнит без калибровки", s.name, "мкТл", base,
                "0-2 поле, 3-5 смещение. Вычитай смещение сам — проверено, совпадает с калиброванным.", 0xFFFF2E88.toInt(), s.type)
            s.type == Sensor.TYPE_MAGNETIC_FIELD -> ChannelState(s.name, "Магнит калиброванный", s.name, "мкТл", base,
                "Сумма ≈53 мкТл = земное поле.", 0xFFFF2E88.toInt(), s.type)
            st.contains("step_count") -> ChannelState(s.name, "Счётчик шагов", s.name, "шаг", base,
                "Накопительный за жизнь телефона. В отчёте — разница от старта.", 0xFF7CFF3D.toInt(), s.type)
            st.contains("step") -> ChannelState(s.name, "Детектор шага", s.name, "шаг", base,
                "Событие на каждый шаг. Основа PDR.", 0xFF7CFF3D.toInt(), s.type)
            st.contains("significant") -> ChannelState(s.name, "Значимое движение", s.name, "ед.", base,
                "Закрыт вендором.", 0xFF7CFF3D.toInt(), s.type)
            s.type == Sensor.TYPE_GAME_ROTATION_VECTOR -> ChannelState(s.name, "Курс без магнитометра", s.name, "кват", base,
                "4 компонента. Не портится магнитными аномалиями.", 0xFF2E7BFF.toInt(), s.type)
            s.type == Sensor.TYPE_LIGHT -> ChannelState(s.name, "Освещённость фронт", s.name, "лк", base,
                "6 значений — есть сырые каналы, датчик цветной.", 0xFFFFB020.toInt(), s.type)
            s.type == Sensor.TYPE_ACCELEROMETER -> ChannelState(s.name, "Акселерометр", s.name, "м/с²", base,
                "200 Гц. Приложи к технике — виброспектр до 100 Гц.", 0xFF00E5C0.toInt(), s.type)
            else -> null
        }
    }

    private fun tryRegister(s: Sensor): String {
        val rates = intArrayOf(
            SensorManager.SENSOR_DELAY_FASTEST,
            SensorManager.SENSOR_DELAY_GAME,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        val names = arrayOf("FASTEST", "GAME", "NORMAL")
        for (i in rates.indices) {
            val ok = try {
                sm.registerListener(listener, s, rates[i])
            } catch (e: Exception) {
                Log.w("SensorProbe", "reg ${s.name} ${names[i]}: ${e.message}")
                false
            }
            if (ok) return names[i]
        }
        return "ОТКАЗ"
    }

    fun start() {
        stop()
        allNames.clear()
        proxDump.clear()
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        for (s in all) {
            allNames.add(s.name + " [" + s.stringType + "] type=" + s.type)
            if (s.stringType.lowercase().contains("proximity") || s.type == Sensor.TYPE_PROXIMITY) {
                proxDump.add(s.name + " · type=" + s.type + " · stringType=" + s.stringType)
            }
            val cs = classify(s) ?: continue
            if (map.containsKey(cs.key)) continue
            val rate = tryRegister(s)
            cs.rateUsed = rate
            cs.failed = rate == "ОТКАЗ"
            map[cs.key] = cs
            if (!cs.failed) bound.add(s)
        }
        Log.d("SensorProbe", "list=${all.size} bound=${bound.size} prox=${proxDump.size}")
    }

    fun stop() {
        try { sm.unregisterListener(listener) } catch (e: Exception) {}
        bound.clear()
        synchronized(map) { map.clear() }
    }

    fun snapshot(): List<ChannelState> {
        synchronized(map) { return ArrayList(map.values) }
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("=== PROXIMITY · ПРИЦЕЛЬНО ===")
        sb.appendLine("Датчиков в списке: " + allNames.size)
        sb.appendLine("Найдено по stringType: " + proxDump.size)
        for (p in proxDump) sb.appendLine("   " + p)
        sb.appendLine()
        sb.appendLine("=== ЖИВЫЕ КАНАЛЫ ===")
        synchronized(map) {
            for (c in map.values) {
                sb.append(c.title + " [" + c.hardware + "] " + c.unit + " · type=" + c.sensorType + " · rate=" + c.rateUsed)
                when {
                    c.failed -> sb.appendLine(" — ОТКАЗ на всех частотах")
                    c.events == 0 -> sb.appendLine(" — НЕТ СОБЫТИЙ")
                    else -> {
                        sb.appendLine()
                        sb.appendLine("   сейчас: " + c.values.joinToString(" / ") { String.format(java.util.Locale.US, "%.4f", it) })
                        sb.appendLine("   min " + String.format(java.util.Locale.US, "%.4f", c.minV) +
                                " · max " + String.format(java.util.Locale.US, "%.4f", c.maxV) +
                                " · событий " + c.events)
                        val d = c.stepsDelta()
                        if (!d.isNaN()) sb.appendLine("   шагов за сессию: " + String.format(java.util.Locale.US, "%.0f", d))
                    }
                }
            }
        }
        return sb.toString()
    }
}

private class Fft(private val n: Int) {
    private val cosT = DoubleArray(n / 2)
    private val sinT = DoubleArray(n / 2)
    private val rev = IntArray(n)

    init {
        for (i in 0 until n / 2) {
            cosT[i] = cos(-2.0 * Math.PI * i / n)
            sinT[i] = sin(-2.0 * Math.PI * i / n)
        }
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun magnitude(re: DoubleArray, im: DoubleArray, out: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val tr = re[l] * cosT[k] - im[l] * sinT[k]
                    val ti = re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    j++
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
        for (i in 0 until n / 2) out[i] = sqrt(re[i] * re[i] + im[i] * im[i])
    }
}

class AudioFrame(
    val sampleRate: Int,
    val source: String,
    val nyquist: Float,
    val binHz: Float,
    val display: FloatArray,
    val bands: FloatArray,
    val bandLabels: Array<String>,
    val peakHz: Float,
    val peakDb: Float,
    val crestDb: Float,
    val stableMs: Long,
    val isTone: Boolean,
    val pathOpen: Boolean,
    val verdict: String
)

class UltrasoundProbe {

    companion object {
        const val FFT = 4096
        const val BINS = 256
        private const val TAG = "UltrasoundProbe"
        private const val FLOOR_CLAMP = -120.0
        private const val CREST_MIN = 12.0
        private const val HOP_TOL_HZ = 350.0
    }

    @Volatile var latest: AudioFrame? = null
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    var errorText: String = ""
        private set
    var maxPeakHz: Float = 0f
        private set
    var maxCrest: Float = 0f
        private set
    var ceilingHz: Float = 24000f
        private set

    fun start(): Boolean {
        if (running) return true
        errorText = ""
        maxPeakHz = 0f
        maxCrest = 0f

        val rates = intArrayOf(96000, 48000, 44100)
        val sources = ArrayList<Pair<Int, String>>()
        sources.add(Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"))
        if (Build.VERSION.SDK_INT >= 24) sources.add(Pair(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"))
        sources.add(Pair(MediaRecorder.AudioSource.MIC, "MIC"))

        var rec: AudioRecord? = null
        var rate = 0
        var srcName = ""

        for (r in rates) {
            val min = try {
                AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (e: Exception) { -1 }
            if (min <= 0) continue
            val need = maxOf(min, FFT * 4)
            for (s in sources) {
                try {
                    val c = AudioRecord(s.first, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, need)
                    if (c.state == AudioRecord.STATE_INITIALIZED) { rec = c; rate = r; srcName = s.second; break }
                    else c.release()
                } catch (e: Exception) { Log.w(TAG, "${s.second}@$r: ${e.message}") }
            }
            if (rec != null) break
        }

        if (rec == null) { errorText = "AudioRecord не инициализировался"; return false }

        running = true
        val record = rec
        val sr = rate
        val src = srcName

        thread = Thread({
            val fft = Fft(FFT)
            val half = FFT / 2
            val win = DoubleArray(FFT)
            for (i in 0 until FFT) win[i] = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (FFT - 1))
            val pcm = ShortArray(FFT)
            val re = DoubleArray(FFT)
            val im = DoubleArray(FFT)
            val mag = DoubleArray(half)
            val db = DoubleArray(half)
            val disp = FloatArray(BINS)
            val binHz = sr.toDouble() / FFT
            val frameMs = FFT * 1000L / sr
            val labels = arrayOf("0–8k", "8–16k", "16–24k", "24–32k", "32–48k")
            var lastPeak = 0f
            var stable = 0L
            var seen = 0
            var toneState = false
            var drop = 0

            try {
                record.startRecording()
                while (running) {
                    val got = record.read(pcm, 0, FFT)
                    if (got != FFT) continue
                    for (i in 0 until FFT) {
                        re[i] = (pcm[i].toDouble() / 32768.0) * win[i]
                        im[i] = 0.0
                    }
                    fft.magnitude(re, im, mag)
                    for (i in 0 until half) {
                        val a = mag[i] * 4.0 / FFT
                        db[i] = 20.0 * log10(maxOf(a, 1e-9))
                    }
                    val group = half / BINS
                    for (b in 0 until BINS) {
                        var m = -160.0
                        val s0 = b * group
                        for (k in s0 until s0 + group) if (db[k] > m) m = db[k]
                        disp[b] = m.toFloat()
                    }
                    val bands = FloatArray(5)
                    for (b in 0 until 5) {
                        val f0 = (b * 8000.0 / binHz).toInt()
                        val f1 = ((b + 1) * 8000.0 / binHz).toInt()
                        bands[b] = if (f0 >= half) -160f else median(db, f0, minOf(f1, half)).toFloat()
                    }

                    val deadAbove24 = sr >= 96000 && bands[3] <= -125f
                    val ceilHz = if (deadAbove24) 24000.0 else sr / 2.0
                    ceilingHz = ceilHz.toFloat()

                    val hfFrom = minOf((15000.0 / binHz).toInt(), half - 2)
                    val hfTo = maxOf(hfFrom + 2, minOf((ceilHz / binHz).toInt(), half))
                    var pi = hfFrom
                    var pv = -160.0
                    for (i in hfFrom until hfTo) if (db[i] > pv) { pv = db[i]; pi = i }

                    val floorRaw = median(db, hfFrom, hfTo)
                    val floor = maxOf(floorRaw, FLOOR_CLAMP)
                    val crest = pv - floor
                    val hz = (pi * binHz).toFloat()

                    // Замок тона: гистерезис + допуск пропаданий + ведение частоты
                    val enter = crest >= CREST_MIN && pv >= -115.0
                    val hold = crest >= CREST_MIN - 5.0 && pv >= -118.0
                    val cand = if (toneState) hold else enter
                    if (cand && abs(hz - lastPeak) < HOP_TOL_HZ) {
                        stable += frameMs
                        drop = 0
                        lastPeak = lastPeak * 0.7f + hz * 0.3f
                    } else if (cand) {
                        lastPeak = hz
                        drop = 0
                    } else {
                        drop++
                        if (drop > 3) { stable = 0; drop = 0 }
                    }
                    seen++
                    toneState = when {
                        !toneState && seen > 10 && stable >= 600L -> true
                        toneState && stable == 0L -> false
                        else -> toneState
                    }
                    val tone = toneState
                    if (crest > maxCrest && pv >= -115.0) { maxCrest = crest.toFloat(); maxPeakHz = hz }

                    val shelf = bands[2] - bands[3]
                    val open = sr >= 96000 && !deadAbove24 && shelf < 12f
                    val verdict = when {
                        sr < 96000 -> "fs " + sr / 1000 + " кГц · полоса до " + (sr / 2000) + " кГц"
                        open -> "ТРАКТ ОТКРЫТ выше 24 кГц"
                        else -> "ПОЛОСА 0–24 кГц · 96k = апсемпл 48k · разрешение сонара 2.9 см"
                    }

                    latest = AudioFrame(sr, src, sr / 2f, binHz.toFloat(), disp.copyOf(), bands, labels,
                        hz, pv.toFloat(), crest.toFloat(), stable, tone, open, verdict)
                }
            } catch (e: Exception) {
                errorText = "Ошибка захвата: " + e.message
                Log.e(TAG, "loop", e)
            } finally {
                try { record.stop() } catch (e: Exception) {}
                record.release()
            }
        }, "UltrasoundProbe").apply { start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (e: InterruptedException) {}
        thread = null
        latest = null
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("=== АКУСТИКА ===")
        sb.appendLine("Рабочий потолок анализа: " + String.format(java.util.Locale.US, "%.1f", ceilingHz / 1000) + " кГц")
        sb.appendLine("Лучший тон за сессию: " + String.format(java.util.Locale.US, "%.2f", maxPeakHz / 1000) +
                " кГц · crest " + String.format(java.util.Locale.US, "%.1f", maxCrest) + " dB")
        val f = latest ?: return sb.toString() + "нет текущего кадра\n"
        sb.appendLine("fs " + f.sampleRate + " Гц · источник " + f.source)
        sb.appendLine("ВЕРДИКТ: " + f.verdict)
        sb.appendLine("Обрыв 16-24k → 24-32k: " + String.format(java.util.Locale.US, "%.1f", f.bands[2] - f.bands[3]) + " dB")
        sb.appendLine("Медианы полос, dBFS:")
        for (i in 0 until 5) sb.appendLine("   " + f.bandLabels[i] + " : " + String.format(java.util.Locale.US, "%.1f", f.bands[i]))
        sb.appendLine("Пик: " + String.format(java.util.Locale.US, "%.2f", f.peakHz / 1000) + " кГц, " +
                String.format(java.util.Locale.US, "%.1f", f.peakDb) + " dBFS, crest " +
                String.format(java.util.Locale.US, "%.1f", f.crestDb) + " dB, ТОН=" + f.isTone +
                ", устойч=" + f.stableMs + " мс")
        return sb.toString()
    }

    private fun median(a: DoubleArray, from: Int, to: Int): Double {
        val n = to - from
        if (n <= 0) return -160.0
        val c = DoubleArray(n)
        System.arraycopy(a, from, c, 0, n)
        c.sort()
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }
}
