package com.yoran.screentranslate

import kotlin.math.abs
import kotlin.math.roundToInt

/** Boxes use physical screen pixels rather than dp. */
internal data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
internal data class TranslatedRegion(val source: String, val text: String, val box: PixelBox)

internal object RegionCoordinates {
    /**
     * Intentional safety check: app-window captures may be cropped and their absolute origin
     * isn't exposed. Never guess their global coordinate mapping.
     */
    fun map(box: PixelBox, captureWidth: Int, captureHeight: Int,
            screenWidth: Int, screenHeight: Int): PixelBox? {
        if (captureWidth <= 0 || captureHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        val sx = screenWidth.toDouble() / captureWidth
        val sy = screenHeight.toDouble() / captureHeight
        if (abs(sx - sy) / maxOf(sx, sy) > 0.025) return null
        val l = (box.left * sx).roundToInt().coerceIn(0, screenWidth)
        val r = (box.right * sx).roundToInt().coerceIn(0, screenWidth)
        val t = (box.top * sy).roundToInt().coerceIn(0, screenHeight)
        val b = (box.bottom * sy).roundToInt().coerceIn(0, screenHeight)
        return if (r - l >= 4 && b - t >= 4) PixelBox(l, t, r, b) else null
    }
}
