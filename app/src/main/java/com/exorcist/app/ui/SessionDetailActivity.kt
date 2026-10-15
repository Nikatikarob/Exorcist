package com.exorcist.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.exorcist.app.data.AppDatabase
import com.exorcist.app.databinding.ActivitySessionDetailBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionDetailBinding
    private var sessionId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getLongExtra("SESSION_ID", 0)
        if (sessionId == 0L) {
            Toast.makeText(this, "Ошибка: сессия не найдена", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

        // Загружаем данные и рисуем радар
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@SessionDetailActivity).sensorDao()
            val session = dao.getSessionById(sessionId)
            
            if (session != null) {
                binding.tvLocation.text = "${session.locationName} • ${session.readingCount} замеров • ${dateFormat.format(session.startTime)}"
                
                val readings = dao.getReadingsBySession(sessionId).first()
                
                // Передаем данные в наш кастомный Радар
                binding.radarView.readings = readings
            } else {
                Toast.makeText(this@SessionDetailActivity, "Данные не найдены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }
}
