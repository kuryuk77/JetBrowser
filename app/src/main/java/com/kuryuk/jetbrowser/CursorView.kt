package com.kuryuk.jetbrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Kumanda modunda ekranda gezen fare imleci. Dokunmaları engellemez. */
class CursorView(ctx: Context) : View(ctx) {
    var cx = 0f
    var cy = 0f
    /** Görünüm ölçülmeden önce kullanılacak sınırlar (sistem geneli imleç). */
    var maxW = 0f
    var maxH = 0f
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * d
    }
    private val path = Path()

    init {
        isClickable = false
        isFocusable = false
    }

    fun centerIfUnset() {
        if (cx == 0f && cy == 0f && width > 0) { cx = width / 2f; cy = height / 2f }
    }

    fun moveBy(dx: Float, dy: Float) {
        val w = if (width > 0) width.toFloat() else maxW
        val h = if (height > 0) height.toFloat() else maxH
        cx = (cx + dx).coerceIn(0f, (w - 1).coerceAtLeast(0f))
        cy = (cy + dy).coerceIn(0f, (h - 1).coerceAtLeast(0f))
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = d * 1.4f
        path.reset()
        path.moveTo(cx, cy)
        path.lineTo(cx, cy + 18 * s)
        path.lineTo(cx + 4.5f * s, cy + 13.5f * s)
        path.lineTo(cx + 8 * s, cy + 21 * s)
        path.lineTo(cx + 11 * s, cy + 19.5f * s)
        path.lineTo(cx + 7.5f * s, cy + 12.5f * s)
        path.lineTo(cx + 13 * s, cy + 12.5f * s)
        path.close()
        c.drawPath(path, fill)
        c.drawPath(path, stroke)
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
}
