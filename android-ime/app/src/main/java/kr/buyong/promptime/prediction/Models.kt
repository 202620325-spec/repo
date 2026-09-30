package kr.buyong.promptime.prediction

enum class Trigger { KEY, SPACE, PUNCTUATION, ACCEPT, CURSOR, BACKSPACE, ACTIVATE }

data class EditorSnapshot(
    val sessionId: Long,
    val before: String,
    val after: String,
    val selectionStart: Int,
    val inputType: Int,
    val language: String,
    val multiline: Boolean
) {
    fun sameTextState(other: EditorSnapshot): Boolean =
        sessionId == other.sessionId && before == other.before && after == other.after && selectionStart == other.selectionStart
}

data class PredictionUiState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val suggestion: String = "",
    val model: String = "",
    val status: String = ""
)
