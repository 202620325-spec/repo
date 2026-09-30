package kr.buyong.promptime.core

object CompletionSlicer {
    fun nextWord(text: String): Int {
        if (text.isEmpty()) return 0
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && !text[i].isWhitespace()) i++
        while (i < text.length && text[i].isWhitespace()) i++
        return i.coerceAtLeast(1)
    }

    fun nextPhrase(text: String): Int {
        if (text.isEmpty()) return 0
        val punctuation = setOf(',', '.', ';', ':', '!', '?', '。', '！', '？', '\n')
        for (i in text.indices) {
            if (i >= 5 && text[i] in punctuation) {
                var end = i + 1
                while (end < text.length && text[end].isWhitespace()) end++
                return end
            }
        }
        val softLimit = 36.coerceAtMost(text.length)
        val boundary = text.lastIndexOf(' ', softLimit - 1)
        return if (boundary >= 8) boundary + 1 else softLimit
    }
}
