package com.exorcist.app.core

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import java.util.Locale
import kotlin.math.sqrt

object DeviceCapabilities {

    private fun f(v: Float, d: Int = 2): String = String.format(Locale.US, "%.${d}f", v)
    private fun f(v: Double, d: Int = 2): String = String.format(Locale.US, "%.${d}f", v)

    fun collect(context: Context): String {
        val sb = StringBuilder()
        val pm = context.packageManager

        sb.appendLine("=== УСТРОЙСТВО ===")
        sb.appendLine("Модель: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        // --- КРАТКАЯ СВОДКА ---
        sb.appendLine("=== КЛЮЕВЫЕ КАНАЛЫ ===")
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unproc = if (Build.VERSION.SDK_INT >= 24) {
                am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) ?: "null"
            } else "API<24"
            val micUs = am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND) ?: "null"
            val spkUs = am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND) ?: "null"
            sb.appendLine("UNPROCESSED mic: $unproc")
            sb.appendLine("MIC near-ultrasound: $micUs")
            sb.appendLine("SPEAKER near-ultrasound: $spkUs")
        } catch (e: Exception) {
            sb.appendLine("audio props error: ${e.message}")
        }

        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sb.appendLine("Магнитометр: " + if (mag == null) "НЕТ" else "${f(mag.resolution, 3)} мкТл, макс ${hz(mag.minDelay)} Гц")
            sb.appendLine("Акселерометр: " + if (acc == null) "НЕТ" else "макс ${hz(acc.minDelay)} Гц")
            sb.appendLine("Гироскоп: " + if (gyr == null) "НЕТ" else "макс ${hz(gyr.minDelay)} Гц")
            sb.appendLine("Барометр: " + if (sm.getDefaultSensor(Sensor.TYPE_PRESSURE) == null) "НЕТ" else "ЕСТЬ")
        } catch (e: Exception) {
            sb.appendLine("sensor error: ${e.message}")
        }

        sb.appendLine("BLE: ${has(pm, PackageManager.FEATURE_BLUETOOTH_LE)}")
        sb.appendLine("WiFi: ${has(pm, PackageManager.FEATURE_WIFI)}")
        sb.appendLine("WiFi RTT (802.11mc): ${has(pm, PackageManager.FEATURE_WIFI_RTT)}")
        sb.appendLine("NFC: ${has(pm, PackageManager.FEATURE_NFC)}")
        sb.appendLine("ИК-бластер: ${has(pm, PackageManager.FEATURE_CONSUMER_IR)}")
        if (Build.VERSION.SDK_INT >= 31) {
            sb.appendLine("UWB: ${has(pm, PackageManager.FEATURE_UWB)}")
        } else {
            sb.appendLine("UWB: API<31, не проверить")
        }
        sb.appendLine()

        // --- АУДИО ---
        sb.appendLine("=== АУДИО ===")
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            sb.appendLine("Выход: ${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Гц, " +
                    "буфер ${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
            for (rate in intArrayOf(44100, 48000, 96000)) {
                val n = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                sb.appendLine("Вход $rate Гц: " + if (n > 0) "OK (min $n байт)" else "НЕ ПОДДЕРЖИВАЕТСЯ")
            }
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            var micCount = 0
            for (d in inputs) {
                if (d.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    micCount++
                    sb.appendLine("Микрофон: ${d.productName}, частоты ${d.sampleRates.joinToString(",")}")
                }
            }
            sb.appendLine("Встроенных микрофонов: $micCount")
        } catch (e: Exception) {
            sb.appendLine("audio error: ${e.message}")
        }
        sb.appendLine()

        // --- КАМЕРЫ ---
        sb.appendLine("=== КАМЕРЫ ===")
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
                val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)

                sb.appendLine("--- камера $id ---")
                sb.appendLine("  положение: ${facingName(facing)}, уровень: ${levelName(level)}")
                if (size != null) {
                    val diag = sqrt(size.width * size.width + size.height * size.height)
                    val crop = if (diag > 0f) 43.27f / diag else 0f
                    sb.appendLine("  сенсор: ${f(size.width)}x${f(size.height)} мм")
                    sb.appendLine("  фокус: " + focals.joinToString(", ") { "${f(it)}мм (~${f(it * crop, 0)}мм экв)" })
                }
                if (exp != null) {
                    sb.appendLine("  выдержка: до ${f(exp.upper / 1e9, 3)} с")
                }
                if (iso != null) sb.appendLine("  ISO: до ${iso.upper}")
                if (minFocus != null && minFocus > 0f) {
                    sb.appendLine("  мин. фокус: ${f(100f / minFocus, 1)} см")
                }
                sb.appendLine("  вспышка: $flash, timestamp: ${if (ts == 1) "REALTIME" else "UNKNOWN"}")
                val names = mutableListOf<String>()
                for (k in caps) {
                    when (k) {
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> names.add("RAW")
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> names.add("MANUAL")
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> names.add("DEPTH")
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> names.add("LOGICAL_MULTI")
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> names.add("MOTION_TRACK")
                    }
                }
                sb.appendLine("  возможности: ${if (names.isEmpty()) "базовые" else names.joinToString(", ")}")
            }
        } catch (e: Exception) {
            sb.appendLine("camera error: ${e.message}")
        }
        sb.appendLine()

        // --- ВСЕ ДАТЧИКИ ---
        sb.appendLine("=== ВСЕ ДАТЧИКИ ===")
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val list = sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.stringType }
            sb.appendLine("Всего: ${list.size}")
            for (s in list) {
                val name = s.stringType.removePrefix("android.sensor.")
                sb.appendLine("$name | ${s.name}")
                sb.appendLine("   диапазон ${f(s.maximumRange)} | разрешение ${f(s.resolution, 4)} | ${hz(s.minDelay)} Гц | ${f(s.power)} мА")
            }
        } catch (e: Exception) {
            sb.appendLine("sensor list error: ${e.message}")
        }
        sb.appendLine()

        // --- ПРОЧЕЕ ---
        sb.appendLine("=== ПРОЧЕЕ ===")
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            sb.appendLine("Провайдеры позиции: ${lm.allProviders.joinToString(", ")}")
        } catch (e: Exception) {
            sb.appendLine("location error: ${e.message}")
        }
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            sb.appendLine("Bluetooth адаптер: ${if (bm.adapter == null) "НЕТ" else "ЕСТЬ"}")
        } catch (e: Exception) {
            sb.appendLine("bt error: ${e.message}")
        }
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val amp = if (Build.VERSION.SDK_INT >= 26) vib?.hasAmplitudeControl() == true else false
            sb.appendLine("Вибратор: ${if (vib?.hasVibrator() == true) "ЕСТЬ" else "НЕТ"}, управление амплитудой: $amp")
        } catch (e: Exception) {
            sb.appendLine("vibrator error: ${e.message}")
        }

        return sb.toString()
    }

    private fun hz(minDelayUs: Int): String {
        return if (minDelayUs > 0) f(1_000_000f / minDelayUs, 0) else "по событию"
    }

    private fun has(pm: PackageManager, feature: String): String {
        return if (pm.hasSystemFeature(feature)) "ЕСТЬ" else "НЕТ"
    }

    private fun facingName(facing: Int?): String = when (facing) {
        CameraCharacteristics.LENS_FACING_FRONT -> "фронтальная"
        CameraCharacteristics.LENS_FACING_BACK -> "задняя"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "внешняя"
        else -> "неизвестно"
    }

    private fun levelName(level: Int?): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "неизвестно"
    }
}
