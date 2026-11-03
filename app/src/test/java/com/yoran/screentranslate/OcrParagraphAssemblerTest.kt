package com.yoran.screentranslate

import org.junit.Assert.*
import org.junit.Test

class OcrParagraphAssemblerTest {
    private fun f(s: String, y: Int, x: Int = 20, w: Int = 180, b: Int = 1) =
        OcrFragment(s, x, y, x + w, y + 22, b)

    @Test fun joinsEnglishWrappedSentenceAndKeepsBoundingUnion() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("What is your", 50), f("name?", 76)), "en")
        assertEquals(1, result.size)
        assertEquals("What is your name?", result[0].text)
        assertEquals(50, result[0].top)
        assertEquals(98, result[0].bottom)
        assertEquals(2, result[0].lineCount)
    }

    @Test fun joinsSingleLetterEnglishLineInsteadOfDroppingIt() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("I", 50), f("am here.", 76)), "en")
        assertEquals("I am here.", result.single().text)
    }

    @Test fun joinsHyphenatedEnglishWithoutSpace() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("extraor-", 50), f("dinary", 77)), "en")
        assertEquals("extraordinary", result.single().text)
    }

    @Test fun japaneseWrapDoesNotInsertSpaces() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("彼女は", 50), f("元気です。", 75)), "ja")
        assertEquals("彼女は元気です。", result.single().text)
    }

    @Test fun koreanWrapUsesWordSeparator() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("오늘은 날씨가", 50), f("좋습니다", 76)), "ko")
        assertEquals("오늘은 날씨가 좋습니다", result.single().text)
    }

    @Test fun separateSentencesRemainIndependent() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("Start game?", 50), f("Choose hero", 74)), "en")
        assertEquals(2, result.size)
    }

    @Test fun farAwayLinesAndOtherColumnsDoNotMerge() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("Character name", 50), f("Level", 200),
                f("Menu option", 55, x = 400)), "en")
        assertEquals(3, result.size)
    }

    @Test fun differentBlocksTightlyAlignedCanJoin() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("Continue the", 50, b = 1), f("journey", 73, b = 2)), "en")
        assertEquals("Continue the journey", result.single().text)
    }

    @Test fun distantBlocksDoNotJoin() {
        val result = OcrParagraphAssembler.assemble(
            listOf(f("Character name", 50, b = 1), f("Skill points", 85, b = 2)), "en")
        assertEquals(2, result.size)
    }

    @Test fun neverMergesMoreThanFiveLines() {
        val input = (0 until 7).map { f("line$it", it * 24) }
        val result = OcrParagraphAssembler.assemble(input, "en")
        assertEquals(2, result.size)
        assertEquals(5, result[0].lineCount)
        assertEquals(2, result[1].lineCount)
    }
}
