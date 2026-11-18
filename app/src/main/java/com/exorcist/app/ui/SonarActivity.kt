package com.exorcist.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.exorcist.app.core.SonarEngine
import com.exorcist.app.databinding.ActivitySonarBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class SonarActivity : AppCompatActivity() {

    private lateinit var b: ActivitySonarBinding
    private val engine = SonarEngine()
    private var tick: Job? = null
    private var running = false

    companion object { private const val REQ = 401 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySonarBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        wire(b.sbF0) { engine.f0 = 14000f + it * 100f; syncLabels() }
        wire(b.sbF1) { engine.f1 = 20000f + it * 100f; syncLabels() }
        wire(b.sbDur) { engine.durMs = 1f + it * 0.5f; syncLabels() }
        wire(b.sbGain) { engine.gain = 0.05f + it * 0.01f; syncLabels() }
        wire(b.sbRange) { engine.maxRangeCm = 100f + it * 10f; syncLabels() }
        wire(b.sbInt) { engine.intervalMs = 300 + it * 50; syncLabels() }

        b.cbEmit.setOnCheckedChangeListener { _, v -> engine.emit = v }

        syncLabels()

        b.btnToggle.setOnClickListener {
            if (running) stopAll()
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startAll()
            else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ)
        }
        b.btnCopy.setOnClickListener { copy() }
        b.btnBack.setOnClickListener { finish() }
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
        b.lblF0.text = "Chirp f₀ · " + String.format(Locale.US, "%.1f", engine.f0 / 1000) + " кГц"
        b.lblF1.text = "Chirp f₁ · " + String.format(Locale.US, "%.1f", engine.f1 / 1000) + " кГц"
        b.lblDur.text = "Длительность · " + String.format(Locale.US, "%.1f", engine.durMs) + " мс"
        b.lblGain.text = "Уровень посылки · " + String.format(Locale.US, "%.2f", engine.gain)
        b.lblRange.text = "Макс. дальность · " + engine.maxRangeCm.toInt() + " см"
        b.lblInt.text = "Период посылки · " + engine.intervalMs + " мс"
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (rc == REQ) {
            if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startAll()
            else Toast.makeText(this, "Без микрофона сонар не работает", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAll() {
        val ok = engine.start()
        if (!ok) {
            Toast.makeText(this, engine.errorText, Toast.LENGTH_LONG).show()
            return
        }
        running = true
        b.btnToggle.text = "СТОП"
        b.btnToggle.setTextColor(Color.parseColor("#FF3B30"))
        b.btnToggle.strokeColor = ColorStateList.valueOf(Color.parseColor("#FF3B30"))
        tick?.cancel()
        tick = lifecycleScope.launch {
            while (isActive) {
                render()
                delay(90)
            }
        }
    }

    private fun stopAll() {
        running = false
        tick?.cancel(); tick = null
        engine.stop()
        b.scope.clear()
        b.btnToggle.text = "СТАРТ"
        b.btnToggle.setTextColor(Color.parseColor("#7CFF3D"))
        b.btnToggle.strokeColor = ColorStateList.valueOf(Color.parseColor("#7CFF3D"))
        b.tvStatus.text = "ОСТАНОВЛЕНО"
        b.tvStatus.setTextColor(Color.parseColor("#7E93A3"))
    }

    private fun render() {
        val f = engine.latest
        b.scope.submit(f, engine.maxRangeCm)
        if (f == null) return

        b.tvStatus.text = f.status
        b.tvStatus.setTextColor(
            Color.parseColor(
                when {
                    !f.heard -> "#FF3B30"
                    f.confidence > 0.5f -> "#7CFF3D"
                    else -> "#FFB020"
                }
            )
        )
        b.tvRange.text = if (f.heard) f.rangeCm.toInt().toString() + " см" else "— см"
        b.tvConf.text = (f.confidence * 100).toInt().toString() + " %"
        b.tvDiag.text = "прямой путь ×" + String.format(Locale.US, "%.1f", f.directRatio) +
                " · SNR " + String.format(Locale.US, "%.1f", f.snr) +
                " · разрешение " + String.format(Locale.US, "%.1f", f.resolutionCm) + " см" +
                " · посылок " + f.pingIndex
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("sonar", engine.report()))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tick?.cancel(); tick = null
        engine.stop()
    }
}
