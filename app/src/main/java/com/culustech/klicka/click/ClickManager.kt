package com.culustech.klicka.click

import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.culustech.klicka.data.ClickPoint
import com.culustech.klicka.log.FileLogger
import com.culustech.klicka.service.ClickAccessibilityService

/**
 * Bridges to ClickAccessibilityService to perform taps (press + release) at coordinates.
 * Uses a short delay between down and up strokes to mimic natural touch.
 */
class ClickManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun service(): ClickAccessibilityService? {
        // Access the active AccessibilityService instance
        return ClickAccessibilityService.ClickManagerHolder.instance
    }

    fun clickOnce(points: List<ClickPoint>) {
        val svc = service() ?: return
        FileLogger.i("ClickManager", "Clicking ${points.size} points")
        points.forEachIndexed { index, p ->
            mainHandler.postDelayed({
                FileLogger.i("ClickManager", "Clicking point ${p.id} at (${p.x}, ${p.y})")
                svc.performClick(p.x, p.y, durationMs = 100)
            }, index * 300L)
        }
    }

    fun doubleClick(points: List<ClickPoint>) {
        val svc = service() ?: return
        FileLogger.i("ClickManager", "Double-clicking ${points.size} points")
        points.forEachIndexed { index, p ->
            val base = index * 500L
            mainHandler.postDelayed({
                FileLogger.i("ClickManager", "First click on point ${p.id}")
                svc.performClick(p.x, p.y, durationMs = 100)
            }, base)
            mainHandler.postDelayed({
                FileLogger.i("ClickManager", "Second click on point ${p.id}")
                svc.performClick(p.x, p.y, durationMs = 100)
            }, base + 200L)
        }
    }

    fun clickPoint(point: ClickPoint) {
        val svc = service() ?: return
        FileLogger.i("ClickManager", "Clicking single point ${point.id} at (${point.x}, ${point.y})")
        svc.performClick(point.x, point.y, durationMs = 100)
    }

    fun doubleClickPoint(point: ClickPoint) {
        val svc = service() ?: return
        FileLogger.i("ClickManager", "Double-clicking point ${point.id}")
        svc.performClick(point.x, point.y, durationMs = 100)
        mainHandler.postDelayed({
            FileLogger.i("ClickManager", "Second click on point ${point.id}")
            svc.performClick(point.x, point.y, durationMs = 100)
        }, 200L)
    }

    companion object {
        // Kept empty; service instance is available via ClickAccessibilityService.Companion.instance
    }
}