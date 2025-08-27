package com.culustech.klicka.click

import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.culustech.klicka.data.ClickPoint
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
        points.forEachIndexed { index, p ->
            mainHandler.postDelayed({ svc.performClick(p.x, p.y, durationMs = 60) }, index * 120L)
        }
    }

    fun doubleClick(points: List<ClickPoint>) {
        val svc = service() ?: return
        points.forEachIndexed { index, p ->
            val base = index * 220L
            mainHandler.postDelayed({ svc.performClick(p.x, p.y, durationMs = 50) }, base)
            mainHandler.postDelayed({ svc.performClick(p.x, p.y, durationMs = 50) }, base + 110L)
        }
    }

    fun clickPoint(point: ClickPoint) {
        val svc = service() ?: return
        svc.performClick(point.x, point.y, durationMs = 60)
    }

    fun doubleClickPoint(point: ClickPoint) {
        val svc = service() ?: return
        svc.performClick(point.x, point.y, durationMs = 50)
        mainHandler.postDelayed({ svc.performClick(point.x, point.y, durationMs = 50) }, 110L)
    }

    companion object {
        // Kept empty; service instance is available via ClickAccessibilityService.Companion.instance
    }
}