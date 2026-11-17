package com.exorcist.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exorcist.app.databinding.ActivityMainBinding
import com.exorcist.app.ui.CapabilitiesActivity
import com.exorcist.app.ui.EMFGuideActivity
import com.exorcist.app.ui.RecorderActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAR.setOnClickListener {
            startActivity(Intent(this, RecorderActivity::class.java))
        }

        binding.btnAcoustic.text = "ДИАГНОСТИКА"
        binding.btnAcoustic.setOnClickListener {
            startActivity(Intent(this, CapabilitiesActivity::class.java))
        }

        binding.btnMap.setOnClickListener {
            Toast.makeText(this, "Раздел в разработке", Toast.LENGTH_SHORT).show()
        }

        binding.btnEMF.setOnClickListener {
            startActivity(Intent(this, EMFGuideActivity::class.java))
        }
    }
}
