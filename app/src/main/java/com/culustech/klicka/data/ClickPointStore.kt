package com.culustech.klicka.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences storage for click points and settings.
 */
class ClickPointStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("klicka_prefs", Context.MODE_PRIVATE)

    fun loadPoints(): MutableList<ClickPoint> {
        val json = prefs.getString(KEY_POINTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val out = mutableListOf<ClickPoint>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(ClickPoint(o.getInt("id"), o.getDouble("x").toFloat(), o.getDouble("y").toFloat()))
        }
        return out
    }

    fun savePoints(points: List<ClickPoint>) {
        val arr = JSONArray()
        points.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("x", p.x)
                put("y", p.y)
            })
        }
        prefs.edit().putString(KEY_POINTS, arr.toString()).apply()
    }

    fun clearPoints() { savePoints(emptyList()) }

    fun loadSettings(): OverlaySettings {
        val size = prefs.getInt(KEY_MARKER_SIZE_DP, 30)
        val opacity = prefs.getFloat(KEY_MARKER_OPACITY, 0.7f)
        val color = prefs.getInt(KEY_MARKER_COLOR, 0xFFFF0000.toInt())
        return OverlaySettings(sizeDp = size, opacity = opacity, color = color)
    }

    fun saveSettings(settings: OverlaySettings) {
        prefs.edit()
            .putInt(KEY_MARKER_SIZE_DP, settings.sizeDp)
            .putFloat(KEY_MARKER_OPACITY, settings.opacity)
            .putInt(KEY_MARKER_COLOR, settings.color)
            .apply()
    }

    companion object {
        private const val KEY_POINTS = "click_points"
        private const val KEY_MARKER_SIZE_DP = "marker_size_dp"
        private const val KEY_MARKER_OPACITY = "marker_opacity"
        private const val KEY_MARKER_COLOR = "marker_color"
    }
}

data class OverlaySettings(
    val sizeDp: Int,
    val opacity: Float,
    val color: Int
)