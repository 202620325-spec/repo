package kr.buyong.promptime.core

data class HangulEdit(val commit: String = "", val composing: String = "")

class HangulComposer {
    private var initial = -1
    private var medial = -1
    private var final = 0

    val isEmpty: Boolean get() = initial < 0 && medial < 0 && final == 0

    fun input(jamo: Char): HangulEdit {
        return if (jamo in CONSONANTS) inputConsonant(jamo) else inputVowel(jamo)
    }

    fun backspace(): HangulEdit? {
        if (final != 0) {
            final = FINAL_DECOMPOSE[final]?.first ?: 0
            return HangulEdit(composing = render())
        }
        if (medial >= 0) {
            medial = MEDIAL_DECOMPOSE[medial] ?: -1
            return HangulEdit(composing = render())
        }
        if (initial >= 0) {
            initial = INITIAL_DECOMPOSE[initial] ?: -1
            return HangulEdit(composing = render())
        }
        return null
    }

    fun flush(): String {
        val value = render()
        reset()
        return value
    }

    fun reset() {
        initial = -1
        medial = -1
        final = 0
    }

    fun current(): String = render()

    private fun inputConsonant(jamo: Char): HangulEdit {
        val newInitial = INITIAL_INDEX[jamo] ?: return HangulEdit(commit = flush(), composing = jamo.toString())

        if (initial < 0 && medial < 0) {
            initial = newInitial
            return HangulEdit(composing = render())
        }
        if (initial < 0 && medial >= 0) {
            val committed = render()
            reset()
            initial = newInitial
            return HangulEdit(commit = committed, composing = render())
        }
        if (initial >= 0 && medial < 0) {
            val combined = INITIAL_COMBINE[initial to newInitial]
            if (combined != null) {
                initial = combined
                return HangulEdit(composing = render())
            }
            val committed = render()
            reset()
            initial = newInitial
            return HangulEdit(commit = committed, composing = render())
        }

        val newFinal = FINAL_INDEX[jamo]
        if (final == 0 && newFinal != null) {
            final = newFinal
            return HangulEdit(composing = render())
        }
        if (final != 0 && newFinal != null) {
            val combined = FINAL_COMBINE[final to newFinal]
            if (combined != null) {
                final = combined
                return HangulEdit(composing = render())
            }
        }

        val committed = render()
        reset()
        initial = newInitial
        return HangulEdit(commit = committed, composing = render())
    }

    private fun inputVowel(jamo: Char): HangulEdit {
        val newMedial = MEDIAL_INDEX[jamo] ?: return HangulEdit(commit = flush(), composing = jamo.toString())

        if (initial < 0 && medial < 0) {
            medial = newMedial
            return HangulEdit(composing = render())
        }
        if (initial < 0 && medial >= 0) {
            val combined = MEDIAL_COMBINE[medial to newMedial]
            if (combined != null) {
                medial = combined
                return HangulEdit(composing = render())
            }
            val committed = render()
            reset()
            medial = newMedial
            return HangulEdit(commit = committed, composing = render())
        }
        if (initial >= 0 && medial < 0) {
            medial = newMedial
            return HangulEdit(composing = render())
        }
        if (final == 0) {
            val combined = MEDIAL_COMBINE[medial to newMedial]
            if (combined != null) {
                medial = combined
                return HangulEdit(composing = render())
            }
            val committed = render()
            reset()
            medial = newMedial
            return HangulEdit(commit = committed, composing = render())
        }

        val oldFinal = final
        val split = FINAL_SPLIT[oldFinal]
        return if (split != null) {
            final = split.first
            val committed = render()
            reset()
            initial = FINAL_TO_INITIAL[split.second] ?: -1
            medial = newMedial
            HangulEdit(commit = committed, composing = render())
        } else {
            final = 0
            val committed = render()
            reset()
            initial = FINAL_TO_INITIAL[oldFinal] ?: -1
            medial = newMedial
            HangulEdit(commit = committed, composing = render())
        }
    }

    private fun render(): String {
        if (initial >= 0 && medial >= 0) {
            val code = 0xAC00 + ((initial * 21 + medial) * 28) + final
            return code.toChar().toString()
        }
        if (initial >= 0) return INITIALS[initial].toString()
        if (medial >= 0) return MEDIALS[medial].toString()
        return ""
    }

    companion object {
        private val INITIALS = charArrayOf('ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
        private val MEDIALS = charArrayOf('ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ')
        private val FINALS = charArrayOf('\u0000','ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ')
        private val CONSONANTS = INITIALS.toSet()
        private val INITIAL_INDEX = INITIALS.withIndex().associate { it.value to it.index }
        private val MEDIAL_INDEX = MEDIALS.withIndex().associate { it.value to it.index }
        private val FINAL_INDEX = FINALS.withIndex().filter { it.index > 0 }.associate { it.value to it.index }
        private val FINAL_TO_INITIAL = FINAL_INDEX.mapNotNull { (ch, f) -> INITIAL_INDEX[ch]?.let { f to it } }.toMap()

        private val INITIAL_COMBINE = mapOf(
            (0 to 0) to 1,
            (3 to 3) to 4,
            (7 to 7) to 8,
            (9 to 9) to 10,
            (12 to 12) to 13
        )
        private val INITIAL_DECOMPOSE = mapOf(1 to 0, 4 to 3, 8 to 7, 10 to 9, 13 to 12)

        private val MEDIAL_COMBINE = mapOf(
            (8 to 0) to 9,
            (8 to 1) to 10,
            (8 to 20) to 11,
            (13 to 4) to 14,
            (13 to 5) to 15,
            (13 to 20) to 16,
            (18 to 20) to 19
        )
        private val MEDIAL_DECOMPOSE = mapOf(9 to 8, 10 to 8, 11 to 8, 14 to 13, 15 to 13, 16 to 13, 19 to 18)

        private val FINAL_COMBINE = mapOf(
            (1 to 1) to 2,
            (1 to 19) to 3,
            (4 to 22) to 5,
            (4 to 27) to 6,
            (8 to 1) to 9,
            (8 to 16) to 10,
            (8 to 17) to 11,
            (8 to 19) to 12,
            (8 to 25) to 13,
            (8 to 26) to 14,
            (8 to 27) to 15,
            (17 to 19) to 18,
            (19 to 19) to 20
        )
        private val FINAL_SPLIT = mapOf(
            3 to (1 to 19),
            5 to (4 to 22),
            6 to (4 to 27),
            9 to (8 to 1),
            10 to (8 to 16),
            11 to (8 to 17),
            12 to (8 to 19),
            13 to (8 to 25),
            14 to (8 to 26),
            15 to (8 to 27),
            18 to (17 to 19)
        )
        private val FINAL_DECOMPOSE = FINAL_SPLIT + mapOf(2 to (1 to 1), 20 to (19 to 19))
    }
}
