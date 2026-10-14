package com.exorcist.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.exorcist.app.R
import com.exorcist.app.databinding.ActivityArScannerBinding
import java.util.concurrent.Executors
import kotlin.math.sqrt

class ARScannerActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityArScannerBinding
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    
    private var baselineValue = 0f
    private var isCalibrated = false
    
    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация сенсоров
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magnetometer == null) {
            binding.tvStatus.text = "МАГНИТОМЕТР НЕ НАЙДЕН"
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
        } else {
            binding.tvStatus.text = "ОЖИДАНИЕ КАМЕРЫ..."
        }

        // Кнопка назад
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Проверяем разрешение камеры
        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Разрешение камеры не получено", Toast.LENGTH_LONG).show()
                binding.tvStatus.text = "КАМЕРА НЕДОСТУПНА"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
                binding.tvStatus.text = "СКАНИРОВАНИЕ..."
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            } catch (e: Exception) {
                binding.tvStatus.text = "ОШИБКА КАМЕРЫ"
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val totalField = sqrt(x * x + y * y + z * z)

        if (!isCalibrated) {
            baselineValue = totalField
            isCalibrated = true
        }

        val deviation = totalField - baselineValue
        val absoluteDeviation = kotlin.math.abs(deviation)

        binding.tvValue.text = String.format("%.2f", totalField)

        when {
            absoluteDeviation < 10 -> {
                binding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
                binding.tvLevel.text = "НОРМА"
                binding.tvLevel.setTextColor(ContextCompat.getColor(this, R.color.neon_cyan))
            }
            absoluteDeviation < 30 -> {
                binding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.neon_yellow))
                binding.tvLevel.text = "ВНИМАНИЕ"
                binding.tvLevel.setTextColor(ContextCompat.getColor(this, R.color.neon_yellow))
            }
            absoluteDeviation < 50 -> {
                binding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
                binding.tvLevel.text = "АНОМАЛИЯ"
                binding.tvLevel.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
            }
            else -> {
                binding.tvValue.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
                binding.tvLevel.text = "⚠ КРИТИЧЕСКИЙ УРОВЕНЬ"
                binding.tvLevel.setTextColor(ContextCompat.getColor(this, R.color.neon_pink))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
