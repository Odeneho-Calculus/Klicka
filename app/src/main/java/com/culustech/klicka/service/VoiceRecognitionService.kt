package com.culustech.klicka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.culustech.klicka.MainActivity
import com.culustech.klicka.R
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.culustech.klicka.log.FileLogger

/**
 * Persistent foreground service with continuous voice recognition.
 * Continues to run in the background even when the app is closed.
 *
 * MVP commands:
 * - "clicker activate" -> acknowledge listening
 * - "click" -> trigger click at all points
 * - "click [number]" -> trigger click at specific point
 * - "deactivate" -> stop listening
 */
class VoiceRecognitionService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var restartReceiver: BroadcastReceiver? = null

    companion object {
        private const val WAKE_LOCK_TAG = "Klicka:VoiceRecognitionWakeLock"
        private const val ACTION_RESTART_SERVICE = "com.culustech.klicka.RESTART_VOICE_SERVICE"
        private const val NOTIFICATION_ID = 1002

        // Flag to track if service should be running
        @Volatile var shouldBeRunning = false

        // Static instance for easy access
        @Volatile private var instance: VoiceRecognitionService? = null

        /**
         * Check if the service is currently running
         */
        fun isRunning(): Boolean {
            return instance != null && shouldBeRunning
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Store instance for static access
        instance = this

        // Initialize file logger
        FileLogger.getInstance().init()
        FileLogger.i("VoiceService", "Voice recognition service created")

        // Acquire partial wake lock to keep CPU running for voice recognition
        acquireWakeLock()

        // Register receiver for service restart
        registerRestartReceiver()

        startAsForeground()
        startListening()

        // Mark service as running
        shouldBeRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If service is killed, it will be restarted with the last delivered intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        releaseWakeLock()
        unregisterRestartReceiver()

        // Clear the static instance reference
        instance = null

        // If service should still be running but was destroyed, schedule restart
        if (shouldBeRunning) {
            scheduleRestart()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
            wakeLock?.acquire(10*60*1000L) // 10 minutes timeout as safety measure
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun registerRestartReceiver() {
        restartReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_RESTART_SERVICE) {
                    startService(Intent(context, VoiceRecognitionService::class.java))
                }
            }
        }
        registerReceiver(restartReceiver, IntentFilter(ACTION_RESTART_SERVICE))
    }

    private fun unregisterRestartReceiver() {
        restartReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore if not registered
            }
        }
        restartReceiver = null
    }

    private fun scheduleRestart() {
        val intent = Intent(ACTION_RESTART_SERVICE)
        intent.setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val triggerTime = System.currentTimeMillis() + 1000 // 1 second delay

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                android.app.AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    private fun startAsForeground() {
        val channelId = "klicka_voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Klicka Voice",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        // Create intent to open main activity when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Klicka voice active")
            .setContentText("Listening for commands: clicker activate, click, click [n], deactivate")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // If speech recognition is not available, try again after delay
            mainHandler.postDelayed({ startListening() }, 5000)
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // Retry with small backoff on errors
                mainHandler.postDelayed({ restart() }, 800)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
                handleResults(texts)
                // Brief delay to avoid hot loop
                mainHandler.postDelayed({ restart() }, 400)
            }
        })
        restart()
    }

    private fun restart() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.startListening(intent)
    }

    private fun handleResults(texts: ArrayList<String>) {
        val normalized = texts.firstOrNull()?.lowercase() ?: return
        // Always toast what was heard
        android.widget.Toast.makeText(this, "Heard: $normalized", android.widget.Toast.LENGTH_SHORT).show()

        // Log the recognized command
        FileLogger.i("VoiceService", "Voice command recognized: '$normalized'")

        val store = com.culustech.klicka.data.ClickPointStore(this)
        val manager = com.culustech.klicka.click.ClickManager(this)
        val svc = com.culustech.klicka.service.ClickAccessibilityService.ClickManagerHolder.instance

        when {
            normalized == "clicker activate" || normalized == "clicker" || normalized == "activate" -> {
                // Feedback and ensure overlay is available
                FileLogger.i("VoiceService", "Activate command received")
                android.widget.Toast.makeText(this, "Voice activated", android.widget.Toast.LENGTH_SHORT).show()

                // Show accessibility overlay via the service (if enabled)
                if (svc != null) {
                    FileLogger.i("VoiceService", "Showing overlay and enabling add mode")
                    svc.showOverlay()

                    // Make sure markers are visible and in add mode
                    svc.showMarkers()
                    svc.setAddMode(true)

                    // Check if there are any click points already defined
                    val pointCount = svc.getClickPointCount()
                    if (pointCount > 0) {
                        FileLogger.i("VoiceService", "Found $pointCount existing click points")
                        android.widget.Toast.makeText(this,
                            "Overlay activated with $pointCount markers. Say 'click' to use them or tap to add more.",
                            android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        FileLogger.i("VoiceService", "No click points defined yet")
                        android.widget.Toast.makeText(this,
                            "Overlay activated in add mode. Tap to add markers.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    FileLogger.e("VoiceService", "Accessibility service not available")
                    android.widget.Toast.makeText(this, "Enable Klicka Accessibility Service to show overlay", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            normalized == "deactivate" -> {
                // Mark service as not running to prevent auto-restart
                shouldBeRunning = false
                stopSelf()
            }
            normalized == "hide overlay" || normalized == "hide markers" -> {
                // Hide the overlay but keep the service running
                svc?.hideOverlay()
                android.widget.Toast.makeText(this, "Overlay hidden", android.widget.Toast.LENGTH_SHORT).show()
            }
            normalized == "show overlay" -> {
                // Show the overlay
                svc?.showOverlay() ?: run {
                    android.widget.Toast.makeText(this, "Enable Klicka Accessibility Service to show overlay", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            normalized == "show markers" -> {
                // Show the markers
                svc?.showMarkers() ?: run {
                    android.widget.Toast.makeText(this, "Enable Klicka Accessibility Service to show markers", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            normalized == "done" || normalized == "finish" -> {
                // Hide the markers but keep the floating button
                svc?.hideMarkers()
                android.widget.Toast.makeText(this, "Markers hidden, ready for clicks", android.widget.Toast.LENGTH_SHORT).show()
            }
            normalized.matches("double click \\d+".toRegex()) -> {
                val id = normalized.substringAfter("double click ").trim().toIntOrNull()
                val point = store.loadPoints().firstOrNull { it.id == id }
                if (point != null) {
                    if (svc == null) android.widget.Toast.makeText(this, "Enable Accessibility to click", android.widget.Toast.LENGTH_LONG).show()
                    manager.doubleClickPoint(point)
                    android.widget.Toast.makeText(this, "Double clicking $id", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Marker $id not found", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            normalized == "double click" -> {
                val points = store.loadPoints()
                if (points.isNotEmpty()) {
                    if (svc == null) android.widget.Toast.makeText(this, "Enable Accessibility to click", android.widget.Toast.LENGTH_LONG).show()
                    manager.doubleClick(points)
                    android.widget.Toast.makeText(this, "Double clicking all", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "No markers to click", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            normalized.matches("click \\d+".toRegex()) -> {
                handleClickNumberCommand(normalized, store, manager, svc)
            }
            normalized == "click" -> {
                handleClickAllCommand(store, manager, svc)
            }
            normalized == "home" || normalized == "go home" -> {
                // Launch main activity
                val intent = Intent(this, com.culustech.klicka.MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        }
    }

    private fun handleClickNumberCommand(command: String, store: com.culustech.klicka.data.ClickPointStore,
                                        manager: com.culustech.klicka.click.ClickManager,
                                        svc: com.culustech.klicka.service.ClickAccessibilityService?) {
        val id = command.substringAfter("click ").trim().toIntOrNull()

        // Log the command
        FileLogger.i("VoiceService", "Click command for ID $id received")

        if (svc == null) {
            FileLogger.e("VoiceService", "Accessibility service not available for clicking")
            android.widget.Toast.makeText(this, "Enable Accessibility to click", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (!svc.hasClickPoints()) {
            FileLogger.i("VoiceService", "No markers exist, showing overlay for marker creation")
            android.widget.Toast.makeText(this, "No markers defined. Tap to add markers.", android.widget.Toast.LENGTH_LONG).show()

            // Automatically show the overlay in add mode
            svc.showOverlay()
            svc.showMarkers()
            svc.setAddMode(true)
            return
        }

        // We have markers, check if the requested ID exists
        val points = store.loadPoints()
        FileLogger.i("VoiceService", "Found ${points.size} markers with IDs: ${points.map { it.id }}")

        val point = points.firstOrNull { it.id == id }
        if (point != null) {
            // Hide markers before clicking to avoid interference
            svc.hideMarkers()

            FileLogger.i("VoiceService", "Clicking marker $id at (${point.x}, ${point.y})")
            manager.clickPoint(point)
            android.widget.Toast.makeText(this, "Clicking marker $id", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            FileLogger.i("VoiceService", "Marker $id not found. Available IDs: ${points.map { it.id }}")
            android.widget.Toast.makeText(this, "Marker $id not found. Available: ${points.map { it.id }}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleClickAllCommand(store: com.culustech.klicka.data.ClickPointStore,
                                     manager: com.culustech.klicka.click.ClickManager,
                                     svc: com.culustech.klicka.service.ClickAccessibilityService?) {
        // Log the current state for debugging
        FileLogger.i("VoiceService", "Click all command received")

        if (svc == null) {
            FileLogger.e("VoiceService", "Accessibility service not available for clicking")
            android.widget.Toast.makeText(this, "Enable Accessibility to click", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (svc.hasClickPoints()) {
            val points = store.loadPoints()
            FileLogger.i("VoiceService", "Found ${points.size} markers with IDs: ${points.map { it.id }}")

            // Hide markers before clicking to avoid interference
            svc.hideMarkers()

            // Perform the clicks
            FileLogger.i("VoiceService", "Clicking all ${points.size} markers")
            manager.clickOnce(points)
            android.widget.Toast.makeText(this, "Clicking all ${points.size} markers", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            FileLogger.i("VoiceService", "No markers defined yet, showing overlay for marker creation")
            android.widget.Toast.makeText(this, "No markers defined. Tap to add markers.", android.widget.Toast.LENGTH_LONG).show()

            // Automatically show the overlay in add mode
            svc.showOverlay()
            svc.showMarkers()
            svc.setAddMode(true)
        }
    }
}