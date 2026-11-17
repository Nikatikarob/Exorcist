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
    val accent: Int
) {
    var values: FloatArray = FloatArray(0)
    var minV: Float = Float.MAX_VALUE
    var maxV: Float = -Float.MAX_VALUE
    var events: Int = 0
    var lastMs: Long = 0
    var failed: Boolean = false
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
}

class SensorProbe(context: Context) {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val map = LinkedHashMap<String, ChannelState>()
    private val bound = ArrayList<Pair<Sensor, ChannelState>>()
    private var lastEmit = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val st = map[e.sensor.name] ?: return
            synchronized(map) {
                val n = minOf(3, e.values.size)
                val arr = FloatArray(n)
                for (i in 0 until n) arr[i] = e.values[i]
                st.values = arr
                st.events++
                st.lastMs = System.currentTimeMillis()
                st.push(arr[0])
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun classify(s: Sensor): ChannelState? {
        val st = s.stringType.lowercase()
        val nm = s.name.lowercase()
        val hz = if (s.minDelay > 0) (1_000_000f / s.minDelay).toInt().toString() + " Гц" else "по событию"
        val base = "диапазон " + trim(s.maximumRange) + " · шаг " + trim(s.resolution) + " · " + hz

        return when {
            s.type == Sensor.TYPE_PROXIMITY && nm.contains("elliptic") -> ChannelState(
                s.name, "Elliptic Proximity", s.name, "ед.", base,
                "КЛЮЧЕВОЙ. Води рукой 5→150 см. Если число совпадает с сантиметрами — готовый дальномер, DSP не нужен.", 0xFF7CFF3D.toInt())
            s.type == Sensor.TYPE_PROXIMITY -> ChannelState(
                s.name, "Оптический proximity", s.name, "ед.", base,
                "Бинарный. Для сравнения с ультразвуковым.", 0xFF7E93A3.toInt())
            st.contains("flicker") -> ChannelState(
                s.name, "Мерцание света", s.name, "Гц", base,
                "Наведи на LED-лампу и на лампу накаливания. Частота ШИМ читается как число.", 0xFFFFB020.toInt())
            st.contains("cct") -> ChannelState(
                s.name, "Цветовая температура", s.name, "K", base,
                "Различает LED / накаливание / люминесцент.", 0xFF2E7BFF.toInt())
            st.contains("rear_light") -> ChannelState(
                s.name, "Задняя освещённость", s.name, "лк", base,
                "Свет со стороны сцены, не экрана. База для режима день/ночь.", 0xFF00E5C0.toInt())
            st.contains("ambient_light_scene") -> ChannelState(
                s.name, "Сцена освещения", s.name, "ед.", base,
                "Класс сцены от вендора.", 0xFF0FF0FC.toInt())
            st.contains("sar") -> ChannelState(
                s.name, "Ёмкостный SAR", s.name, "ед.", base,
                "Единственный ёмкостный канал наружу. Поднеси руку и металл — сравни.", 0xFFFF2E88.toInt())
            st.contains("af_ccl") -> ChannelState(
                s.name, "AF CCL (wide)", s.name, "ед.", base,
                "Возможно позиция линзы → грубый признак глубины.", 0xFFA855F7.toInt())
            st.contains("gyro_hs") -> ChannelState(
                s.name, "Гироскоп HS 800 Гц", s.name, "рад/с", base,
                "Приложи к работающей технике — полоса вибродиагностики шире.", 0xFFA855F7.toInt())
            s.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> ChannelState(
                s.name, "Магнит без калибровки", s.name, "мкТл", base,
                "Правильный источник для карты: калиброванный съедает статику.", 0xFFFF2E88.toInt())
            s.type == Sensor.TYPE_MAGNETIC_FIELD -> ChannelState(
                s.name, "Магнит калиброванный", s.name, "мкТл", base,
                "Для сравнения с некалиброванным.", 0xFFFF2E88.toInt())
            st.contains("step") && !st.contains("wakeup") -> ChannelState(
                s.name, "Детектор шага", s.name, "шаг", base,
                "Аппаратный. Основа PDR вместо двойного интегрирования.", 0xFF7CFF3D.toInt())
            s.type == Sensor.TYPE_GAME_ROTATION_VECTOR -> ChannelState(
                s.name, "Курс без магнитометра", s.name, "ед.", base,
                "Не портится магнитными аномалиями. Курс для сонара.", 0xFF2E7BFF.toInt())
            s.type == Sensor.TYPE_LIGHT -> ChannelState(
                s.name, "Освещённость (фронт)", s.name, "лк", base,
                "Датчик со стороны экрана.", 0xFFFFB020.toInt())
            s.type == Sensor.TYPE_ACCELEROMETER -> ChannelState(
                s.name, "Акселерометр", s.name, "м/с²", base,
                "200 Гц. Приложенный к корпусу техники — виброспектр до 100 Гц.", 0xFF00E5C0.toInt())
            else -> null
        }
    }

    private fun trim(v: Float): String {
        return if (abs(v) >= 100f) v.toInt().toString() else String.format(java.util.Locale.US, "%.4f", v)
    }

    fun start() {
        stop()
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        for (s in all) {
            val cs = classify(s) ?: continue
            if (map.containsKey(cs.key)) continue
            map[cs.key] = cs
            val ok = try {
                sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_FASTEST)
            } catch (e: Exception) {
                Log.w("SensorProbe", "register ${s.name}: ${e.message}")
                false
            }
            cs.failed = !ok
            if (ok) bound.add(Pair(s, cs))
        }
        Log.d("SensorProbe", "bound ${bound.size} of ${map.size}")
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
        sb.appendLine("=== ЖИВЫЕ КАНАЛЫ ===")
        synchronized(map) {
            for (c in map.values) {
                if (c.failed) {
                    sb.appendLine(c.title + " [" + c.hardware + "] — ОТКАЗ РЕГИСТРАЦИИ")
                    continue
                }
                if (c.events == 0) {
                    sb.appendLine(c.title + " [" + c.hardware + "] — НЕТ СОБЫТИЙ")
                    continue
                }
                sb.appendLine(c.title + " [" + c.hardware + "] " + c.unit)
                sb.appendLine("   сейчас: " + c.values.joinToString(" / ") { String.format(java.util.Locale.US, "%.4f", it) })
                sb.appendLine("   min " + String.format(java.util.Locale.US, "%.4f", c.minV) +
                        " · max " + String.format(java.util.Locale.US, "%.4f", c.maxV) +
                        " · событий " + c.events)
            }
        }
        return sb.toString()
    }
}

