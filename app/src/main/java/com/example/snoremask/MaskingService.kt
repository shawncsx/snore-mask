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
import android.media.AudioTrack
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
    private val yin = YinDetector()
    private val synth = CombSynthesizer()
    private val agc = Agc()
    @Volatile private var isRunning = false
    private var currentF0 = 100f

    companion object {
        const val CHANNEL_ID = "snore_mask_channel"
        const val NOTIF_ID = 1
        private const val SENSOR_RATE_US = 500000
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
                val pass = runCalibration()
                Log.i("SnoreMask", "Calibration result: $pass")
                if (pass) {
                    updateNotification("运行中 — 屏蔽呼噜声")
                    startMaskingLoop()
                } else {
                    updateNotification("校准失败：床架不传振，建议用耳塞")
                    Thread.sleep(3000)
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e("SnoreMask", "Error in calibration thread", e)
                updateNotification("出错了: ${e.message}")
            }
        }.start()
        return START_STICKY
    }

    private fun runCalibration(): Boolean {
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

        Log.i("SnoreMask", "Calib: normalizedPeak=$normalizedPeak, variance=$variance, samples=${buffer.size}")
        Log.i("SnoreMask", "Calib: normalizedPeak=$normalizedPeak, threshold=0.15")
        return normalizedPeak > 0.15f
    }

    private fun startMaskingLoop() {
        isRunning = true
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager.registerListener(this, sensor, SENSOR_RATE_US)
        audioTrack.play()

        object : Thread("MaskingLoop") {
            override fun run() {
                while (isRunning) {
                    val vibFrame = vibBuffer.readFrame(1024)
                    val clean = Preprocess.process(vibFrame)
                    val (f0, conf) = yin.detect(clean)
                    if (conf > 0.5f) {
                        currentF0 = f0
                        synth.updateF0(f0)
                    }
                    val maskFrame = synth.nextFrame()
                    val residual = maskFrame.map { it.toDouble() * it }.sum() * 0.01
                    val gained = agc.process(maskFrame, residual)
                    val limited = FloatArray(gained.size) { gained[it].coerceIn(-0.89f, 0.89f) }
                    audioTrack.write(limited, 0, limited.size, AudioTrack.WRITE_BLOCKING)
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
            .setSampleRate(16000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bufSize = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
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