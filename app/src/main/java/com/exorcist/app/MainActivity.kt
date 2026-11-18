package com.exorcist.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.exorcist.app.databinding.ActivityMainBinding
import com.exorcist.app.ui.EMFGuideActivity
import com.exorcist.app.ui.ProbeActivity
import com.exorcist.app.ui.RoomMapActivity
import com.exorcist.app.ui.SonarActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAR.text = "КАРТА КОМНАТЫ"
        binding.btnAR.setOnClickListener {
            startActivity(Intent(this, RoomMapActivity::class.java))
        }

        binding.btnAcoustic.text = "ПАНЕЛЬ КАНАЛОВ"
        binding.btnAcoustic.setOnClickListener {
            startActivity(Intent(this, ProbeActivity::class.java))
        }

        binding.btnMap.text = "ЭХО-СОНАР"
        binding.btnMap.setOnClickListener {
            startActivity(Intent(this, SonarActivity::class.java))
        }

        binding.btnEMF.setOnClickListener {
            startActivity(Intent(this, EMFGuideActivity::class.java))
        }
    }
}
