package com.culustech.klicka.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
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
import com.culustech.klicka.log.FileLogger
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
    private var controlsView: View? = null
    private var addMode = true
    private var markersVisible = true
    private var controlsVisible = true

    // Function to update UI based on add/remove mode
    private fun updateModeUi() {
        val modeText = controlsView?.findViewById<TextView>(R.id.mode_text) ?: return
        val toggleMode = controlsView?.findViewById<ImageView>(R.id.btn_toggle_mode) ?: return

        modeText.text = if (addMode) "ADD" else "REM"
        toggleMode.setImageResource(if (addMode) android.R.drawable.ic_input_add else android.R.drawable.ic_delete)

        FileLogger.d("ClickService", "Updated mode UI to ${if (addMode) "ADD" else "REMOVE"} mode")
    }

    // Data
    private lateinit var store: ClickPointStore
    private var points = mutableListOf<ClickPoint>()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        ClickManagerHolder.instance = this

        // Initialize the file logger
        FileLogger.getInstance().init()
        FileLogger.i("ClickService", "Accessibility service connected")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        store = ClickPointStore(this)
        points = store.loadPoints()
        // Ensure IDs are normalized to start at 1 and be sequential
        normalizeIds()

        FileLogger.i("ClickService", "Loaded ${points.size} click points")

        // Register accessibility button if available (Android O+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                accessibilityButtonController.registerAccessibilityButtonCallback(
                    object : AccessibilityButtonController.AccessibilityButtonCallback() {
                        override fun onClicked(controller: AccessibilityButtonController) {
                            FileLogger.d("ClickService", "Accessibility button clicked")
                            toggleOverlay()
                        }
                    },
                    mainHandler
                )
                FileLogger.i("ClickService", "Registered accessibility button callback")
            } catch (e: Throwable) {
                FileLogger.e("ClickService", "Failed to register accessibility button", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not used */ }
    override fun onInterrupt() { /* not used */ }

    override fun onDestroy() {
        super.onDestroy()
        try { hideOverlay() } catch (_: Exception) {}
        controlsView = null
        ClickManagerHolder.instance = null
    }

    // Public API for other components (e.g., Voice) to show overlay
    fun showOverlay() {
        if (overlayView != null) {
            // If already visible, just make sure controls are visible
            if (!controlsVisible) {
                toggleControlsVisibility(true)
            }
            // If markers should be visible but aren't, show them
            if (markersVisible && overlayContainer?.visibility == View.INVISIBLE) {
                overlayContainer?.visibility = View.VISIBLE
            }
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.overlay_bubble, null)
        overlayView = view
        controlsView = view  // Store the controls view for later use

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
        val doneButton = view.findViewById<ImageView>(R.id.btn_done)
        val homeButton = view.findViewById<ImageView>(R.id.btn_home)

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

        // Update the mode UI using our class method
        updateModeUi()

        // Tap bubble toggles controls visibility when in minimized mode
        bubble.setOnClickListener {
            if (controlsVisible) {
                addMode = !addMode
                updateModeUi()
            } else {
                toggleControlsVisibility(true)
            }
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
                            if (controlsVisible) {
                                addMode = !addMode
                                updateModeUi()
                            } else {
                                toggleControlsVisibility(true)
                            }
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

        // Done button - toggle markers visibility
        doneButton.setOnClickListener {
            if (markersVisible) {
                hideMarkers()
                doneButton.setImageResource(android.R.drawable.ic_menu_view)
                Toast.makeText(this, "Markers hidden, ready for clicks", Toast.LENGTH_SHORT).show()
            } else {
                showMarkers()
                doneButton.setImageResource(android.R.drawable.ic_menu_save)
                Toast.makeText(this, "Markers visible", Toast.LENGTH_SHORT).show()
            }
        }

        // Home button - minimize the overlay (hide controls but keep bubble)
        homeButton.setOnClickListener {
            toggleControlsVisibility(false)
            Toast.makeText(this, "Controls minimized", Toast.LENGTH_SHORT).show()
        }

        windowManager.addView(overlayContainer, fullParams)
        windowManager.addView(view, bubbleParams)

        // Render existing points
        points.forEach { renderMarker(it) }
        counterText.text = points.size.toString()

        // Set initial visibility states
        markersVisible = true
        controlsVisible = true
    }

    /**
     * Hides the markers but keeps the controls visible
     * This allows clicks to be performed without the markers interfering
     */
    fun hideMarkers() {
        markersVisible = false
        overlayContainer?.visibility = View.INVISIBLE
        // Don't hide controls - keep them visible
    }

    /**
     * Shows the markers if they were hidden
     */
    fun showMarkers() {
        markersVisible = true
        overlayContainer?.visibility = View.VISIBLE
    }

    /**
     * Shows or hides the control buttons, leaving only the main bubble visible
     */
    private fun toggleControlsVisibility(visible: Boolean) {
        controlsVisible = visible
        overlayView?.let { view ->
            val controls = listOf(
                R.id.points_counter,
                R.id.mode_text,
                R.id.btn_toggle_mode,
                R.id.btn_remove_last,
                R.id.btn_clear_all,
                R.id.btn_done,
                R.id.btn_home
            )

            controls.forEach { id ->
                view.findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE
            }

            // Update layout after changing visibility
            windowManager.updateViewLayout(view, view.layoutParams)
        }
    }

    fun hideOverlay() {
        try { overlayContainer?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayContainer = null
        overlayView = null
        controlsView = null
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
        // Create a proper tap gesture with down and up events
        val path = Path()
        path.moveTo(x, y)

        FileLogger.i("ClickService", "Performing click at ($x, $y) with duration ${durationMs}ms")

        // Create a gesture with a clear start time and duration
        val builder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        builder.addStroke(stroke)

        // Dispatch with a callback to log success/failure
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                FileLogger.d("ClickService", "Click gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                FileLogger.e("ClickService", "Click gesture cancelled at ($x, $y)")
            }
        }, null)
    }

    /**
     * Check if the overlay is currently visible
     */
    fun isOverlayVisible(): Boolean {
        return overlayView != null
    }

    /**
     * Check if there are any click points defined
     */
    fun hasClickPoints(): Boolean {
        return points.isNotEmpty()
    }

    /**
     * Get the number of click points defined
     */
    fun getClickPointCount(): Int {
        return points.size
    }

    /**
     * Set the overlay to add or remove mode
     */
    fun setAddMode(enabled: Boolean) {
        if (addMode != enabled) {
            addMode = enabled
            updateModeUi()
            FileLogger.i("ClickService", "Set add mode to $enabled")
        }
    }

    companion object ClickManagerHolder {
        @Volatile var instance: ClickAccessibilityService? = null
    }
}
