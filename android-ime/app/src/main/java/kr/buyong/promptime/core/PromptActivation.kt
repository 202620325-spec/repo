package kr.buyong.promptime.core

object PromptActivation {
    fun shouldActivate(textBeforeCursor: String, typedLatinChar: Char, uriLikeEditor: Boolean): Boolean {
        if (uriLikeEditor) return false
        if (typedLatinChar != 'p') return false
        if (!textBeforeCursor.endsWith('/')) return false
        if (textBeforeCursor.length == 1) return true
        val beforeSlash = textBeforeCursor[textBeforeCursor.length - 2]
        return beforeSlash.isWhitespace() || beforeSlash in "([{'\""
    }
}
