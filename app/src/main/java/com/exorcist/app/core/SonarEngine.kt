package com.exorcist.app.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
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
    val nearEnergy: Float,
    val status: String
)

class SonarEngine {

    companion object {
        private const val TAG = "SonarEngine"
        private const val BINS = 240
        private const val SPEED = 343.0
        private const val BLOCK = 512
    }

    @Volatile var f0: Float = 15000f
    @Volatile var f1: Float = 20000f
    @Volatile var durMs: Float = 12f
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
    var accepted: Int = 0
        private set
    var rejected: Int = 0
        private set
    var dLagMedian: Int = 0
        private set
    var dLagSpreadAcc: Int = 0
        private set
    var maxLoopGapMs: Float = 0f
        private set

    private var ioThread: Thread? = null
    private var workThread: Thread? = null
    private var profAvg: FloatArray? = null
    private var capAcc: FloatArray? = null

    private class Job(
        val cap: FloatArray,
        val tpl: FloatArray,
        val tplE: Double,
        val tplS2: Double,
        val directWin: Int,
        val echoWin: Int,
        val guard: Int,
        val fs: Int
    )

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
        pings = 0; heardCount = 0; accepted = 0; rejected = 0
        bestRange = 0f; bestConf = 0f
        dLagMedian = 0; dLagSpreadAcc = 0; maxLoopGapMs = 0f
        profAvg = null; capAcc = null

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
                    .setBufferSizeInBytes(max(minOut, 16384))
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
        val queue = ArrayBlockingQueue<Job>(2)

        workThread = Thread({
            val dLags = ArrayList<Int>(8)
            while (running || queue.isNotEmpty()) {
                val job = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (job != null) process(job, dLags)
            }
        }, "SonarWorker").apply { start() }

