package com.exorcist.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.exorcist.app.core.PdrEngine
import com.exorcist.app.core.UltrasoundProbe
import com.exorcist.app.databinding.ActivityRoomMapBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class RoomMapActivity : AppCompatActivity() {

    private lateinit var b: ActivityRoomMapBinding
    private lateinit var pdr: PdrEngine
    private val probe = UltrasoundProbe()
    private var tick: Job? = null
    private var running = false
    private var prevTone = false
    private var checkStepsJob: Job? = null

    companion object {
        private const val REQ_AUDIO = 501
        private const val REQ_ACTIVITY = 502
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRoomMapBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        pdr = PdrEngine(this)
        b.mapView.bind(pdr)

        b.sbStride.max = 60
        b.sbStride.progress = 25
        b.sbThr.max = 60
        b.sbThr.progress = 20

        wire(b.sbStride) { pdr.strideM = 0.40f + it * 0.01f; syncLabels() }
        wire(b.sbThr) { pdr.magThreshold = 2f + it * 0.1f; syncLabels() }
        syncLabels()

        b.btnToggle.setOnClickListener {
            if (running) {
                stopAll()
            } else if (!hasActivityPerm()) {
                requestActivityPerm()
            } else if (!hasAudioPerm()) {
                requestAudioPerm()
            } else {
                startAll()
            }
        }
        b.btnCopy.setOnClickListener { copy() }
        b.btnBack.setOnClickListener { finish() }
    }

    private fun hasActivityPerm(): Boolean {
        return Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPerm(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestActivityPerm() {
        if (Build.VERSION.SDK_INT >= 29) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQ_ACTIVITY)
        }
    }

    private fun requestAudioPerm() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
    }

    private fun wire(sb: SeekBar, apply: (Int) -> Unit) {
        apply(sb.progress)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = apply(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun syncLabels() {
        b.lblStride.text = "Длина шага · " + String.format(Locale.US, "%.2f", pdr.strideM) + " м"
        b.lblThr.text = "Порог магнитной метки · " + String.format(Locale.US, "%.1f", pdr.magThreshold) + " мкТл"
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        val ok = g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED
        when (rc) {
            REQ_ACTIVITY -> {
                if (ok) Toast.makeText(this, "Разрешение получено. Нажми СТАРТ", Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "Без физической активности шаги считаться не будут", Toast.LENGTH_LONG).show()
            }
            REQ_AUDIO -> {
                if (ok) {
                    if (hasActivityPerm()) startAll() else requestActivityPerm()
                } else {
                    Toast.makeText(this, "Без микрофона не будет тоновых меток", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startAll() {
        pdr.start()
        probe.start()
        prevTone = false
        running = true
        b.btnToggle.text = "СТОП"
        b.btnToggle.setTextColor(Color.parseColor("#FF3B30"))
        b.btnToggle.strokeColor = ColorStateList.valueOf(Color.parseColor("#FF3B30"))
        b.tvStatus.text = "ИДИ МЕДЛЕННО ВДОЛЬ СТЕН · ВЕРНИСЬ В СТАРТ"
        b.tvStatus.setTextColor(Color.parseColor("#7CFF3D"))

        checkStepsJob?.cancel()
        checkStepsJob = lifecycleScope.launch {
            delay(5000)
            if (running && pdr.steps == 0) {
                if (!pdr.stepRegistered) {
                    b.tvStatus.text = "ОШИБКА: датчик шагов не зарегистрирован"
                    b.tvStatus.setTextColor(Color.parseColor("#FF3B30"))
                } else if (pdr.stepEvents == 0) {
                    b.tvStatus.text = "ВНИМАНИЕ: события шагов не приходят"
                    b.tvStatus.setTextColor(Color.parseColor("#FFB020"))
                }
            }
        }

        tick?.cancel()
        tick = lifecycleScope.launch {
            while (isActive) {
                render()
                delay(100)
            }
        }
    }

    private fun stopAll() {
        running = false
        tick?.cancel(); tick = null
        checkStepsJob?.cancel(); checkStepsJob = null
        pdr.stop()
        probe.stop()
        b.btnToggle.text = "СТАРТ"
        b.btnToggle.setTextColor(Color.parseColor("#7CFF3D"))
        b.btnToggle.strokeColor = ColorStateList.valueOf(Color.parseColor("#7CFF3D"))
        b.tvStatus.text = "ОСТАНОВЛЕНО · МЕТОК " + pdr.marks.size
        b.tvStatus.setTextColor(Color.parseColor("#7E93A3"))
        b.mapView.invalidate()
    }

    private fun render() {
        val f = probe.latest
        val tone = f?.isTone == true
        pdr.toneLocked = tone
        if (tone && !prevTone) pdr.noteTone((f?.peakHz ?: 0f) / 1000f)
        prevTone = tone

        // Живой статус: как только пошли шаги, предупреждение гаснет
        if (running && pdr.steps > 0) {
            val wp = pdr.wallPoly
            if (wp != null) {
                b.tvStatus.text = "КОНТУР ГОТОВ · УГЛОВ " + wp.pts.size +
                        (if (wp.closed) " · ЗАМКНУТ" else "")
                b.tvStatus.setTextColor(Color.parseColor("#7CFF3D"))
            } else {
                b.tvStatus.text = pdr.outlineReason.ifEmpty { "ИДИ МЕДЛЕННО ВДОЛЬ СТЕН · ВЕРНИСЬ В СТАРТ" }
                b.tvStatus.setTextColor(Color.parseColor("#FFB020"))
            }
        }

        b.tvSteps.text = "шаги " + pdr.steps
        b.tvHead.text = "курс " + pdr.headingDeg.toInt() + "°"
        b.tvMag.text = "маг " + String.format(Locale.US, "%+.1f", pdr.magDev)
        b.tvTone.text = if (tone) "тон " + String.format(Locale.US, "%.1f", (f?.peakHz ?: 0f) / 1000f) else "тон —"
        b.tvTone.setTextColor(Color.parseColor(if (tone) "#FFB020" else "#7CFF3D"))
        b.mapView.invalidate()
    }

    private fun copy() {
        val sb = StringBuilder()
        sb.appendLine("=== КАРТА КОМНАТЫ · vivo V2419A ===")
        sb.append(pdr.report())
        sb.appendLine()
        sb.append(probe.report())
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("roommap", sb.toString()))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tick?.cancel(); tick = null
        checkStepsJob?.cancel(); checkStepsJob = null
        pdr.stop()
        probe.stop()
    }
}
