package com.yoran.screentranslate

/**
 * Translate the old adaptive font size down by exactly two scale-independent
 * pixels (2sp). Physical pixel reduction respects the user's font scaling.
 */
internal object TranslationFontSizing {
    fun sizePx(boxHeightPx: Int, scaledDensity: Float): Float {
        val oldSize = (boxHeightPx * 0.85f)
            .coerceIn(13f * scaledDensity, 22f * scaledDensity)
        return oldSize - (2f * scaledDensity)
    }
}
