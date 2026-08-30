package com.example.snoremask

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val REQ_CODE = 100
    private val perms = mutableListOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.WAKE_LOCK
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (hasAllPerms()) startMasking() else requestPerms()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, MaskingService::class.java))
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasAllPerms() = perms.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerms() = ActivityCompat.requestPermissions(this, perms, REQ_CODE)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMasking()
        } else {
            Toast.makeText(this, "需要传感器和通知权限才能工作", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMasking() {
        Log.i("SnoreMask", "Starting MaskingService")
        try {
            startForegroundService(Intent(this, MaskingService::class.java))
            Toast.makeText(this, "校准中，请将手机贴紧床板保持不动…", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("SnoreMask", "Failed to start service", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}