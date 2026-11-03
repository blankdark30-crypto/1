package com.yoran.screentranslate

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Raw ML Kit text line with a stable block identity. */
internal data class OcrFragment(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val blockId: Int
)

/** Whole sentence/paragraph to translate in one request; bounds cover every source line. */
internal data class OcrParagraph(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val lineCount: Int,
    val blockId: Int
)

/**
 * ML Kit returns individual Lines even when a game wraps a single sentence over 2–5 rows.
 * Group tightly stacked lines that are in the same ML Kit TextBlock first, and merge
 * across blocks only for exceptionally tight, aligned continuation lines.
 *
 * Different columns/menu entries should remain independent translation regions.
 */
internal object OcrParagraphAssembler {
    private const val MAX_LINES = 5
    private const val MAX_CHARS = 320

    fun assemble(fragments: List<OcrFragment>, language: String): List<OcrParagraph> {
        if (fragments.isEmpty()) return emptyList()
        // Do not discard single-character lines before joining; e.g. "I" / "am here".
        val valid = fragments
            .filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
            .filter { it.text.length <= MAX_CHARS }
        val perBlock = valid.groupBy { it.blockId }.values.flatMap { entries ->
            val sorted = entries.sortedWith(compareBy({ it.top }, { it.left }))
            val paragraph = mutableListOf<OcrParagraph>()
            for (line in sorted) {
                val last = paragraph.lastOrNull()
                if (last != null && shouldMerge(last, line, sameBlock = true)) {
                    paragraph[paragraph.lastIndex] = merge(last, line, language)
                } else {
                    paragraph.add(line.toParagraph())
                }
            }
            paragraph
        }
        val sortedParagraphs = perBlock.sortedWith(compareBy({ it.top }, { it.left }))
        if (sortedParagraphs.isEmpty()) return emptyList()
        val merged = mutableListOf<OcrParagraph>()
        for (next in sortedParagraphs) {
            val last = merged.lastOrNull()
            if (last != null && canMergeAcrossBlocks(last, next) &&
                shouldMerge(last, next.asFragment(), sameBlock = false)) {
                merged[merged.lastIndex] = merge(last, next.asFragment(), language)
                    .copy(lineCount = last.lineCount + next.lineCount)
            } else {
                merged.add(next)
            }
        }
        return merged
    }

    private fun OcrFragment.toParagraph() =
        OcrParagraph(text, left, top, right, bottom, 1, blockId)

    private fun OcrParagraph.asFragment() =
        OcrFragment(text, left, top, right, bottom, blockId)

    private fun shouldMerge(previous: OcrParagraph, next: OcrFragment, sameBlock: Boolean): Boolean {
        if (previous.lineCount >= MAX_LINES || previous.text.length + next.text.length > MAX_CHARS) return false
        if (endsSentence(previous.text)) return false
        val previousHeight = (previous.bottom - previous.top).toFloat() / previous.lineCount
        val nextHeight = (next.bottom - next.top).toFloat()
        if (previousHeight < 6f || nextHeight < 6f ||
            nextHeight / previousHeight !in .6f..1.65f) return false
        // Handle minor ML Kit box overlap but don't group items sitting on the same row.
        val gap = next.top - previous.bottom
        val maxGap = if (sameBlock) max(previousHeight, nextHeight) * 1.1f
            else max(previousHeight, nextHeight) * .55f
        if (gap < -nextHeight * .3f || gap > maxGap) return false
        val overlap = min(previous.right, next.right) - max(previous.left, next.left)
        if (overlap <= 0) return false
        val narrowWidth = min(previous.right - previous.left, next.right - next.left).coerceAtLeast(1)
        val alignmentTolerance = if (sameBlock) max(16f, previousHeight * 1.15f)
            else max(8f, previousHeight * .65f)
        return abs(previous.left - next.left) <= alignmentTolerance ||
            overlap >= narrowWidth * if (sameBlock) .76f else .90f
    }

    private fun canMergeAcrossBlocks(previous: OcrParagraph, next: OcrParagraph): Boolean {
        // Only single-line ML Kit blocks can accidentally be fragments of the same paragraph.
        // Never fuse an already assembled paragraph with separate menu/control groups.
        if (previous.blockId == next.blockId || next.lineCount != 1 || previous.lineCount >= MAX_LINES) return false
        // Strong continuation evidence: same left edge and nearly adjacent lines.
        val height = (previous.bottom - previous.top).toFloat() / previous.lineCount
        if (abs(previous.left - next.left) > max(8f, height * .65f)) return false
        // A very short heading should not absorb a following menu entry.
        if (previous.text.trim().length < 5) return false
        return true
    }

    private fun endsSentence(text: String): Boolean {
        val trimmed = text.trimEnd().trimEnd('"', '\'', '”', '’', ')', ']', '」')
        return trimmed.lastOrNull() in listOf('.', '?', '!', '。', '？', '！', '…', ':', '：', '；', ';')
    }

    private fun merge(previous: OcrParagraph, next: OcrFragment, language: String): OcrParagraph {
        val leftText = previous.text.trimEnd()
        val rightText = next.text.trimStart()
        val joined = when {
            leftText.endsWith("-") && language == "en" -> leftText.dropLast(1) + rightText
            language == "ja" -> leftText + rightText
            else -> leftText + " " + rightText
        }
        return previous.copy(
            text = joined, left = min(previous.left, next.left),
            top = min(previous.top, next.top),
            right = max(previous.right, next.right),
            bottom = max(previous.bottom, next.bottom),
            lineCount = previous.lineCount + 1
        )
    }
}
