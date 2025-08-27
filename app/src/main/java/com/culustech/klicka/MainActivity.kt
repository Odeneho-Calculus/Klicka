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
    private lateinit var requestPermissionsButton: Button
    private lateinit var openAccessibilityButton: Button
    private lateinit var startOverlayButton: Button
    private lateinit var startVoiceButton: Button

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
        requestPermissionsButton = findViewById(R.id.btn_request_permissions)
        openAccessibilityButton = findViewById(R.id.btn_open_accessibility)
        startOverlayButton = findViewById(R.id.btn_start_overlay)
        startVoiceButton = findViewById(R.id.btn_start_voice)

        requestPermissionsButton.setOnClickListener { requestAllCriticalPermissions() }
        openAccessibilityButton.setOnClickListener { openAccessibilitySettings() }
        startOverlayButton.setOnClickListener { startOverlayService() }
        startVoiceButton.setOnClickListener { startVoiceService() }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, com.culustech.klicka.settings.SettingsActivity::class.java))
        }

        // Initialize the file logger
        com.culustech.klicka.log.FileLogger.getInstance().init()
        com.culustech.klicka.log.FileLogger.i("MainActivity", "Application started")

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()

        // Auto-start services when the app is resumed and permissions are granted
        autoStartServicesIfReady()
    }

    private fun refreshStatus() {
        val micGranted = isMicGranted()
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        // Check if services are running
        val overlayRunning = isOverlayRunning()
        val voiceRunning = isVoiceServiceRunning()

        // Log status
        com.culustech.klicka.log.FileLogger.d("MainActivity",
            "Status - Mic: $micGranted, Overlay: $overlayGranted, " +
            "Accessibility: $accessibilityEnabled, " +
            "Overlay Running: $overlayRunning, Voice Running: $voiceRunning")

        // Update status text
        statusText.text = buildString {
            appendLine("Overlay: ${if (overlayGranted) "Granted" else "Missing"}")
            appendLine("Microphone: ${if (micGranted) "Granted" else "Missing"}")
            appendLine("Accessibility: ${if (accessibilityEnabled) "Enabled" else "Disabled"}")
            appendLine("Overlay Service: ${if (overlayRunning) "Running" else "Stopped"}")
            appendLine("Voice Service: ${if (voiceRunning) "Running" else "Stopped"}")
        }

        // Update button states based on permissions and service status
        requestPermissionsButton.isEnabled = !(micGranted && overlayGranted)
        openAccessibilityButton.isEnabled = !accessibilityEnabled

        // Disable start buttons if services are already running
        startOverlayButton.isEnabled = overlayGranted && !overlayRunning
        startVoiceButton.isEnabled = micGranted && !voiceRunning
    }

    /**
     * Check if the overlay is currently running
     */
    private fun isOverlayRunning(): Boolean {
        val svc = com.culustech.klicka.service.ClickAccessibilityService.ClickManagerHolder.instance
        return svc != null && svc.isOverlayVisible()
    }

    /**
     * Check if the voice service is running
     */
    private fun isVoiceServiceRunning(): Boolean {
        return com.culustech.klicka.service.VoiceRecognitionService.shouldBeRunning
    }

    /**
     * Checks if the accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityServiceName = packageName + "/" + com.culustech.klicka.service.ClickAccessibilityService::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { it.equals(accessibilityServiceName, ignoreCase = true) }
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
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        // Check if services are already running
        val overlayRunning = isOverlayRunning()
        val voiceRunning = isVoiceServiceRunning()

        com.culustech.klicka.log.FileLogger.i("MainActivity",
            "Auto-starting services - Permissions: Overlay=$overlayGranted, Mic=$micGranted, " +
            "Accessibility=$accessibilityEnabled, Services: Overlay=$overlayRunning, Voice=$voiceRunning")

        // Start overlay service if permissions are granted and it's not already running
        if (accessibilityEnabled && overlayGranted && !overlayRunning) {
            com.culustech.klicka.log.FileLogger.i("MainActivity", "Auto-starting overlay service")
            // Use the accessibility service for overlay instead of the standalone service
            val svc = com.culustech.klicka.service.ClickAccessibilityService.ClickManagerHolder.instance
            svc?.showOverlay()

            // Update button state
            startOverlayButton.isEnabled = false
        }

        // Start voice service if microphone permission is granted and it's not already running
        if (micGranted && !voiceRunning) {
            com.culustech.klicka.log.FileLogger.i("MainActivity", "Auto-starting voice service")
            startVoiceService()

            // Update button state
            startVoiceButton.isEnabled = false
        }

        // Refresh status after starting services
        refreshStatus()
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