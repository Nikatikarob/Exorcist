package com.exorcist.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exorcist.app.R
import com.exorcist.app.databinding.ActivityEmfGuideBinding
import kotlin.math.sqrt

class EMFGuideActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityEmfGuideBinding
    private lateinit var sensorManager: SensorManager
    private var magnetometer: Sensor? = null
    
    private var baselineValue = 0f
    private var isCalibrated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmfGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация сенсоров
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (magnetometer == null) {
            Toast.makeText(this, "Магнитометр не найден на этом устройстве", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = "ДАТЧИК НЕ НАЙДЕН"
            binding.tvStatus.setTextColor(getColor(R.color.neon_pink))
        } else {
            binding.tvStatus.text = "ОЖИДАНИЕ ДАННЫХ..."
        }

        // Кнопка калибровки
        binding.btnCalibrate.setOnClickListener {
            calibrate()
        }

        // Кнопка назад
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun calibrate() {
        if (magnetometer == null) return
        
        // Сбрасываем базовое значение
        baselineValue = 0f
        isCalibrated = false
        binding.tvStatus.text = "КАЛИБРОВКА..."
        binding.tvStatus.setTextColor(getColor(R.color.neon_yellow))
        binding.progressBar.progress = 0
        
        Toast.makeText(this, "Калибровка... Держите телефон в покое", Toast.LENGTH_SHORT).show()
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

        // Получаем значения по осям X, Y, Z
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Рассчитываем общее магнитное поле (векторная сумма)
        val totalField = sqrt(x * x + y * y + z * z)

        // Если не откалибровано, устанавливаем базовое значение (нормальный фон ~30-60 мкТл)
        if (!isCalibrated) {
            baselineValue = totalField
            isCalibrated = true
            binding.tvStatus.text = "МОНИТОРИНГ"
            binding.tvStatus.setTextColor(getColor(R.color.neon_cyan))
        }

        // Рассчитываем отклонение от нормы
        val deviation = totalField - baselineValue
        val absoluteDeviation = kotlin.math.abs(deviation)

        // Обновляем отображение
        binding.tvValue.text = String.format("%.2f", totalField)

        // Обновляем прогресс-бар (максимум 200 мкТл)
        val progress = (totalField / 200f * 100).toInt().coerceIn(0, 100)
        binding.progressBar.progress = progress

        // Меняем цвет в зависимости от уровня
        when {
            absoluteDeviation < 10 -> {
                // Норма - бирюзовый
                binding.tvValue.setTextColor(getColor(R.color.neon_cyan))
                binding.tvStatus.text = "НОРМА"
                binding.tvStatus.setTextColor(getColor(R.color.neon_cyan))
            }
            absoluteDeviation < 30 -> {
                // Лёгкое отклонение - жёлтый
                binding.tvValue.setTextColor(getColor(R.color.neon_yellow))
                binding.tvStatus.text = "ВНИМАНИЕ"
                binding.tvStatus.setTextColor(getColor(R.color.neon_yellow))
            }
            absoluteDeviation < 50 -> {
                // Среднее отклонение - розовый
                binding.tvValue.setTextColor(getColor(R.color.neon_pink))
                binding.tvStatus.text = "АНОМАЛИЯ"
                binding.tvStatus.setTextColor(getColor(R.color.neon_pink))
            }
            else -> {
                // Сильное отклонение - красный (мигание)
                binding.tvValue.setTextColor(getColor(R.color.neon_pink))
                binding.tvStatus.text = "⚠ КРИТИЧЕСКИЙ УРОВЕНЬ"
                binding.tvStatus.setTextColor(getColor(R.color.neon_pink))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Можно добавить обработку точности сенсора
    }
}
