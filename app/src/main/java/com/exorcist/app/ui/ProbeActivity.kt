package com.exorcist.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.exorcist.app.core.ChannelState
import com.exorcist.app.core.SensorProbe
import com.exorcist.app.core.UltrasoundProbe
import com.exorcist.app.databinding.ActivityProbeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class ProbeActivity : AppCompatActivity() {

    private lateinit var b: ActivityProbeBinding
    private lateinit var sensors: SensorProbe
    private val audio = UltrasoundProbe()
    private var tick: Job? = null
    private var running = false
    private val holders = LinkedHashMap<String, Holder>()

    private class Holder(
        val value: TextView,
        val range: TextView,
        val raw: TextView,
        val spark: SparklineView
    )

    companion object {
        private const val REQ_AUDIO = 301
        private const val REQ_ACTIVITY = 302
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProbeBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensors = SensorProbe(this)

        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                REQ_ACTIVITY
            )
        }

        b.btnToggle.setOnClickListener {
            if (running) {
                stopAll()
            } else if (granted(Manifest.permission.RECORD_AUDIO)) {
                startAll()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQ_AUDIO
                )
            }
        }

        b.btnCopy.setOnClickListener { copyReport() }
        b.btnBack.setOnClickListener { finish() }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        val ok = g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED
        when (rc) {
            REQ_AUDIO -> {
                if (ok) startAll()
                else Toast.makeText(this, "Без микрофона акустическая часть недоступна", Toast.LENGTH_LONG).show()
            }
            REQ_ACTIVITY -> {
                if (!ok) Toast.makeText(this, "Шаги останутся недоступны без разрешения", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAll() {
        sensors.start()
        val ok = audio.start()
        running = true

        b.btnToggle.text = "СТОП"
        b.btnToggle.setTextColor(Color.parseColor("#FF3B30"))
        b.btnToggle.strokeColor = ColorStateList.valueOf(Color.parseColor("#FF3B30"))

        if (!ok) Toast.makeText(this, audio.errorText, Toast.LENGTH_LONG).show()

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
        tick?.cancel()
        tick = null
        sensors.stop()
        audio.stop()
        b.spectrum.clear()

        b.btnToggle.text = "СТАРТ"
        b.btnToggle.setTextColor(Color.parseColor("#7CFF3D"))
        b.btnToggle.strokeColor = ColorStateList.valueOf(Color.parseColor("#7CFF3D"))
        b.tvVerdict.text = "ОСТАНОВЛЕНО"
        b.tvVerdict.setTextColor(Color.parseColor("#7E93A3"))
    }

    private fun render() {
        val f = audio.latest
        b.spectrum.submit(f)

        if (f != null) {
            b.tvVerdict.text = f.verdict
            val col = when {
                f.pathOpen -> "#7CFF3D"
                f.sampleRate >= 96000 -> "#FF3B30"
                else -> "#FFB020"
            }
            b.tvVerdict.setTextColor(Color.parseColor(col))

            val views = arrayOf(b.band0, b.band1, b.band2, b.band3, b.band4)
            for (i in 0 until 5) {
                val v = f.bands[i]
                views[i].text = f.bandLabels[i] + "\n" + String.format(Locale.US, "%.0f", v)
                views[i].setTextColor(Color.parseColor(if (v > -125f) "#00E5C0" else "#4E6373"))
            }
        }

        val list = sensors.snapshot()
        for (c in list) {
            val h = holders[c.key] ?: createCard(c).also { holders[c.key] = it }
            updateCard(c, h)
        }
    }

    private fun createCard(c: ChannelState): Holder {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setBackgroundColor(Color.parseColor("#0D151C"))
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        card.layoutParams = lp

        val title = TextView(this)
        title.text = c.title
        title.textSize = 15f
        title.typeface = Typeface.MONOSPACE
        title.setTextColor(c.accent)
        card.addView(title)

        val hw = TextView(this)
        hw.text = c.hardware + "  ·  " + c.spec
        hw.textSize = 11f
        hw.typeface = Typeface.MONOSPACE
        hw.setTextColor(Color.parseColor("#4E6373"))
        card.addView(hw)

        val value = TextView(this)
        value.text = "—"
        value.textSize = 24f
        value.typeface = Typeface.MONOSPACE
        value.setTextColor(Color.parseColor("#E8E4DA"))
        val vlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        vlp.topMargin = dp(4)
        value.layoutParams = vlp
        card.addView(value)

        val range = TextView(this)
        range.textSize = 12f
        range.typeface = Typeface.MONOSPACE
        range.setTextColor(Color.parseColor("#7E93A3"))
        card.addView(range)

        val raw = TextView(this)
        raw.textSize = 11f
        raw.typeface = Typeface.MONOSPACE
        raw.setTextColor(Color.parseColor("#4E6373"))
        card.addView(raw)

        val spark = SparklineView(this)
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
        slp.topMargin = dp(4)
        spark.layoutParams = slp
        card.addView(spark)

        val note = TextView(this)
        note.text = c.note
        note.textSize = 11f
        note.setTextColor(Color.parseColor("#7E93A3"))
        val nlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        nlp.topMargin = dp(4)
        note.layoutParams = nlp
        card.addView(note)

        b.channelList.addView(card)
        return Holder(value, range, raw, spark)
    }

    private fun updateCard(c: ChannelState, h: Holder) {
        if (c.failed) {
            h.value.text = "ОТКАЗ"
            h.value.setTextColor(Color.parseColor("#FF3B30"))
            h.range.text = "registerListener отказал на FASTEST, GAME и NORMAL"
            h.raw.text = ""
            return
        }
        if (c.events == 0) {
            h.value.text = "НЕТ СОБЫТИЙ"
            h.value.setTextColor(Color.parseColor("#4E6373"))
            h.range.text = "зарегистрирован на " + c.rateUsed + ", но молчит"
            h.raw.text = ""
            return
        }

        h.value.text = String.format(Locale.US, "%.4f", c.primary()) + " " + c.unit
        h.value.setTextColor(Color.parseColor("#E8E4DA"))
        h.range.text = "min " + String.format(Locale.US, "%.4f", c.minV) +
                "   max " + String.format(Locale.US, "%.4f", c.maxV) +
                "   n=" + c.events + "   rate=" + c.rateUsed
        h.raw.text = "оси: " + c.values.joinToString(" / ") { String.format(Locale.US, "%.3f", it) }
        h.spark.submit(c.ordered(), c.accent)
    }

    private fun copyReport() {
        val sb = StringBuilder()
        sb.appendLine("=== ПАНЕЛЬ КАНАЛОВ · vivo V2419A ===")
        sb.appendLine()
        sb.append(audio.report())
        sb.appendLine()
        sb.append(sensors.report())

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("probe", sb.toString()))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tick?.cancel()
        tick = null
        sensors.stop()
        audio.stop()
    }
}
