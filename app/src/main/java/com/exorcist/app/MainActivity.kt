package com.exorcist.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exorcist.app.databinding.ActivityMainBinding
import com.exorcist.app.ui.EMFGuideActivity
import com.exorcist.app.ui.RecorderActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    private fun setupButtons() {
        binding.btnAR.setOnClickListener {
            // Открываем Нейро-сканер (бывший AR)
            startActivity(Intent(this, RecorderActivity::class.java))
        }
        
        binding.btnMap.setOnClickListener { 
            showToast("Карта: В разработке")
        }
        
        binding.btnAcoustic.setOnClickListener { 
            showToast("Акустика: В разработке")
        }
        
        binding.btnEMF.setOnClickListener { 
            startActivity(Intent(this, EMFGuideActivity::class.java))
        }
        
        binding.btnAnomaly.setOnClickListener { 
            showToast("Детектор аномалий: В разработке")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
