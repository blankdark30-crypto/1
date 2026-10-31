package com.yoran.screentranslate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Up to 14 small, non-focusable, non-touchable text windows. Never capture while visible.
 * Window alpha < 0.8 mitigates Android 12+ untrusted-overlay touch protection.
 */
internal class RegionOverlayRenderer(
    private val context: Context,
    private val manager: WindowManager
) {
    private data class Item(val view: TextView, val params: WindowManager.LayoutParams)
    private val active = mutableListOf<Item>()
    private var hidden = false
    fun render(regions: List<TranslatedRegion>, captureWidth: Int, captureHeight: Int): Boolean {
        val screen = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = manager.maximumWindowMetrics.bounds
            screen.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(screen)
        }
        if (RegionCoordinates.map(PixelBox(0, 0, captureWidth, captureHeight),
                captureWidth, captureHeight, screen.x, screen.y) == null) {
            clear()
            return false
        }
        val occupied = mutableListOf<PixelBox>()
        var shown = 0
        for (region in regions.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            if (shown >= MAX_REGIONS) break
            val rect = RegionCoordinates.map(region.box, captureWidth, captureHeight, screen.x, screen.y) ?: continue
            if (region.text.isBlank()) continue
            val x = rect.left.coerceAtMost((screen.x - dp(50)).coerceAtLeast(0))
            val y = rect.top.coerceAtMost((screen.y - dp(24)).coerceAtLeast(0))
            val fontPx = (rect.height * .78f).coerceIn(
                12 * context.resources.displayMetrics.scaledDensity,
                20 * context.resources.displayMetrics.scaledDensity)
            val textWidth = (region.text.length * fontPx).roundToInt()
            val width = maxOf(rect.width, minOf(textWidth, rect.width * 2), dp(58))
                .coerceAtMost((screen.x - x - dp(4)).coerceAtLeast(dp(32)))
            val estimatedLines = if (textWidth > width - dp(8)) 2 else 1
            val estimatedHeight = (fontPx * estimatedLines * 1.4f + dp(8)).roundToInt()
            val footprint = PixelBox(x, y, x + width, minOf(screen.y, y + estimatedHeight))
            if (occupied.any { a -> a.left < footprint.right && footprint.left < a.right &&
                    a.top < footprint.bottom && footprint.top < a.bottom }) continue
            val item = if (shown < active.size) active[shown] else create().also { active.add(it) }
            item.view.text = region.text
            item.view.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
            item.params.x = x
            item.params.y = y
            item.params.width = width
            item.view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            try { manager.updateViewLayout(item.view, item.params); occupied.add(footprint); shown++ }
            catch (_: Exception) { }
        }
        while (active.size > shown) {
            val item = active.removeAt(active.lastIndex)
            try { manager.removeView(item.view) } catch (_: Exception) { }
        }
        return true
    }

    fun hideForCapture(value: Boolean) {
        hidden = value
        for (item in active) item.view.visibility = if (value) View.INVISIBLE else View.VISIBLE
    }

    fun clear() {
        while (active.isNotEmpty()) {
            val item = active.removeAt(active.lastIndex)
            try { manager.removeView(item.view) } catch (_: Exception) { }
        }
    }

    private fun create(): Item {
        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(3), dp(5), dp(3))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xB8111725.toInt())
                cornerRadius = dp(4).toFloat()
            }
            contentDescription = "原位文字翻譯"
        }
        val params = WindowManager.LayoutParams(
            dp(90), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.72f
        }
        manager.addView(view, params)
        return Item(view, params)
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    companion object { private const val MAX_REGIONS = 14 }
}
