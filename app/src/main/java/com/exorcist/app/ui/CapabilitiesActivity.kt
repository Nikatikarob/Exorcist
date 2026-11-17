package com.exorcist.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.exorcist.app.core.DeviceCapabilities
import com.exorcist.app.databinding.ActivityCapabilitiesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CapabilitiesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCapabilitiesBinding
    private var report: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCapabilitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            report = withContext(Dispatchers.IO) {
                try {
                    DeviceCapabilities.collect(this@CapabilitiesActivity)
                } catch (e: Exception) {
                    "Ошибка сбора: ${e.message}"
                }
            }
            binding.tvReport.text = report
        }

        binding.btnCopy.setOnClickListener {
            if (report.isEmpty()) {
                Toast.makeText(this, "Отчёт ещё не готов", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("device_report", report))
            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener { finish() }
    }
}
