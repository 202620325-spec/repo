package kr.buyong.promptime.core

data class SelectedContext(
    val before: String,
    val after: String,
    val language: String,
    val multiline: Boolean
)

object ContextSelector {
    fun select(beforeRaw: String, afterRaw: String, maxBefore: Int = 1800, maxAfter: Int = 500): SelectedContext {
        val before = beforeRaw.takeLast(maxBefore)
        val after = afterRaw.take(maxAfter)
        val ko = before.count { it in '\uAC00'..'\uD7A3' || it in '\u3131'..'\u318E' }
        val en = before.count { it in 'A'..'Z' || it in 'a'..'z' }
        val language = when {
            ko > 0 && en > 0 -> "ko-en-mixed"
            ko > 0 -> "ko"
            en > 0 -> "en"
            else -> "unknown"
        }
        return SelectedContext(before, after, language, '\n' in before || '\n' in after)
    }
}
