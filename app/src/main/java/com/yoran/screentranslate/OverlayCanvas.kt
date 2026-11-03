package com.yoran.screentranslate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
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

internal data class VisibleTranslation(
    val original: String,
    val translated: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** One non-touchable accessibility overlay; the separate bubble is the only touch target. */
internal class OverlayCanvas(context: Context, private val wm: WindowManager) {
    private val layer = TextLayer(context)
    private var attached = false
    val width: Int get() = layer.width
    val height: Int get() = layer.height

    fun attach() {
        if (attached) return
        val p = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        wm.addView(layer, p)
        attached = true
    }

    fun update(items: List<VisibleTranslation>) { if (attached) layer.render(items) }
    fun hide() { if (attached && layer.visibility != View.INVISIBLE) layer.visibility = View.INVISIBLE }
    fun show() { if (attached && layer.visibility != View.VISIBLE) layer.visibility = View.VISIBLE }
    fun clear() { if (attached) layer.render(emptyList()) }
    fun detach() {
        if (attached) {
            try { wm.removeView(layer) } catch (_: Exception) {}
            attached = false
        }
    }

    private class TextLayer(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val scaled = resources.displayMetrics.scaledDensity
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE3111520.toInt() }
        private val fg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private var labels: List<VisibleTranslation> = emptyList()
        private fun dp(n: Float) = n * density
        fun render(items: List<VisibleTranslation>) {
            val next = items.take(12)
            // Identical OCR frames should not force another overlay redraw.
            if (!SubtitleChangePolicy.hasChanged(labels, next)) return
            labels = next
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width < 100 || height < 100) return
            val occupied = ArrayList<RectF>()
            for (label in labels) {
                val text = label.translated.trim()
                if (text.isBlank()) continue
                val size = TranslationFontSizing.sizePx(label.bottom - label.top, scaled)
                fg.textSize = size
                val padding = dp(5f)
                val x = label.left.toFloat().coerceIn(0f, max(0f, width - dp(55f)))
                // Wrapped source paragraphs can produce longer translated captions.
                // Allow up to five lines; keep a panel within the screen instead of
                // truncating a multi-line sentence to two lines.
                val originalWidth = (label.right - label.left).toFloat().coerceAtLeast(dp(30f))
                val estimatedWidth = max(
                    originalWidth,
                    min(fg.measureText(text) + padding * 2, max(originalWidth * 2.6f, width * .70f))
                )
                val boxWidth = min(max(dp(68f), estimatedWidth), width - x - dp(3f)).roundToInt()
                if (boxWidth < dp(30f)) continue
                val availableTextWidth = max(1, (boxWidth - padding * 2).roundToInt())
                val layout = StaticLayout.Builder.obtain(text, 0, text.length, fg, availableTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setMaxLines(5)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(availableTextWidth)
                    .build()
                val blockHeight = layout.height + padding * 2
                // A dialogue at the bottom of a game should grow upward if needed.
                val y = label.top.toFloat().coerceIn(0f, max(0f, height - blockHeight - dp(3f)))
                val box = RectF(x, y, x + boxWidth, min(height.toFloat(), y + blockHeight))
                if (box.bottom <= box.top || occupied.any { RectF.intersects(it, box) }) continue
                occupied.add(box)
                canvas.drawRoundRect(box, dp(4f), dp(4f), bg)
                canvas.save()
                canvas.translate(x + padding, y + padding)
                layout.draw(canvas)
                canvas.restore()
            }
        }
    }
}
