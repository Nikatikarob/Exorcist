package com.exorcist.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exorcist.app.databinding.ActivityMainBinding

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
            showToast("AR-Сканер: В разработке")
        }
        
        binding.btnMap.setOnClickListener { 
            showToast("Карта: В разработке")
        }
        
        binding.btnAcoustic.setOnClickListener { 
            showToast("Акустика: В разработке")
        }
        
        binding.btnEMF.setOnClickListener { 
            showToast("EMF-Гид: В разработке")
        }
        
        binding.btnAnomaly.setOnClickListener { 
            showToast("Детектор аномалий: В разработке")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
