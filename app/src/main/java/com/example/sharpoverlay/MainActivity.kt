package com.example.sharpoverlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkOverlayPermission()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, SharpCaptureService::class.java).apply {
                    putExtra(SharpCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(SharpCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                statusText.text = "Статус: фильтр активен"
            } else {
                statusText.text = "Статус: захват экрана отклонён"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val modeGroup = findViewById<RadioGroup>(R.id.modeGroup)
        val seekBar = findViewById<SeekBar>(R.id.sharpnessSeekBar)
        val sharpnessLabel = findViewById<TextView>(R.id.sharpnessLabel)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            SharpCaptureService.currentMode = when (checkedId) {
                R.id.radioCas -> SharpMode.CAS
                R.id.radioRcas -> SharpMode.RCAS
                else -> SharpMode.DYNAMIC
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                SharpCaptureService.currentSharpness = progress / 100f
                sharpnessLabel.text = "Интенсивность: $progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<android.widget.Button>(R.id.btnStart).setOnClickListener {
            requestOverlayPermission()
        }

        findViewById<android.widget.Button>(R.id.btnCapture).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала выдай overlay-разрешение", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        findViewById<android.widget.Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, SharpCaptureService::class.java))
            statusText.text = "Статус: остановлено"
        }

        checkOverlayPermission()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Overlay-разрешение уже выдано", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkOverlayPermission() {
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "Статус: overlay-разрешение есть, можно запускать"
        } else {
            "Статус: нужно выдать overlay-разрешение"
        }
    }
}
