package com.yoran.screentranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A true accessibility overlay: no SYSTEM_ALERT_WINDOW permission and no screen sharing.
 * Translation layer never receives touches; one overlay View avoids multiple window occlusion.
 */
internal class AccessibilityOverlay(
    private val context: Context,
    private val manager: WindowManager
) {
    private val translationView = TranslationSurface(context)
    private var attached = false

    fun attach() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        translationView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        manager.addView(translationView, params)
        attached = true
    }

    fun show(regions: List<TranslatedRegion>) {
        if (attached) translationView.update(regions)
    }

    fun clear() { translationView.update(emptyList()) }

    fun detach() {
        clear()
        if (attached) {
            try { manager.removeView(translationView) } catch (_: Exception) {}
            attached = false
        }
    }

    private class TranslationSurface(context: Context) : View(context) {
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDC141924.toInt() }
        private val foreground = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT
        }
        private var data: List<TranslatedRegion> = emptyList()
        private val density = resources.displayMetrics.density
        private val scaled = resources.displayMetrics.scaledDensity
        private fun dp(v: Float) = v * density
        fun update(regions: List<TranslatedRegion>) {
            data = regions.take(14)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val occupied = ArrayList<RectF>()
            data.sortedWith(compareBy({ it.box.top }, { it.box.left })).forEach { region ->
                val box = region.box
                if (box.right <= 0 || box.bottom <= 0 || box.left >= width || box.top >= height) return@forEach
                val text = region.text.trim()
                if (text.isBlank()) return@forEach
                val font = (box.height * .75f).coerceIn(12f * scaled, 20f * scaled)
                foreground.textSize = font
                val pad = dp(5f)
                val x = box.left.toFloat().coerceIn(0f, max(0f, width - dp(54f)))
                val y = box.top.toFloat().coerceIn(0f, max(0f, height - dp(26f)))
                val available = max(dp(40f), width - x - pad * 2)
                val required = foreground.measureText(text)
                val desired = max(box.width.toFloat(), min(required, box.width * 2.5f))
                val layoutWidth = max(dp(58f), min(desired + pad * 2, available)).roundToInt()
                if (layoutWidth <= 0) return@forEach
                val contentWidth = max(1, (layoutWidth - pad * 2).roundToInt())
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, foreground, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(contentWidth)
                    .build()
                val h = layout.height + pad * 2
                val block = RectF(x, y, min(width.toFloat(), x + layoutWidth),
                    min(height.toFloat(), y + h))
                if (occupied.any { RectF.intersects(it, block) }) return@forEach
                occupied.add(block)
                canvas.drawRoundRect(block, dp(5f), dp(5f), background)
                canvas.save()
                canvas.translate(x + pad, y + pad)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
