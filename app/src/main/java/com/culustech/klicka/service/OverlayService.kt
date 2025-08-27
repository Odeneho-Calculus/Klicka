package com.culustech.klicka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.culustech.klicka.R
import com.culustech.klicka.data.ClickPoint
import com.culustech.klicka.data.ClickPointStore
import com.culustech.klicka.ui.MarkerView
import com.culustech.klicka.data.OverlaySettings

/**
 * Foreground service that draws an overlay button and numbered markers placeholder.
 * MVP: touch to place points (auto-numbered), long-press to remove.
 */
@Deprecated("Overlay is now provided by AccessibilityService. This service remains for legacy/manual start.")
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayContainer: FrameLayout? = null
    private var points = mutableListOf<ClickPoint>()
    private lateinit var store: ClickPointStore

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        store = ClickPointStore(this)
        points = store.loadPoints()
        showOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        overlayContainer?.let { windowManager.removeView(it) }
    }

    private fun startAsForeground() {
        val channelId = "klicka_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Klicka Overlay", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Klicka running")
            .setContentText("Tap to place markers. Long-press to remove.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(1001, notification)
    }

    private fun showOverlay() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_bubble, null)
        overlayView = view

        // Choose overlay type per SDK (pre-O uses deprecated TYPE_PHONE - suppressed locally)
        val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 32
        params.y = 128

        val counterText = view.findViewById<TextView>(R.id.points_counter)
        val bubble = view.findViewById<ImageView>(R.id.bubble_button)
        val modeText = view.findViewById<TextView>(R.id.mode_text)
        val toggleMode = view.findViewById<ImageView>(R.id.btn_toggle_mode)
        val removeLast = view.findViewById<ImageView>(R.id.btn_remove_last)

        // Container to host markers
        overlayContainer = FrameLayout(this)
        val fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        fullParams.gravity = Gravity.TOP or Gravity.START

        var addMode = true
        val updateModeUi = {
            modeText.text = if (addMode) "ADD" else "REM"
            toggleMode.setImageResource(if (addMode) android.R.drawable.ic_input_add else android.R.drawable.ic_delete)
        }
        updateModeUi()

        // Tap bubble toggles Add/Remove mode for simplicity
        bubble.setOnClickListener {
            addMode = !addMode
            updateModeUi()
        }

        overlayContainer?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (addMode) {
                    if (points.size >= 10) {
                        Toast.makeText(this, "Max 10 markers reached", Toast.LENGTH_SHORT).show()
                    } else {
                        addMarker(event.rawX, event.rawY)
                    }
                } else {
                    // remove closest marker to touch
                    removeClosestMarker(event.rawX, event.rawY)
                }
                counterText.text = points.size.toString()
            }
            true
        }

        toggleMode.setOnClickListener {
            addMode = !addMode
            updateModeUi()
        }
        removeLast.setOnClickListener {
            removeLastMarker()
            counterText.text = points.size.toString()
        }

        // Make bubble draggable with edge snap + tap to toggle mode + long-press to close
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0
            var initY = 0
            var initTouchX = 0f
            var initTouchY = 0f
            var downTime = 0L
            var moved = false
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val density = resources.displayMetrics.density
                val clickSlop = (8 * density)
                val longPressMs = 600
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = params.x
                        initY = params.y
                        initTouchX = event.rawX
                        initTouchY = event.rawY
                        downTime = System.currentTimeMillis()
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initTouchX
                        val dy = event.rawY - initTouchY
                        if (!moved && (kotlin.math.abs(dx) > clickSlop || kotlin.math.abs(dy) > clickSlop)) moved = true
                        params.x = initX + dx.toInt()
                        params.y = initY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val elapsed = System.currentTimeMillis() - downTime
                        val dx = event.rawX - initTouchX
                        val dy = event.rawY - initTouchY
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (elapsed >= longPressMs && dist < clickSlop) {
                            // Close overlay entirely
                            try { overlayContainer?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                            try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
                            stopSelf()
                            return true
                        }
                        if (!moved && dist < clickSlop) {
                            // Treat as tap: toggle add/remove mode
                            addMode = !addMode
                            updateModeUi()
                            return true
                        }
                        // Snap to nearest horizontal edge after drag
                        val screenWidth = resources.displayMetrics.widthPixels
                        params.x = if (params.x + view.width / 2 < screenWidth / 2) 0 else screenWidth - view.width
                        windowManager.updateViewLayout(view, params)
                        return true
                    }
                }
                return false
            }
        })

        // Long-press handling is integrated in the touch listener above

        windowManager.addView(overlayContainer, fullParams)
        windowManager.addView(view, params)

        // Render existing points
        points.forEach { renderMarker(it) }
        counterText.text = points.size.toString()
    }

    private fun addMarker(rawX: Float, rawY: Float) {
        val id = points.size + 1
        val p = ClickPoint(id, rawX, rawY)
        points.add(p)
        store.savePoints(points)
        renderMarker(p)
    }

    private fun removeLastMarker() {
        if (points.isEmpty()) return
        points.removeLast()
        renumberPoints()
        store.savePoints(points)
        redrawMarkers()
    }

    private fun removeClosestMarker(x: Float, y: Float) {
        if (points.isEmpty()) return
        val closest = points.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) } ?: return
        points.remove(closest)
        renumberPoints()
        store.savePoints(points)
        redrawMarkers()
    }

    private fun renumberPoints() {
        points = points.mapIndexed { index, cp -> cp.copy(id = index + 1) }.toMutableList()
    }

    private fun redrawMarkers() {
        val container = overlayContainer ?: return
        container.removeAllViews()
        points.forEach { renderMarker(it) }
    }

    private fun renderMarker(cp: ClickPoint) {
        val container = overlayContainer ?: return
        val settings: OverlaySettings = store.loadSettings()
        val marker = MarkerView(this).apply {
            number = cp.id
            circleColor = settings.color
            circleAlpha = settings.opacity
            val density = resources.displayMetrics.density
            val sizePx = (settings.sizeDp * density).toInt()
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                leftMargin = (cp.x - sizePx / 2f).toInt()
                topMargin = (cp.y - sizePx / 2f).toInt()
            }
            setOnLongClickListener {
                // Remove this specific marker
                points.removeAll { it.id == cp.id }
                renumberPoints()
                store.savePoints(points)
                redrawMarkers()
                true
            }
        }
        container.addView(marker)
    }
}