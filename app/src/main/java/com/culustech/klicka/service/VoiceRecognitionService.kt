package com.culustech.klicka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.culustech.klicka.R
import android.os.Handler
import android.os.Looper

/**
 * Foreground service with continuous listening using SpeechRecognizer.
 * MVP commands:
 * - "clicker activate" -> acknowledge listening
 * - "click" -> trigger click at all points (placeholder callback)
 * - "click [number]" -> trigger click at specific point (placeholder)
 * - "deactivate" -> stop listening
 */
class VoiceRecognitionService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
    }

    private fun startAsForeground() {
        val channelId = "klicka_voice"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Klicka Voice", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Klicka voice active")
            .setContentText("Listening for commands: clicker activate, click, click [n], deactivate")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1002, notification)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
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

        val store = com.culustech.klicka.data.ClickPointStore(this)
        val manager = com.culustech.klicka.click.ClickManager(this)
        val svc = com.culustech.klicka.service.ClickAccessibilityService.ClickManagerHolder.instance

        when {
            normalized == "clicker activate" || normalized == "clicker" -> {
                // Feedback and ensure overlay is available
                android.widget.Toast.makeText(this, "Voice activated", android.widget.Toast.LENGTH_SHORT).show()
                // Show accessibility overlay via the service (if enabled)
                svc?.showOverlay() ?: run {
                    android.widget.Toast.makeText(this, "Enable Klicka Accessibility Service to show overlay", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            normalized == "deactivate" -> {
                stopSelf()
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
                val id = normalized.substringAfter("click ").trim().toIntOrNull()
                val point = store.loadPoints().firstOrNull { it.id == id }
                if (point != null) {
                    if (svc == null) android.widget.Toast.makeText(this, "Enable Accessibility to click", android.widget.Toast.LENGTH_LONG).show()
                    manager.clickPoint(point)
                    android.widget.Toast.makeText(this, "Clicking $id", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "Marker $id not found", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            normalized == "click" -> {
                val points = store.loadPoints()
                if (points.isNotEmpty()) {
                    if (svc == null) android.widget.Toast.makeText(this, "Enable Accessibility to click", android.widget.Toast.LENGTH_LONG).show()
                    manager.clickOnce(points)
                    android.widget.Toast.makeText(this, "Clicking all", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "No markers to click", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}