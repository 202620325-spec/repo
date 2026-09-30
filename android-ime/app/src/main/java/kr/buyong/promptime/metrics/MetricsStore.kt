package kr.buyong.promptime.metrics

import android.content.Context

class MetricsStore(context: Context) {
    private val prefs = context.getSharedPreferences("promptime_metrics", Context.MODE_PRIVATE)

    fun suggestionShown(chars: Int) = add("suggestion_shown", 1, "suggested_chars", chars.toLong())
    fun accepted(chars: Int, partial: Boolean) {
        add(if (partial) "partial_accept" else "full_accept", 1, "accepted_chars", chars.toLong())
    }
    fun manual(chars: Int) = add("manual_chars", chars.toLong())
    fun staleDiscard() = add("stale_discard", 1)
    fun request() = add("request_count", 1)
    fun correctionAfterAccept() = add("correction_after_accept", 1)

    fun timing(debounceMs: Long, networkMs: Long, firstTokenMs: Long, renderMs: Long) {
        prefs.edit()
            .putLong("last_debounce_ms", debounceMs)
            .putLong("last_network_ms", networkMs)
            .putLong("last_first_token_ms", firstTokenMs)
            .putLong("last_render_ms", renderMs)
            .apply()
    }

    fun snapshot(): Map<String, Long> = listOf(
        "suggestion_shown", "suggested_chars", "partial_accept", "full_accept",
        "accepted_chars", "manual_chars", "stale_discard", "request_count",
        "correction_after_accept", "last_debounce_ms", "last_network_ms",
        "last_first_token_ms", "last_render_ms"
    ).associateWith { prefs.getLong(it, 0L) }

    fun reset() = prefs.edit().clear().apply()

    private fun add(key: String, amount: Long, key2: String? = null, amount2: Long = 0L) {
        val edit = prefs.edit().putLong(key, prefs.getLong(key, 0L) + amount)
        if (key2 != null) edit.putLong(key2, prefs.getLong(key2, 0L) + amount2)
        edit.apply()
    }
}
