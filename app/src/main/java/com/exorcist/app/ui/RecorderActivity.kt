package com.exorcist.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private var vibrator: Vibrator? = null
    
    private var isRecording = false
    private val liveReadings = mutableListOf<SensorReading>()
    private val maxLiveReadings = 300
    private var lastVibrationTime = 0L

    companion object {
        private const val AUDIO_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация вибратора
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        sensorFusion = SensorFusion(this)
        
        adapter = SessionAdapter { session ->
            val intent = android.content.Intent(this, SessionDetailActivity::class.java)
            intent.putExtra("SESSION_ID", session.id)
            startActivity(intent)
        }
        
        binding.recyclerViewSessions.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewSessions.adapter = adapter

        lifecycleScope.launch {
            AppDatabase.getInstance(this@RecorderActivity).sensorDao().getAllSessions().collectLatest { sessions ->
                adapter.submitList(sessions)
            }
        }

        // Live-радар + вибрация
        lifecycleScope.launch {
            sensorFusion.currentReading.collectLatest { reading ->
                if (reading != null && isRecording) {
                    liveReadings.add(reading)
                    
                    if (liveReadings.size > maxLiveReadings) {
                        liveReadings.removeAt(0)
                    }
                    
                    // Вычисляем силу сигнала для индикатора
                    val hasCorrelation = reading.anomalyStatus.contains("АНОМАЛИЯ")
                    val hasAnomaly = reading.anomalyStatus.contains("ВНИМАНИЕ") || hasCorrelation
                    
                    if (hasAnomaly) {
                        val strength = when {
                            hasCorrelation -> 0.9f
                            reading.anomalyStatus.contains("ВНИМАНИЕ") -> 0.5f
                            else -> 0.2f
                        }
                        
                        // Обновляем индикатор
                        runOnUiThread {
                            binding.liveRadar.signalStrength = strength
                            binding.liveRadar.readings = liveReadings.toList()
                        }
                        
                        // Вибрация при корреляции (не чаще раза в 2 секунды)
                        if (hasCorrelation) {
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastVibrationTime > 2000) {
                                vibrate()
                                lastVibrationTime = currentTime
                            }
                        }
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

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(200)
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
            binding.liveRadar.signalStrength = 0f
            
            sensorFusion.startRecording("Комната")
            isRecording = true
            
            binding.btnRecord.text = "ОСТАНОВИТЬ ЗАПИСЬ"
            binding.btnRecord.strokeColor = ContextCompat.getColorStateList(this, R.color.neon_cyan)
            binding.btnRecord.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            
            binding.tvStatus.text = "СКАНИРОВАНИЕ... (КАЛИБРОВКА 15 сек)"
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