/* ---------------- FFT ---------------- */

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
    }

    @Volatile var latest: AudioFrame? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    var errorText: String = ""
        private set

    fun start(): Boolean {
        if (running) return true
        errorText = ""

        val rates = intArrayOf(96000, 48000, 44100)
        val sources = ArrayList<Pair<Int, String>>()
        sources.add(Pair(MediaRecorder.AudioSource.VOICE_RECOGNITION, "VOICE_RECOGNITION"))
        if (Build.VERSION.SDK_INT >= 24) {
            sources.add(Pair(MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED"))
        }
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
                    if (c.state == AudioRecord.STATE_INITIALIZED) {
                        rec = c; rate = r; srcName = s.second; break
                    } else c.release()
                } catch (e: Exception) {
                    Log.w(TAG, "${s.second}@$r: ${e.message}")
                }
            }
            if (rec != null) break
        }

        if (rec == null) {
            errorText = "AudioRecord не инициализировался ни на одной частоте"
            return false
        }

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
                        if (f0 >= half) { bands[b] = -160f } else {
                            bands[b] = median(db, f0, minOf(f1, half)).toFloat()
                        }
                    }
                    val hfFrom = minOf((15000.0 / binHz).toInt(), half - 2)
                    var pi = hfFrom
                    var pv = -160.0
                    for (i in hfFrom until half) if (db[i] > pv) { pv = db[i]; pi = i }
                    val floor = median(db, hfFrom, half)
                    val crest = pv - floor
                    val hz = (pi * binHz).toFloat()
                    val cand = crest >= 14.0 && pv >= -95.0
                    stable = if (cand && abs(hz - lastPeak) < 180f) stable + frameMs else 0L
                    lastPeak = hz
                    seen++
                    val tone = seen > 10 && cand && stable >= 600L

                    val open = sr >= 96000 && bands[3] > -150f
                    val verdict = when {
                        sr < 96000 -> "fs " + sr / 1000 + " кГц — полоса выше 24 кГц вне обзора"
                        open -> "ТРАКТ ОТКРЫТ выше 24 кГц · доступен chirp до 40 кГц"
                        else -> "ПОЛОСА выше 24 кГц МЕРТВА · аналоговый фильтр закрыт"
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
        val f = latest ?: return "=== АКУСТИКА ===\nнет данных\n"
        val sb = StringBuilder()
        sb.appendLine("=== АКУСТИКА ===")
        sb.appendLine("fs " + f.sampleRate + " Гц · источник " + f.source + " · Nyq " + (f.nyquist / 1000).toInt() + " кГц")
        sb.appendLine("ВЕРДИКТ: " + f.verdict)
        sb.appendLine("Медианы полос, dBFS:")
        for (i in 0 until 5) {
            sb.appendLine("   " + f.bandLabels[i] + " : " + String.format(java.util.Locale.US, "%.1f", f.bands[i]))
        }
        sb.appendLine("Пик 15k+ : " + String.format(java.util.Locale.US, "%.2f", f.peakHz / 1000) + " кГц, " +
                String.format(java.util.Locale.US, "%.1f", f.peakDb) + " dBFS, crest " +
                String.format(java.util.Locale.US, "%.1f", f.crestDb) + " dB, тон=" + f.isTone)
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
