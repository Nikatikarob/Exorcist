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
    
    val spatialTracker = SpatialTracker()
    val anomalyDetector = AnomalyDetector()
    
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    
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
    
    private var audioFrequency = 0f
    private var audioAmplitude = 0f
    private var audioRecord: AudioRecord? = null
    private var isRecordingAudio = false
    
    private val database = AppDatabase.getInstance(context)
    private val dao = database.sensorDao()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var currentSessionId: Long = 0
    private var isRecording = false
    
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
            val session = SensorSession(locationName = locationName)
            currentSessionId = dao.insertSession(session)
            isRecording = true
            
            spatialTracker.reset()
            anomalyDetector.reset()
            
            magnetometer?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            accelerometer?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            gyroscope?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            barometer?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            lightSensor?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            proximitySensor?.let { sensorManager.registerListener(this@SensorFusion, it, SensorManager.SENSOR_DELAY_NORMAL) }
            
            startAudioRecording()
            saveLoop()
            
            Log.d(TAG, "Recording started: session $currentSessionId")
        }
    }
    
    fun stopRecording() {
        if (!isRecording) return
        
        scope.launch {
            isRecording = false
            sensorManager.unregisterListener(this@SensorFusion)
            stopAudioRecording()
            
            val session = dao.getSessionById(currentSessionId)
            session?.let {
                val updated = it.copy(
                    endTime = System.currentTimeMillis(),
                    isActive = false,
                    readingCount = dao.getReadingCount(currentSessionId)
                )
                dao.updateSession(updated)
            }
            
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
                    delay(100)
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
        var maxAmplitude = 0.0
        for (i in 0 until size) {
            val amplitude = Math.abs(buffer[i].toDouble())
            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude
            }
        }
        audioAmplitude = maxAmplitude.toFloat()
        if (maxAmplitude > 100) {
            audioFrequency = 18000f
        } else {
            audioFrequency = 0f
        }
    }
    
    private suspend fun saveLoop() {
        while (isRecording) {
            delay(1000)
            
            val detection = anomalyDetector.analyze(magneticTotal, audioAmplitude)
            
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
                proximity = proximity,
                posX = spatialTracker.x,
                posY = spatialTracker.y,
                posZ = spatialTracker.z,
                azimuth = spatialTracker.azimuth,
                pitch = spatialTracker.pitch,
                roll = spatialTracker.roll,
                anomalyStatus = detection.severity,
                anomalyReason = detection.reason
            )
            
            dao.insertReading(reading)
            Log.d(TAG, "Saved: mag=${magneticTotal}, audio=${audioAmplitude}, status=${detection.severity}")
        }
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        spatialTracker.onSensorChanged(event)
        
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
            Sensor.TYPE_PRESSURE -> pressure = event.values[0]
            Sensor.TYPE_LIGHT -> light = event.values[0]
            Sensor.TYPE_PROXIMITY -> proximity = event.values[0]
        }
        
        updateCurrentReading()
    }
    
    private fun updateCurrentReading() {
        val detection = anomalyDetector.analyze(magneticTotal, audioAmplitude)
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
            proximity = proximity,
            posX = spatialTracker.x,
            posY = spatialTracker.y,
            posZ = spatialTracker.z,
            azimuth = spatialTracker.azimuth,
            pitch = spatialTracker.pitch,
            roll = spatialTracker.roll,
            anomalyStatus = detection.severity,
            anomalyReason = detection.reason
        )
        _currentReading.value = reading
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    fun destroy() {
        stopRecording()
        scope.cancel()
    }
}
