package kr.buyong.promptime.core

class PredictionBuffer {
    var remaining: String = ""
        private set

    val isEmpty: Boolean get() = remaining.isEmpty()

    fun replace(value: String) {
        remaining = value
    }

    fun clear() {
        remaining = ""
    }

    fun consumeTyped(text: String): Boolean {
        if (text.isEmpty()) return true
        if (!remaining.startsWith(text)) {
            clear()
            return false
        }
        remaining = remaining.drop(text.length)
        return true
    }

    fun accept(count: Int): String {
        if (remaining.isEmpty() || count <= 0) return ""
        val n = count.coerceAtMost(remaining.length)
        val accepted = remaining.take(n)
        remaining = remaining.drop(n)
        return accepted
    }
}
