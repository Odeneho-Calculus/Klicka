package com.culustech.klicka.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.culustech.klicka.R
import com.culustech.klicka.data.ClickPoint
import com.culustech.klicka.data.ClickPointStore
import com.culustech.klicka.ui.MarkerView

/**
 * AccessibilityService that:
 * - Performs click gestures
 * - Hosts the in-app overlay using TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW needed)
 * - Hooks the system Accessibility Button to toggle the overlay
 */
class ClickAccessibilityService : AccessibilityService() {

    // Overlay
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayContainer: FrameLayout? = null
    private var addMode = true

    // Data
    private lateinit var store: ClickPointStore
    private var points = mutableListOf<ClickPoint>()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ClickManagerHolder.instance = this

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        store = ClickPointStore(this)
        points = store.loadPoints()
        // Ensure IDs are normalized to start at 1 and be sequential
        normalizeIds()

        // Register accessibility button if available (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                accessibilityButtonController.registerAccessibilityButtonCallback(
                    object : AccessibilityButtonController.AccessibilityButtonCallback() {
                        override fun onClicked(controller: AccessibilityButtonController) {
                            toggleOverlay()
                        }
                    },
                    mainHandler
                )
            } catch (_: Throwable) { /* ignore */ }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        super.onDestroy()
        try { hideOverlay() } catch (_: Exception) {}
        ClickManagerHolder.instance = null
    }

    // Public API for other components (e.g., Voice) to show overlay
    fun showOverlay() {
        if (overlayView != null) return // already visible

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_bubble, null)
        overlayView = view

        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        bubbleParams.gravity = Gravity.TOP or Gravity.START
        bubbleParams.x = 32
        bubbleParams.y = 128

        val counterText = view.findViewById<TextView>(R.id.points_counter)
        val bubble = view.findViewById<ImageView>(R.id.bubble_button)
        val modeText = view.findViewById<TextView>(R.id.mode_text)
        val toggleMode = view.findViewById<ImageView>(R.id.btn_toggle_mode)
        val removeLast = view.findViewById<ImageView>(R.id.btn_remove_last)
        val clearAll = view.findViewById<ImageView>(R.id.btn_clear_all)

        // Fullscreen container to host markers
        overlayContainer = FrameLayout(this)
        val fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        fullParams.gravity = Gravity.TOP or Gravity.START

        val updateModeUi = {
            modeText.text = if (addMode) "ADD" else "REM"
            toggleMode.setImageResource(if (addMode) android.R.drawable.ic_input_add else android.R.drawable.ic_delete)
        }
        updateModeUi()

        // Tap bubble toggles add/remove
        bubble.setOnClickListener {
            addMode = !addMode
            updateModeUi()
        }

        // Drag bubble with edge snap + long-press to close
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
                        initX = bubbleParams.x
                        initY = bubbleParams.y
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
                        bubbleParams.x = initX + dx.toInt()
                        bubbleParams.y = initY + dy.toInt()
                        windowManager.updateViewLayout(view, bubbleParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val elapsed = System.currentTimeMillis() - downTime
                        val dx = event.rawX - initTouchX
                        val dy = event.rawY - initTouchY
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (elapsed >= longPressMs && dist < clickSlop) {
                            hideOverlay()
                            return true
                        }
                        if (!moved && dist < clickSlop) {
                            addMode = !addMode
                            updateModeUi()
                            return true
                        }
                        val screenWidth = resources.displayMetrics.widthPixels
                        bubbleParams.x = if (bubbleParams.x + view.width / 2 < screenWidth / 2) 0 else screenWidth - view.width
                        windowManager.updateViewLayout(view, bubbleParams)
                        return true
                    }
                }
                return false
            }
        })

        overlayContainer?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (addMode) {
                    if (points.size >= 10) {
                        Toast.makeText(this, "Max 10 markers reached", Toast.LENGTH_SHORT).show()
                    } else {
                        addMarker(event.rawX, event.rawY)
                    }
                } else {
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
        clearAll.setOnClickListener {
            points.clear()
            store.clearPoints()
            redrawMarkers()
            counterText.text = "0"
            Toast.makeText(this, "Cleared all markers", Toast.LENGTH_SHORT).show()
        }

        windowManager.addView(overlayContainer, fullParams)
        windowManager.addView(view, bubbleParams)

        // Render existing points
        points.forEach { renderMarker(it) }
        counterText.text = points.size.toString()
    }

    fun hideOverlay() {
        try { overlayContainer?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayContainer = null
        overlayView = null
    }

    private fun toggleOverlay() {
        if (overlayView == null) showOverlay() else hideOverlay()
    }

    // ----- Marker management -----
    private fun addMarker(rawX: Float, rawY: Float) {
        // Ensure ids are sequential before assigning the next one
        renumberPoints()
        val nextId = points.size + 1
        val p = ClickPoint(nextId, rawX, rawY)
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

    private fun normalizeIds() {
        // If stored ids are off (e.g., empty list but next id would be 2), fix them
        if (points.isEmpty()) return
        var needFix = false
        points.forEachIndexed { index, cp -> if (cp.id != index + 1) needFix = true }
        if (needFix) {
            points = points.mapIndexed { index, cp -> cp.copy(id = index + 1) }.toMutableList()
            store.savePoints(points)
        }
    }

    private fun redrawMarkers() {
        val container = overlayContainer ?: return
        container.removeAllViews()
        points.forEach { renderMarker(it) }
    }

    private fun renderMarker(cp: ClickPoint) {
        val container = overlayContainer ?: return
        val settings = store.loadSettings()
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
        }

        // Make marker draggable and support long-press to delete
        marker.setOnTouchListener(object : View.OnTouchListener {
            var initLeft = 0
            var initTop = 0
            var downRawX = 0f
            var downRawY = 0f
            var moved = false
            var downTime = 0L
            val density = resources.displayMetrics.density
            val touchSlop = 8f * density
            val longPressMs = 600

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                val lp = marker.layoutParams as FrameLayout.LayoutParams
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initLeft = lp.leftMargin
                        initTop = lp.topMargin
                        downRawX = event.rawX
                        downRawY = event.rawY
                        moved = false
                        downTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!moved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) moved = true
                        lp.leftMargin = initLeft + dx.toInt()
                        lp.topMargin = initTop + dy.toInt()
                        marker.layoutParams = lp
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val elapsed = System.currentTimeMillis() - downTime
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        if (!moved && dist < touchSlop && elapsed >= longPressMs) {
                            // long-press delete
                            points.removeAll { it.id == cp.id }
                            renumberPoints()
                            store.savePoints(points)
                            redrawMarkers()
                            return true
                        }
                        if (moved) {
                            // Persist new center position
                            val sizePx = marker.layoutParams.width
                            val newCenterX = (lp.leftMargin + sizePx / 2f)
                            val newCenterY = (lp.topMargin + sizePx / 2f)
                            points = points.map { p -> if (p.id == cp.id) p.copy(x = newCenterX, y = newCenterY) else p }.toMutableList()
                            store.savePoints(points)
                        }
                        return true
                    }
                }
                return false
            }
        })

        container.addView(marker)
    }

    // ----- Click execution -----
    fun performClick(x: Float, y: Float, durationMs: Long = 50L) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    companion object ClickManagerHolder {
        @Volatile var instance: ClickAccessibilityService? = null
    }
}
