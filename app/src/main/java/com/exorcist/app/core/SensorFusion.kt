package com.exorcist.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.exorcist.app.data.AppDatabase
import com.exorcist.app.data.SensorReading
import com.exorcist.app.data.SensorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class SensorFusion(private val context: Context) : SensorEventListener {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // Датчики
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    
    // Текущие значения сенсоров
    private var magneticX = 0f
    private var magneticY = 0f
    private var magneticZ = 0f
    private var magneticTotal = 0f
    
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var accelTotal = 0f
    
    private var pressure = 0f
    private var light = 0f
    private var proximity = 0f
    
    // Аудио (ультразвук)
    private var audioFrequency = 0f
    private var audioAmplitude = 0f
    private var audioRecord: AudioRecord? = null
    private var isRecordingAudio = false
    
    // База данных
    private val database = AppDatabase.getInstance(context)
    private val dao = database.sensorDao()
    
    // Корутин скоуп
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Текущая сессия
    private var currentSessionId: Long = 0
    private var isRecording = false
    
    // Поток для UI
    private val _currentReading = MutableStateFlow<SensorReading?>(null)
    val currentReading: StateFlow<SensorReading?> = _currentReading
    
    companion object {
        private const val TAG = "SensorFusion"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    
    fun startRecording(locationName: String = "") {
        if (isRecording) return
        
        scope.launch {
            // Создаём новую сессию
            val session = SensorSession(locationName = locationName)
            currentSessionId = dao.insertSession(session)
            isRecording = true
            
            // Регистрируем слушатели сенсоров
            magnetometer?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            accelerometer?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            gyroscope?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            barometer?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            lightSensor?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            proximitySensor?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            
            // Запускаем запись аудио
            startAudioRecording()
            
            // Запускаем цикл сохранения
            saveLoop()
            
            Log.d(TAG, "Recording started: session $currentSessionId")
        }
    }
    
    fun stopRecording() {
        if (!isRecording) return
        
        scope.launch {
            isRecording = false
            
            // Отписываемся от сенсоров
            sensorManager.unregisterListener(this@SensorFusion)
            
            // Останавливаем аудио
            stopAudioRecording()
            
            // Обновляем сессию
            val session = dao.getSessionById(currentSessionId)
            session?.let {
                val updated = it.copy(
                    endTime = System.currentTimeMillis(),
                    isActive = false,
                    readingCount = dao.getReadingCount(currentSessionId)
                )
                dao.updateSession(updated)
            }
            
            // Очистка старых данных
            dao.cleanup()
            
            Log.d(TAG, "Recording stopped: session $currentSessionId")
        }
    }
    
    private fun startAudioRecording() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            
            audioRecord?.startRecording()
            isRecordingAudio = true
            
            scope.launch {
                val buffer = ShortArray(bufferSize)
                while (isRecordingAudio) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        analyzeAudio(buffer, readSize)
                    }
                    delay(100) // Анализируем каждые 100 мс
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
        }
    }
    
    private fun stopAudioRecording() {
        isRecordingAudio = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
    
    private fun analyzeAudio(buffer: ShortArray, size: Int) {
        // Простой анализ: находим максимальную амплитуду
        var maxAmplitude = 0.0
        for (i in 0 until size) {
            val amplitude = Math.abs(buffer[i].toDouble())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }
        
        audioAmplitude = maxAmplitude.toFloat()
        
        // Упрощённый анализ частоты (в реальности нужен FFT)
        // Пока просто фиксируем, что есть звук
        if (maxAmplitude > 100) {
            audioFrequency = 18000f // Заглушка: предполагаем ультразвук
        } else {
            audioFrequency = 0f
        }
    }
    
    private suspend fun saveLoop() {
        while (isRecording) {
            delay(1000) // Сохраняем каждую секунду
            
            val reading = SensorReading(
                sessionId = currentSessionId,
                magneticX = magneticX,
                magneticY = magneticY,
                magneticZ = magneticZ,
                magneticTotal = magneticTotal,
                audioFrequency = audioFrequency,
                audioAmplitude = audioAmplitude,
                accelerationX = accelX,
                accelerationY = accelY,
                accelerationZ = accelZ,
                accelerationTotal = accelTotal,
                pressure = pressure,
                light = light,
                proximity = proximity
            )
            
            dao.insertReading(reading)
            _currentReading.value = reading
            
            Log.d(TAG, "Saved reading: mag=${magneticTotal}, audio=${audioAmplitude}")
        }
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticX = event.values[0]
                magneticY = event.values[1]
                magneticZ = event.values[2]
                magneticTotal = sqrt(magneticX * magneticX + magneticY * magneticY + magneticZ * magneticZ)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelX = event.values[0]
                accelY = event.values[1]
                accelZ = event.values[2]
                accelTotal = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
            }
            Sensor.TYPE_PRESSURE -> {
                pressure = event.values[0]
            }
            Sensor.TYPE_LIGHT -> {
                light = event.values[0]
            }
            Sensor.TYPE_PROXIMITY -> {
                proximity = event.values[0]
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Игнорируем
    }
    
    fun destroy() {
        stopRecording()
        scope.cancel()
    }
}
