package com.yoran.screentranslate

/** Deliberately refuse password and editable fields. The accessibility tree is not a screenshot. */
internal object AccessibleTextFilter {
    fun normalize(value: String?, isPassword: Boolean, isEditable: Boolean, box: PixelBox): String? {
        if (isPassword || isEditable || value == null) return null
        val clean = value.replace(Regex("\\s+"), " ").trim()
        if (clean.length !in 2..180 || clean.count { it.isLetter() } < 2) return null
        if (box.width < 12 || box.height < 10 || box.height > 360) return null
        return clean
    }
}