        ioThread = Thread({
            var tpl = buildTemplate(rate)
            var tplF = toFloatTpl(tpl)
            var tplE = energy(tplF)
            var tplS2 = tplE * tplE
            var sigF0 = f0; var sigF1 = f1; var sigDur = durMs; var sigGain = gain

            val outBlock = ShortArray(BLOCK)
            val inBlock = ShortArray(BLOCK)
            var pingQueue: ShortArray? = null
            var pingOff = 0
            var blockCount = 0L
            val blocksPerPing = max(4L, (intervalMs.toDouble() / (BLOCK * 1000.0 / rate)).toLong())

            var cap = FloatArray(0)
            var capIdx = 0
            var capNeed = 0
            var directWin = 0
            var echoWin = 0
            var guard = 0
            var capturing = false
            var lastLoopNs = System.nanoTime()

            try {
                r.startRecording()

                // Предзаполнение: запас тишины, поглощающий любые приостановы
                val silence = ShortArray(BLOCK)
                for (i in 0 until 6) t.write(silence, 0, BLOCK)
                t.play()

                while (running) {
                    val gapMs = (System.nanoTime() - lastLoopNs) / 1e6f
                    lastLoopNs = System.nanoTime()
                    if (gapMs > maxLoopGapMs) maxLoopGapMs = gapMs

                    if (f0 != sigF0 || f1 != sigF1 || durMs != sigDur || gain != sigGain) {
                        sigF0 = f0; sigF1 = f1; sigDur = durMs; sigGain = gain
                        tpl = buildTemplate(rate)
                        tplF = toFloatTpl(tpl)
                        tplE = energy(tplF)
                        tplS2 = tplE * tplE
                        capAcc = null
                        profAvg = null
                    }

                    if (pingQueue == null && emit && blockCount % blocksPerPing == 0L) {
                        pingQueue = tpl
                        pingOff = 0
                        val tn = tplF.size
                        directWin = (rate * 0.085).toInt()
                        echoWin = max(64, (rate * 2.0 * (maxRangeCm / 100.0) / SPEED).toInt())
                        guard = max(8, (rate * 0.30 / SPEED).toInt())
                        capNeed = directWin + guard + echoWin + 2 * tn + 64
                        cap = FloatArray(capNeed)
                        capIdx = 0
                        capturing = true
                        pings++
                    }

                    for (i in 0 until BLOCK) {
                        val q = pingQueue
                        if (q != null) {
                            outBlock[i] = q[pingOff++]
                            if (pingOff >= q.size) { pingQueue = null; pingOff = 0 }
                        } else {
                            outBlock[i] = 0
                        }
                    }
                    try {
                        t.write(outBlock, 0, BLOCK)
                    } catch (e: Exception) {
                        Log.e(TAG, "write", e)
                    }

                    val got = r.read(inBlock, 0, BLOCK)
                    blockCount++
                    if (got <= 0) continue

                    if (capturing) {
                        val take = min(got, capNeed - capIdx)
                        for (i in 0 until take) cap[capIdx + i] = inBlock[i].toFloat() / 32768f
                        capIdx += take
                        if (capIdx >= capNeed) {
                            capturing = false
                            val job = Job(cap, tplF, tplE, tplS2, directWin, echoWin, guard, rate)
                            if (!queue.offer(job)) rejected++
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
        }, "SonarIO").apply { start() }

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

    private fun process(job: Job, dLags: ArrayList<Int>) {
        try {
            val cap = job.cap
            val tpl = job.tpl
            val tplE = job.tplE
            val tplS2 = job.tplS2
            val directWin = job.directWin
            val echoWin = job.echoWin
            val guard = job.guard
            val fs = job.fs
            val tn = tpl.size

            var dLag = 0
            var dAbs = 0f
            var dSigned = 0.0
            for (lag in 0 until directWin) {
                var s = 0.0
                val lim = min(tn, cap.size - lag)
                for (i in 0 until lim) s += cap[lag + i] * tpl[i]
                val v = (abs(s) / tplE).toFloat()
                if (v > dAbs) { dAbs = v; dLag = lag; dSigned = s }
            }

            var gateOk = true
            if (dLags.size >= 3) {
                val sorted = dLags.sorted()
                val med = sorted[sorted.size / 2]
                gateOk = abs(dLag - med) <= 160
                dLagMedian = med
                var sp = 0
                for (v in sorted) sp = max(sp, abs(v - med))
                dLagSpreadAcc = sp
            }
            if (gateOk) {
                dLags.add(dLag)
                if (dLags.size > 8) dLags.removeAt(0)
                accepted++
            } else {
                rejected++
            }

            val amp = dSigned / tplS2
            val subEnd = min(cap.size, dLag + tn)
            for (i in dLag until subEnd) cap[i] -= (amp * tpl[i - dLag]).toFloat()

            val echoStart = dLag + guard
            val accLen = echoWin + tn
            if (echoStart + accLen > cap.size) return
            var acc = capAcc
            if (acc == null || acc.size != accLen) {
                acc = FloatArray(accLen)
                capAcc = acc
            }
            if (gateOk) {
                for (k in 0 until accLen) acc[k] = acc[k] * 0.5f + cap[echoStart + k] * 0.5f
            }

            val corr = FloatArray(echoWin)
            for (k in 0 until echoWin) {
                var s = 0.0
                for (i in 0 until tn) s += acc[k + i] * tpl[i]
                corr[k] = (abs(s) / tplE).toFloat()
            }

            val floor = median(corr, 0, echoWin)
            val dRatio = dAbs / (floor + 1e-9f)
            val dDb = (20.0 * Math.log10(dAbs.toDouble() + 1e-9)).toFloat()
            val heard = emit && dRatio > 4f && gateOk

            val raw = FloatArray(BINS)
            for (k in 0 until echoWin) {
                val b = (k.toLong() * (BINS - 1) / echoWin).toInt().coerceIn(0, BINS - 1)
                if (corr[k] > raw[b]) raw[b] = corr[k]
            }
            val rfloor = median(raw, 0, BINS)

            val res = FloatArray(BINS)
            for (b in 0 until BINS) {
                val rangeM = b.toDouble() / (BINS - 1) * (echoWin.toDouble() / fs) * SPEED / 2.0
                val comp = (1.0 + 0.8 * rangeM).toFloat()
                res[b] = max(0f, raw[b] - rfloor) * comp
            }

            if (heard) {
                var pa = profAvg
                if (pa == null || pa.size != BINS) { pa = FloatArray(BINS); profAvg = pa }
                for (b in 0 until BINS) pa[b] = pa[b] * 0.65f + res[b] * 0.35f
            }
            val src = if (heard) (profAvg ?: res) else res
            var mx = 1e-9f
            for (v in src) if (v > mx) mx = v
            val disp = FloatArray(BINS)
            for (b in 0 until BINS) disp[b] = src[b] / mx

            var pk = 0
            var pv = 0f
            for (b in 0 until BINS) if (disp[b] > pv) { pv = disp[b]; pk = b }

            val rangeCm = (pk.toDouble() / (BINS - 1) * (echoWin.toDouble() / fs) * SPEED / 2.0 * 100.0).toFloat()
            val snr = raw[pk] / (rfloor + 1e-9f)
            val conf = ((snr - 3f) / 10f).coerceIn(0f, 1f)

            val cmPerBin = maxRangeCm / (BINS - 1f)
            val b0 = (20f / cmPerBin).toInt().coerceIn(0, BINS - 1)
            val b1 = (120f / cmPerBin).toInt().coerceIn(b0 + 1, BINS)
            var ne = 0f
            for (b in b0 until b1) ne += disp[b]
            val near = ne / (b1 - b0)

            if (heard) {
                heardCount++
                if (conf > bestConf && rangeCm <= maxRangeCm) { bestConf = conf; bestRange = rangeCm }
            }

            val bandHz = min(f1, fs / 2f * 0.97f) - min(f0, f1 - 1500f)
            val resCm = (SPEED / (2.0 * max(bandHz, 100f)) * 100.0).toFloat()

            val status = when {
                !emit -> "ИЗЛУЧЕНИЕ ВЫКЛЮЧЕНО · только приём"
                dRatio < 4f -> "ПОСЫЛКА СЛАБАЯ ×" + String.format(java.util.Locale.US, "%.1f", dRatio)
                !gateOk -> "ЯКОРЬ СБИЛСЯ · посылка отброшена"
                conf > 0.5f -> "ЦЕЛЬ · " + rangeCm.toInt() + " см"
                else -> "БЛИЖНЯЯ ЗОНА 20–120 см: " + String.format(java.util.Locale.US, "%.2f", near) +
                        " · води телефоном, следи за ростом"
            }

            latest = SonarFrame(fs, disp, rangeCm, conf, snr, dAbs, dRatio, dDb, resCm, pings, heard, near, status)
        } catch (e: Exception) {
            Log.e(TAG, "process", e)
        }
    }

    fun stop() {
        running = false
        try { ioThread?.join(700) } catch (e: InterruptedException) {}
        try { workThread?.join(700) } catch (e: InterruptedException) {}
        ioThread = null
        workThread = null
        latest = null
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("=== СОНАР ===")
        sb.appendLine("fs " + actualRate + " Гц · chirp " + String.format(java.util.Locale.US, "%.1f", f0 / 1000) +
                "–" + String.format(java.util.Locale.US, "%.1f", f1 / 1000) + " кГц · " + durMs + " мс · gain " + gain)
        sb.appendLine("Посылок " + pings + " · принято " + accepted + " · отброшено " + rejected)
        sb.appendLine("Медиана dLag " + dLagMedian + " · разброс принятых " + dLagSpreadAcc + " отсчётов")
        sb.appendLine("Макс. зазор цикла: " + String.format(java.util.Locale.US, "%.1f", maxLoopGapMs) +
                " мс (норма около 11, всё что выше — простой ввода-вывода)")
        sb.appendLine("Лучшая цель: " + bestRange.toInt() + " см при уверенности " + (bestConf * 100).toInt() + "%")
        val f = latest ?: return sb.toString() + "нет кадра\n"
        sb.appendLine("Разрешение: " + String.format(java.util.Locale.US, "%.1f", f.resolutionCm) + " см")
        sb.appendLine("Прямой путь: " + String.format(java.util.Locale.US, "%.1f", f.directDb) + " dB · ×" +
                String.format(java.util.Locale.US, "%.1f", f.directRatio))
        sb.appendLine("Эхо: " + f.rangeCm.toInt() + " см · SNR " + String.format(java.util.Locale.US, "%.1f", f.snr) +
                " · уверенность " + (f.confidence * 100).toInt() + "%")
        sb.appendLine("Ближняя зона 20–120 см: " + String.format(java.util.Locale.US, "%.3f", f.nearEnergy))
        sb.appendLine("Статус: " + f.status)
        sb.appendLine("Профиль (см : амплитуда):")
        val step = BINS / 24
        for (b in 0 until BINS step step) {
            val cm = (b.toDouble() / (BINS - 1) * maxRangeCm).toInt()
            sb.appendLine("   " + cm + " : " + String.format(java.util.Locale.US, "%.2f", f.profile[b]))
        }
        return sb.toString()
    }

    private fun median(a: FloatArray, from: Int, to: Int): Float {
        val n = to - from
        if (n <= 0) return 0f
        val c = FloatArray(n)
        System.arraycopy(a, from, c, 0, n)
        c.sort()
        return if (n % 2 == 1) c[n / 2] else 0.5f * (c[n / 2 - 1] + c[n / 2])
    }
}
