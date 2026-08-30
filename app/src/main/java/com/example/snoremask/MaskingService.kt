package com.example.snoremask

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class MaskingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var audioTrack: AudioTrack
    private lateinit var wakeLock: PowerManager.WakeLock
    private val vibBuffer = RingBuffer<Float>(10000)
    private val micBuffer = RingBuffer<Float>(16000)
    private val yin = YinDetector()
    private val synth = CombSynthesizer()
    private val agc = Agc()
    @Volatile private var isRunning = false
    @Volatile private var useMicMode = false

    companion object {
        const val CHANNEL_ID = "snore_mask_channel"
        const val NOTIF_ID = 1
        private const val SENSOR_RATE_US = 500000
        private const val SAMPLE_RATE = 16000
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createNotificationChannel()
        initWakeLock()
        initAudioTrack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("SnoreMask", "onStartCommand called")
        try {
            startForeground(NOTIF_ID, buildNotification("校准中，请贴紧床板保持不动…"))
            Log.i("SnoreMask", "startForeground succeeded")
        } catch (e: Exception) {
            Log.e("SnoreMask", "startForeground failed", e)
        }
        Thread {
            try {
                Log.i("SnoreMask", "Calibration thread started")

                // 先尝试加速度计校准
                val vibPass = runVibCalibration()
                Log.i("SnoreMask", "Vib calibration result: $vibPass")

                if (vibPass) {
                    useMicMode = false
                    updateNotification("运行中（振动模式）— 屏蔽呼噜声")
                    startVibLoop()
                } else {
                    // 加速度计失败，尝试麦克风校准
                    Log.i("SnoreMask", "Vib failed, trying mic calibration")
                    updateNotification("校准中（麦克风模式）…")
                    val micPass = runMicCalibration()
                    Log.i("SnoreMask", "Mic calibration result: $micPass")

                    if (micPass) {
                        useMicMode = true
                        updateNotification("运行中（麦克风模式）— 屏蔽呼噜声")
                        startMicLoop()
                    } else {
                        updateNotification("校准失败：床架不传振且未检测到呼噜，建议用耳塞")
                        Thread.sleep(3000)
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e("SnoreMask", "Error in calibration thread", e)
                updateNotification("出错了: ${e.message}")
            }
        }.start()
        return START_STICKY
    }

    // ========== 加速度计校准 ==========
    private fun runVibCalibration(): Boolean {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        val buffer = mutableListOf<Float>()
        val latch = CountDownLatch(1)

        val calibListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                buffer.add(mag)
                if (buffer.size >= 5000) { latch.countDown() }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }

        sensorManager.registerListener(calibListener, sensor, SENSOR_RATE_US)
        latch.await(12, TimeUnit.SECONDS)
        sensorManager.unregisterListener(calibListener)

        if (buffer.size < 4000) return false
        val arr = buffer.toFloatArray()
        val mean = arr.average().toFloat()
        val centered = FloatArray(arr.size) { arr[it] - mean }

        var maxCorr = 0f
        for (lag in 32..320) {
            var sum = 0f
            for (i in 0 until centered.size - lag) {
                sum += centered[i] * centered[i + lag]
            }
            val norm = sum / (centered.size - lag)
            if (norm > maxCorr) maxCorr = norm
        }
        val variance = centered.map { it * it }.average().toFloat()
        val normalizedPeak = if (variance > 1e-10f) maxCorr / variance else 0f

        Log.i("SnoreMask", "Vib calib: normalizedPeak=$normalizedPeak, variance=$variance")
        return normalizedPeak > 0.15f
    }

    // ========== 麦克风校准 ==========
    private fun runMicCalibration(): Boolean {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        } catch (e: SecurityException) {
            Log.e("SnoreMask", "Mic permission denied", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SnoreMask", "AudioRecord init failed")
            return false
        }

        val buffer = ShortArray(SAMPLE_RATE * 8) // 8 秒
        record.startRecording()
        record.read(buffer, 0, buffer.size)
        record.stop()
        record.release()

        // 转 float 并归一化
        val floatBuf = FloatArray(buffer.size) { buffer[it].toFloat() / 32768f }

        // 检测低频能量（50–350 Hz 呼噜频段）
        val lowEnergy = bandEnergy(floatBuf, 50f, 350f)
        val highEnergy = bandEnergy(floatBuf, 1000f, 4000f)
        val ratio = if (highEnergy > 1e-10f) lowEnergy / highEnergy else 0f

        Log.i("SnoreMask", "Mic calib: lowEnergy=$lowEnergy, highEnergy=$highEnergy, ratio=$ratio")

        // 呼噜特征：低频能量远大于高频（> 3 倍）
        return ratio > 3.0f
    }

    private fun bandEnergy(buf: FloatArray, fLow: Float, fHigh: Float): Float {
        val n = buf.size
        val fftSize = minOf(n, 16000)
        var energy = 0f
        for (k in 0 until fftSize / 2) {
            val freq = k.toFloat() * SAMPLE_RATE.toFloat() / fftSize.toFloat()
            if (freq < fLow || freq > fHigh) continue
            var re = 0f; var im = 0f
            for (i in 0 until fftSize) {
                val angle = 2.0 * Math.PI * k * i / fftSize.toDouble()
                re += buf[i] * Math.cos(angle).toFloat()
                im -= buf[i] * Math.sin(angle).toFloat()
            }
            energy += re * re + im * im
        }
        return energy
    }

    // ========== 棕噪声生成 ==========
    private var brownNoiseState = 0f
    private fun generateBrownNoise(size: Int, gain: Float): FloatArray {
        val out = FloatArray(size)
        for (i in 0 until size) {
            val white = (Math.random() * 2 - 1).toFloat()
            brownNoiseState = (brownNoiseState + 0.02f * white).coerceIn(-1f, 1f)
            out[i] = brownNoiseState * gain
        }
        return out
    }

    // ========== 加速度计主循环 ==========
    private fun startVibLoop() {
        isRunning = true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(this, sensor, SENSOR_RATE_US)
        audioTrack.play()

        object : Thread("VibMaskingLoop") {
            override fun run() {
                while (isRunning) {
                    val vibFrame = vibBuffer.readFrame(1024)
                    val clean = Preprocess.process(vibFrame)
                    val (f0, conf) = yin.detect(clean)
                    if (conf > 0.5f) { synth.updateF0(f0) }
                    val maskFrame = synth.nextFrame()
                    val residual = maskFrame.map { it.toDouble() * it }.sum() * 0.01
                    val gained = agc.process(maskFrame, residual)
                    val brownNoise = generateBrownNoise(gained.size, 0.15f)
                    val mixed = FloatArray(gained.size) {
                        (gained[it] + brownNoise[it]).coerceIn(-0.89f, 0.89f)
                    }
                    audioTrack.write(mixed, 0, mixed.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }.start()
    }

    // ========== 麦克风主循环 ==========
    private fun startMicLoop() {
        isRunning = true
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        } catch (e: SecurityException) {
            Log.e("SnoreMask", "Mic permission denied", e)
            stopSelf()
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SnoreMask", "AudioRecord init failed")
            stopSelf()
            return
        }

        record.startRecording()
        audioTrack.play()

        // 麦克风采集线程
        object : Thread("MicReader") {
            override fun run() {
                val readBuf = ShortArray(1024)
                while (isRunning) {
                    val read = record.read(readBuf, 0, readBuf.size)
                    if (read > 0) {
                        for (i in 0 until read) {
                            micBuffer.write(readBuf[i].toFloat() / 32768f)
                        }
                    }
                }
            }
        }.start()

        // DSP 处理线程
        object : Thread("MicMaskingLoop") {
            override fun run() {
                while (isRunning) {
                    val micFrame = micBuffer.readFrame(1024)
                    val clean = Preprocess.process(micFrame)
                    val (f0, conf) = yin.detect(clean)
                    if (conf > 0.5f) { synth.updateF0(f0) }
                    val maskFrame = synth.nextFrame()
                    val residual = maskFrame.map { it.toDouble() * it }.sum() * 0.01
                    val gained = agc.process(maskFrame, residual)
                    val brownNoise = generateBrownNoise(gained.size, 0.15f)
                    val mixed = FloatArray(gained.size) {
                        (gained[it] + brownNoise[it]).coerceIn(-0.89f, 0.89f)
                    }
                    audioTrack.write(mixed, 0, mixed.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val mag = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        vibBuffer.write(mag)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun initAudioTrack() {
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attr)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(bufSize, 4096) * 4)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun initWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnoreMask::WakeLock")
        wakeLock.acquire(8 * 60 * 60 * 1000L)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Snore Masking", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnoreMask")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        isRunning = false
        sensorManager.unregisterListener(this)
        if (::audioTrack.isInitialized) { audioTrack.stop(); audioTrack.release() }
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}