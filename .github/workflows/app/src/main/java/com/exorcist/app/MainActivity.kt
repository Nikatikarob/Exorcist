package com.exorcist.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exorcist.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Подключаем наш красивый дизайн из activity_main.xml
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        binding.btnAR.setOnClickListener {
            showToast("AR-Сканер: Инициализация камеры и магнитометра... (Скоро)")
            // startActivity(Intent(this, ARScannerActivity::class.java))
        }
        
        binding.btnMap.setOnClickListener { 
            showToast("Карта: Запуск построения 3D-модели... (Скоро)")
        }
        
        binding.btnAcoustic.setOnClickListener { 
            showToast("Акустика: Калибровка микрофонов... (Скоро)")
        }
        
        binding.btnEMF.setOnClickListener { 
            showToast("EMF-Гид: Поиск источников излучения... (Скоро)")
        }
        
        binding.btnAnomaly.setOnClickListener { 
            showToast("Детектор аномалий: Сканирование всех сенсоров... (Скоро)")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
