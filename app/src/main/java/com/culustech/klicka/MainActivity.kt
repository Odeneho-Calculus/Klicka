package com.culustech.klicka

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Main entry for Klicka MVP.
 * - Requests/validates permissions (Overlay, Microphone, Accessibility prompt via settings)
 * - Provides quick links to start overlay and voice services
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Microphone permission is required for voice control", Toast.LENGTH_LONG).show()
            }
            refreshStatus()
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Return from overlay settings
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        findViewById<Button>(R.id.btn_request_permissions).setOnClickListener { requestAllCriticalPermissions() }
        findViewById<Button>(R.id.btn_open_accessibility).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_start_overlay).setOnClickListener { startOverlayService() }
        findViewById<Button>(R.id.btn_start_voice).setOnClickListener { startVoiceService() }

        // Auto-start services once permissions are granted
        autoStartServicesIfReady()
        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, com.culustech.klicka.settings.SettingsActivity::class.java))
        }

        refreshStatus()
    }

    private fun refreshStatus() {
        val micGranted = isMicGranted()
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityHint = "Enable Klicka Accessibility Service in Settings"
        statusText.text = buildString {
            appendLine("Overlay: ${if (overlayGranted) "Granted" else "Missing"}")
            appendLine("Microphone: ${if (micGranted) "Granted" else "Missing"}")
            appendLine(accessibilityHint)
        }
        autoStartServicesIfReady()
    }

    private fun requestAllCriticalPermissions() {
        requestOverlayPermissionIfNeeded()
        requestMicIfNeeded()
    }

    private fun requestMicIfNeeded() {
        if (!isMicGranted()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun autoStartServicesIfReady() {
        val overlayGranted = Settings.canDrawOverlays(this)
        val micGranted = isMicGranted()
        if (overlayGranted) startOverlayService()
        if (micGranted) startVoiceService()
    }

    private fun requestOverlayPermissionIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun isMicGranted(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) true
        else checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startOverlayService() {
        // Use the AccessibilityService overlay (if enabled) instead of the deprecated service
        val svc = com.culustech.klicka.service.ClickAccessibilityService.ClickManagerHolder.instance
        if (svc != null) {
            svc.showOverlay()
        } else {
            Toast.makeText(this, "Enable Klicka in Accessibility settings to show overlay", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, com.culustech.klicka.service.VoiceRecognitionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}