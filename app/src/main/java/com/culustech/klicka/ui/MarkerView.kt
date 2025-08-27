package com.culustech.klicka.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Circular numbered marker with configurable color, opacity, and size.
 */
class MarkerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var number: Int = 0
        set(value) { field = value; invalidate() }
    var circleColor: Int = Color.RED
        set(value) { field = value; invalidate() }
    var circleAlpha: Float = 0.7f
        set(value) { field = value; invalidate() }

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 42f
        isFakeBoldText = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = width.coerceAtMost(height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val alphaInt = (circleAlpha * 255).toInt().coerceIn(0, 255)

        circlePaint.color = circleColor
        circlePaint.alpha = alphaInt
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(number.toString(), cx, textY, textPaint)
    }
}