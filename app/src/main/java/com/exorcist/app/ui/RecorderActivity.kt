package com.exorcist.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.exorcist.app.R
import com.exorcist.app.core.SensorFusion
import com.exorcist.app.data.AppDatabase
import com.exorcist.app.databinding.ActivityRecorderBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private lateinit var sensorFusion: SensorFusion
    private lateinit var adapter: SessionAdapter
    
    private var isRecording = false

    companion object {
        private const val AUDIO_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация
        sensorFusion = SensorFusion(this)
                adapter = SessionAdapter { session ->
            // При клике открываем детализацию
            val intent = android.content.Intent(this, SessionDetailActivity::class.java)
            intent.putExtra("SESSION_ID", session.id)
            startActivity(intent)
        }
        binding.recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSessions.adapter = adapter

        // Наблюдаем за сессиями в реальном времени
        lifecycleScope.launch {
            AppDatabase.getInstance(this@RecorderActivity).sensorDao().getAllSessions().collectLatest { sessions ->
                adapter.submitList(sessions)
            }
        }

        // Наблюдаем за текущими показаниями сенсоров
        lifecycleScope.launch {
            sensorFusion.currentReading.collectLatest { reading ->
                if (reading != null) {
                    binding.tvMagnetic.text = "Магнитное поле: %.2f мкТл".format(reading.magneticTotal)
                    binding.tvAudio.text = "Ультразвук (амплитуда): %.0f".format(reading.audioAmplitude)
                    binding.tvPressure.text = "Давление: %.2f гПа".format(reading.pressure)
                }
            }
        }

        // Кнопка записи
        binding.btnRecord.setOnClickListener {
            if (checkAudioPermission()) {
                toggleRecording()
            } else {
                requestAudioPermission()
            }
        }

        // Кнопка назад
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleRecording()
            } else {
                Toast.makeText(this, "Нужен доступ к микрофону для анализа ультразвука", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            // Начинаем запись
            sensorFusion.startRecording("Комната")
            isRecording = true
            binding.btnRecord.text = "ОСТАНОВИТЬ ЗАПИСЬ"
            binding.btnRecord.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_cyan)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            binding.tvStatus.text = "ИДЕТ СБОР ДАННЫХ..."
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
        } else {
            // Останавливаем запись
            sensorFusion.stopRecording()
            isRecording = false
            binding.btnRecord.text = "НАЧАТЬ ЗАПИСЬ"
            binding.btnRecord.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_pink)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
            binding.tvStatus.text = "ЗАПИСЬ ОСТАНОВЛЕНА"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            sensorFusion.stopRecording()
        }
        sensorFusion.destroy()
    }
}
