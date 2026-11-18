package com.exorcist.app.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class SonarFrame(
    val sampleRate: Int,
    val profile: FloatArray,
    val rangeCm: Float,
    val confidence: Float,
    val snr: Float,
    val directVal: Float,
    val directRatio: Float,
    val directDb: Float,
    val resolutionCm: Float,
    val pingIndex: Int,
    val heard: Boolean,
    val status: String
)

class SonarEngine {

    companion object {
        private const val TAG = "SonarEngine"
        private const val BINS = 240
        private const val SPEED = 343.0
    }

    @Volatile var f0: Float = 16000f
    @Volatile var f1: Float = 20000f
    @Volatile var durMs: Float = 3f
    @Volatile var gain: Float = 0.70f
    @Volatile var maxRangeCm: Float = 300f
    @Volatile var intervalMs: Int = 600
    @Volatile var emit: Boolean = true

    @Volatile var latest: SonarFrame? = null
        private set
    @Volatile var running: Boolean = false
        private set

    var errorText: String = ""
        private set
    var actualRate: Int = 0
        private set
    var bestRange: Float = 0f
        private set
    var bestConf: Float = 0f
        private set
    var pings: Int = 0
        private set
    var heardCount: Int = 0
        private set

    private var thread: Thread? = null
    private var avg: FloatArray? = null

