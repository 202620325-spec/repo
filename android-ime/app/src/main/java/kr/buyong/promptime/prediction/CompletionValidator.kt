package kr.buyong.promptime.prediction

object CompletionValidator {
    private val badStarts = listOf(
        "물론입니다", "물론이죠", "네,", "좋습니다", "Sure", "Certainly", "Of course",
        "Here is", "Here's", "다음은", "요청하신"
    )

    fun validate(raw: String, snapshot: EditorSnapshot): String? {
        var text = raw.replace("\u0000", "").trimEnd()
        if (text.isBlank()) return null
        if (badStarts.any { text.startsWith(it, ignoreCase = true) }) return null
        if (text.contains("/p")) text = text.replace("/p", "")

        val prefixOverlap = longestOverlap(snapshot.before.takeLast(120), text.take(120))
        if (prefixOverlap >= 8) text = text.drop(prefixOverlap)

        if (snapshot.after.isNotEmpty()) {
            val rightOverlap = longestSuffixPrefix(text, snapshot.after.take(120))
            if (rightOverlap >= 2) text = text.dropLast(rightOverlap)
        }

        if (text.length > 520) text = text.take(520)
        return text.takeIf { it.isNotBlank() }
    }

    private fun longestOverlap(left: String, right: String): Int {
        val max = minOf(left.length, right.length, 120)
        for (n in max downTo 1) if (left.takeLast(n) == right.take(n)) return n
        return 0
    }

    private fun longestSuffixPrefix(left: String, right: String): Int {
        val max = minOf(left.length, right.length, 120)
        for (n in max downTo 1) if (left.takeLast(n) == right.take(n)) return n
        return 0
    }
}
