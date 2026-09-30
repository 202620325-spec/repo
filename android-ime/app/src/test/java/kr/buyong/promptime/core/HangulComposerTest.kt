package kr.buyong.promptime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangulComposerTest {
    private fun compose(chars: List<Char>): String {
        val c = HangulComposer()
        val out = StringBuilder()
        chars.forEach { out.append(c.input(it).commit) }
        out.append(c.flush())
        return out.toString()
    }

    @Test fun basicKorean() = assertEquals("한글", compose(listOf('ㅎ','ㅏ','ㄴ','ㄱ','ㅡ','ㄹ')))
    @Test fun compoundVowel() = assertEquals("과", compose(listOf('ㄱ','ㅗ','ㅏ')))
    @Test fun finalMovesToNextSyllable() = assertEquals("가나", compose(listOf('ㄱ','ㅏ','ㄴ','ㅏ')))
    @Test fun compoundFinalSplits() = assertEquals("각사", compose(listOf('ㄱ','ㅏ','ㄱ','ㅅ','ㅏ')))

    @Test fun backspaceDecomposes() {
        val c = HangulComposer()
        listOf('ㄱ','ㅗ','ㅏ').forEach { c.input(it) }
        assertEquals("고", c.backspace()!!.composing)
        assertEquals("ㄱ", c.backspace()!!.composing)
        assertEquals("", c.backspace()!!.composing)
        assertTrue(c.isEmpty)
    }

    @Test fun predictionBufferConsumesMatchingPrefix() {
        val b = PredictionBuffer()
        b.replace("을 만들어줘")
        assertTrue(b.consumeTyped("을 "))
        assertEquals("만들어줘", b.remaining)
        assertFalse(b.consumeTyped("X"))
        assertTrue(b.isEmpty)
    }
}
