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
import com.exorcist.app.data.SensorReading
import com.exorcist.app.databinding.ActivityRecorderBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private lateinit var sensorFusion: SensorFusion
    private lateinit var adapter: SessionAdapter
    
    private var isRecording = false
    private val liveReadings = mutableListOf<SensorReading>()
    private val maxLiveReadings = 200 // Ограничиваем количество точек на радаре

    companion object {
        private const val AUDIO_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorFusion = SensorFusion(this)
        adapter = SessionAdapter { session ->
            val intent = android.content.Intent(this, SessionDetailActivity::class.java)
            intent.putExtra("SESSION_ID", session.id)
            startActivity(intent)
        }
        binding.recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSessions.adapter = adapter

        // Наблюдаем за сессиями
        lifecycleScope.launch {
            AppDatabase.getInstance(this@RecorderActivity).sensorDao().getAllSessions().collectLatest { sessions ->
                adapter.submitList(sessions)
            }
        }

        // Live-радар: подписываемся на показания в реальном времени
        lifecycleScope.launch {
            sensorFusion.currentReading.collectLatest { reading ->
                if (reading != null && isRecording) {
                    // Проверяем, есть ли аномалия
                    val hasAnomaly = reading.anomalyStatus.contains("ВНИМАНИЕ") || 
                                     reading.anomalyStatus.contains("АНОМАЛИЯ")
                    
                    if (hasAnomaly) {
                        liveReadings.add(reading)
                        // Ограничиваем количество точек
                        if (liveReadings.size > maxLiveReadings) {
                            liveReadings.removeAt(0)
                        }
                        // Передаём в радар
                        binding.liveRadar.readings = liveReadings.toList()
                    }
                }
            }
        }

        binding.btnRecord.setOnClickListener {
            if (checkAudioPermission()) {
                toggleRecording()
            } else {
                requestAudioPermission()
            }
        }

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
                Toast.makeText(this, "Нужен доступ к микрофону", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleRecording() {
        if (!isRecording) {
            liveReadings.clear()
            binding.liveRadar.readings = emptyList()
            
            sensorFusion.startRecording("Комната")
            isRecording = true
            binding.btnRecord.text = "ОСТАНОВИТЬ ЗАПИСЬ"
            binding.btnRecord.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_cyan)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            binding.tvStatus.text = "СКАНИРОВАНИЕ..."
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
        } else {
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