    private fun buildTemplate(fs: Int): ShortArray {
        val n = max(48, (fs * durMs / 1000f).toInt())
        val t = n.toDouble() / fs
        val lo = min(f0, f1 - 1500f).toDouble()
        val hi = min(f1.toDouble(), fs / 2.0 * 0.97)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val tt = i.toDouble() / fs
            val ph = 2.0 * Math.PI * (lo * tt + (hi - lo) * tt * tt / (2.0 * t))
            val w = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (n - 1))
            val v = sin(ph) * w * gain.toDouble()
            out[i] = (v * 32000.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    fun start(): Boolean {
        if (running) return true
        errorText = ""
        pings = 0
        heardCount = 0
        bestRange = 0f
        bestConf = 0f
        avg = null

        var rec: AudioRecord? = null
        var trk: AudioTrack? = null
        var fs = 0

        for (rate in intArrayOf(48000, 44100)) {
            val minIn = try {
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (e: Exception) { -1 }
            val minOut = try {
                AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (e: Exception) { -1 }
            if (minIn <= 0 || minOut <= 0) continue
            try {
                val r = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minIn, 8192)
                )
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(max(minOut, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                if (t.state != AudioTrack.STATE_INITIALIZED) { r.release(); t.release(); continue }
                rec = r; trk = t; fs = rate; break
            } catch (e: Exception) {
                Log.w(TAG, "init@$rate: ${e.message}")
            }
        }

        if (rec == null || trk == null) {
            errorText = "Не удалось открыть тракт записи/воспроизведения"
            return false
        }

        actualRate = fs
        running = true
        val r = rec
        val t = trk
        val rate = fs

        thread = Thread({
            var tpl = buildTemplate(rate)
            var tplF = toFloatTpl(tpl)
            var tplEnergy = energy(tplF)
            var sigF0 = f0; var sigF1 = f1; var sigDur = durMs; var sigGain = gain

            val block = 512
            val buf = ShortArray(block)
            var cap = FloatArray(0)
            var capIdx = 0
            var capNeed = 0
            var directWin = 0
            var echoWin = 0
            var guard = 0
            var armed = false
            var lastPing = 0L

            try {
                r.startRecording()
                t.play()

                while (running) {
                    val now = System.currentTimeMillis()

                    if (!armed && now - lastPing >= intervalMs) {
                        if (f0 != sigF0 || f1 != sigF1 || durMs != sigDur || gain != sigGain) {
                            sigF0 = f0; sigF1 = f1; sigDur = durMs; sigGain = gain
                            tpl = buildTemplate(rate)
                            tplF = toFloatTpl(tpl)
                            tplEnergy = energy(tplF)
                        }
                        directWin = (rate * 0.085).toInt()
                        echoWin = max(64, (rate * 2.0 * (maxRangeCm / 100.0) / SPEED).toInt())
                        guard = max(12, (rate * 0.5 / SPEED).toInt())
                        capNeed = directWin + guard + echoWin + tplF.size + 64
                        cap = FloatArray(capNeed)
                        capIdx = 0
                        armed = true
                        lastPing = now

                        if (emit) {
                            try {
                                val w = t.write(tpl, 0, tpl.size)
                                if (w < tpl.size) Log.w(TAG, "short write $w/${tpl.size}")
                                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                            } catch (e: Exception) {
                                Log.e(TAG, "write", e)
                            }
                        }
                        pings++
                    }

                    val got = r.read(buf, 0, block)
                    if (got <= 0) continue

                    if (armed) {
                        val take = min(got, capNeed - capIdx)
                        for (i in 0 until take) cap[capIdx + i] = buf[i].toFloat() / 32768f
                        capIdx += take
                        if (capIdx >= capNeed) {
                            armed = false
                            process(cap, tplF, tplEnergy, directWin, echoWin, guard, rate)
                        }
                    }
                }
            } catch (e: Exception) {
                errorText = "Ошибка цикла: " + e.message
                Log.e(TAG, "loop", e)
            } finally {
                try { r.stop() } catch (e: Exception) {}
                try { r.release() } catch (e: Exception) {}
                try { t.stop() } catch (e: Exception) {}
                try { t.release() } catch (e: Exception) {}
            }
        }, "SonarEngine").apply { start() }

        return true
    }

    private fun toFloatTpl(t: ShortArray): FloatArray {
        val o = FloatArray(t.size)
        for (i in t.indices) o[i] = t[i].toFloat() / 32768f
        return o
    }

    private fun energy(a: FloatArray): Double {
        var e = 0.0
        for (v in a) e += v * v
        return sqrt(max(e, 1e-12))
    }

    private fun process(
        cap: FloatArray,
        tpl: FloatArray,
        tplEnergy: Double,
        directWin: Int,
        echoWin: Int,
        guard: Int,
        fs: Int
    ) {
        try {
            val tn = tpl.size

            // Проход 1: прямой путь динамик -> микрофон
            var dLag = 0
            var dVal = 0f
            for (lag in 0 until directWin) {
                var s = 0.0
                val lim = min(tn, cap.size - lag)
                for (i in 0 until lim) s += cap[lag + i] * tpl[i]
                val v = (abs(s) / tplEnergy).toFloat()
                if (v > dVal) { dVal = v; dLag = lag }
            }

            // Проход 2: эхо строго после прямого пути
            val echoStart = dLag + guard
            val echoEnd = min(cap.size - tn, echoStart + echoWin)
            if (echoEnd <= echoStart + 8) return
            val span = echoEnd - echoStart

            val corr = FloatArray(span)
            for (k in 0 until span) {
                val lag = echoStart + k
                var s = 0.0
                for (i in 0 until tn) s += cap[lag + i] * tpl[i]
                corr[k] = (abs(s) / tplEnergy).toFloat()
            }

            val floor = median(corr, 0, span)
            val dRatio = dVal / (floor + 1e-9f)
            val dDb = (20.0 * log10(dVal.toDouble() + 1e-9)).toFloat()
            val heard = emit && dRatio > 4f

            val raw = FloatArray(BINS)
            for (k in 0 until span) {
                val b = (k.toLong() * (BINS - 1) / span).toInt().coerceIn(0, BINS - 1)
                if (corr[k] > raw[b]) raw[b] = corr[k]
            }
            val rfloor = median(raw, 0, BINS)

            val res = FloatArray(BINS)
            var pk = 0
            var pv = 0f
            for (b in 0 until BINS) {
                val rangeM = b.toDouble() / (BINS - 1) * (span.toDouble() / fs) * SPEED / 2.0
                val comp = (1.0 + 0.8 * rangeM).toFloat()
                val v = max(0f, raw[b] - rfloor) * comp
                res[b] = v
                if (v > pv) { pv = v; pk = b }
            }

            // Когерентное усреднение: стена даёт устойчивую полосу
            if (heard) {
                var a = avg
                if (a == null || a.size != BINS) { a = FloatArray(BINS); avg = a }
                for (b in 0 until BINS) a[b] = a[b] * 0.65f + res[b] * 0.35f
            }
            val disp = if (heard) (avg ?: res).copyOf() else res.copyOf()

            var mx = 1e-9f
            for (v in disp) if (v > mx) mx = v
            for (b in 0 until BINS) disp[b] = disp[b] / mx

            var pk2 = 0
            var pv2 = 0f
            for (b in 0 until BINS) if (disp[b] > pv2) { pv2 = disp[b]; pk2 = b }

            val rangeCm = (pk2.toDouble() / (BINS - 1) * (span.toDouble() / fs) * SPEED / 2.0 * 100.0).toFloat()
            val snr = raw[pk] / (rfloor + 1e-9f)
            val conf = ((snr - 3f) / 12f).coerceIn(0f, 1f)

            if (heard) {
                heardCount++
                if (conf > bestConf && rangeCm <= maxRangeCm) { bestConf = conf; bestRange = rangeCm }
            }

            val bandHz = min(f1, fs / 2f * 0.97f) - min(f0, f1 - 1500f)
            val resCm = (SPEED / (2.0 * max(bandHz, 100f)) * 100.0).toFloat()

            val status = when {
                !emit -> "ИЗЛУЧЕНИЕ ВЫКЛЮЧЕНО · только приём"
                dRatio < 4f -> "ПОСЫЛКА СЛАБАЯ ×" + String.format(java.util.Locale.US, "%.1f", dRatio) + " · подними уровень или опусти частоту"
                conf > 0.5f -> "ЦЕЛЬ УВЕРЕННАЯ · " + rangeCm.toInt() + " см"
                conf > 0.25f -> "ЦЕЛЬ СЛАБАЯ · " + rangeCm.toInt() + " см"
                else -> "ЭХО НЕ ВЫДЕЛЯЕТСЯ · подойди ближе к стене"
            }

            latest = SonarFrame(fs, disp, rangeCm, conf, snr, dVal, dRatio, dDb, resCm, pings, heard, status)
        } catch (e: Exception) {
            Log.e(TAG, "process", e)
        }
    }

    fun stop() {
        running = false
        try { thread?.join(700) } catch (e: InterruptedException) {}
        thread = null
        latest = null
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("=== СОНАР ===")
        sb.appendLine("fs " + actualRate + " Гц · chirp " + String.format(java.util.Locale.US, "%.1f", f0 / 1000) +
                "–" + String.format(java.util.Locale.US, "%.1f", f1 / 1000) + " кГц · " + durMs + " мс · gain " + gain)
        sb.appendLine("Посылок " + pings + " · услышано " + heardCount)
        sb.appendLine("Лучшая цель: " + bestRange.toInt() + " см при уверенности " + (bestConf * 100).toInt() + "%")
        val f = latest ?: return sb.toString() + "нет кадра\n"
        sb.appendLine("Разрешение: " + String.format(java.util.Locale.US, "%.1f", f.resolutionCm) + " см")
        sb.appendLine("Прямой путь: " + String.format(java.util.Locale.US, "%.1f", f.directDb) + " dB · ×" +
                String.format(java.util.Locale.US, "%.1f", f.directRatio) + " к шуму")
        sb.appendLine("Эхо: " + f.rangeCm.toInt() + " см · SNR " + String.format(java.util.Locale.US, "%.1f", f.snr) +
                " · уверенность " + (f.confidence * 100).toInt() + "%")
        sb.appendLine("Статус: " + f.status)
        sb.appendLine("Профиль (см : амплитуда):")
        val step = BINS / 24
        for (b in 0 until BINS step step) {
            val cm = (b.toDouble() / (BINS - 1) * maxRangeCm).toInt()
            sb.appendLine("   " + cm + " : " + String.format(java.util.Locale.US, "%.2f", f.profile[b]))
        }
        return sb.toString()
    }

    private fun log10(v: Double): Double = Math.log10(v)

    private fun median(a: FloatArray, from: Int, to: Int): Float {
        val n = to - from
        if (n <= 0) return 0f
        val c = FloatArray(n)
        System.arraycopy(a, from, c, 0, n)
        c.sort()
        return if (n % 2 == 1) c[n / 2] else 0.5f * (c[n / 2 - 1] + c[n / 2])
    }
}
