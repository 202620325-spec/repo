package kr.buyong.promptime.prediction

import kr.buyong.promptime.core.CompletionSlicer
import kr.buyong.promptime.core.PredictionBuffer
import kr.buyong.promptime.data.ImePreferences
import kr.buyong.promptime.data.ModelMode
import kr.buyong.promptime.metrics.MetricsStore
import kr.buyong.promptime.network.SolarApiClient
import kr.buyong.promptime.network.SolarApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

class PredictionCoordinator(
    private val scope: CoroutineScope,
    private val preferences: ImePreferences,
    private val client: SolarApiClient,
    private val metrics: MetricsStore,
    private val onState: (PredictionUiState) -> Unit
) {
    private val generation = AtomicLong(0)
    private val buffer = PredictionBuffer()
    private var requestJob: Job? = null
    private var latestSnapshot: EditorSnapshot? = null
    private var activeSessionId = -1L
    private var promptMode = false
    private var state = PredictionUiState()
    private var lastAccepted = ""
    private var lastKeyAt = 0L
    private var emaIntervalMs = 220.0

    fun startSession(sessionId: Long) {
        cancelAll()
        activeSessionId = sessionId
        promptMode = false
        latestSnapshot = null
        lastAccepted = ""
        publish(PredictionUiState(active = false))
    }

    fun endSession() {
        cancelAll()
        promptMode = false
        latestSnapshot = null
        buffer.clear()
        publish(PredictionUiState(active = false))
    }

    fun enablePrompt(snapshot: EditorSnapshot) {
        if (snapshot.sessionId != activeSessionId) return
        promptMode = true
        latestSnapshot = snapshot
        buffer.clear()
        publish(PredictionUiState(active = true, status = if (preferences.apiKey().isBlank()) "API 키 필요" else ""))
        schedule(snapshot, Trigger.ACTIVATE)
    }

    fun observeTyped(snapshot: EditorSnapshot, typedText: String, trigger: Trigger) {
        latestSnapshot = snapshot
        if (!promptMode || snapshot.sessionId != activeSessionId) return
        metrics.manual(typedText.length)
        updateTypingRate(trigger)

        if (!buffer.isEmpty) {
            val matched = buffer.consumeTyped(typedText)
            if (matched) {
                publish(state.copy(active = true, suggestion = buffer.remaining, loading = false, status = ""))
                if (!buffer.isEmpty) return
            }
        }
        schedule(snapshot, trigger)
    }

    fun observeEditorChange(snapshot: EditorSnapshot, trigger: Trigger) {
        latestSnapshot = snapshot
        if (!promptMode || snapshot.sessionId != activeSessionId) return
        buffer.clear()
        publish(state.copy(active = true, suggestion = "", loading = false))
        schedule(snapshot, trigger)
    }

    fun takeWord(): String = take(CompletionSlicer.nextWord(buffer.remaining), partial = true)
    fun takePhrase(): String = take(CompletionSlicer.nextPhrase(buffer.remaining), partial = true)
    fun takeAll(): String = take(buffer.remaining.length, partial = false)

    fun afterAccepted(snapshot: EditorSnapshot, accepted: String) {
        latestSnapshot = snapshot
        if (accepted.isNotBlank()) lastAccepted = (lastAccepted + accepted).takeLast(240)
        if (!promptMode) return
        if (buffer.isEmpty) schedule(snapshot, Trigger.ACCEPT)
        else publish(state.copy(active = true, suggestion = buffer.remaining, loading = false, status = ""))
    }

    fun suggestion(): String = buffer.remaining

    private fun take(count: Int, partial: Boolean): String {
        val accepted = buffer.accept(count)
        if (accepted.isNotEmpty()) {
            metrics.accepted(accepted.length, partial && buffer.remaining.isNotEmpty())
            publish(state.copy(active = true, suggestion = buffer.remaining, loading = false))
        }
        return accepted
    }

    private fun schedule(snapshot: EditorSnapshot, trigger: Trigger) {
        requestJob?.cancel()
        val key = preferences.apiKey()
        if (key.isBlank()) {
            publish(PredictionUiState(active = true, status = "API 키 필요"))
            return
        }
        if ((snapshot.before + snapshot.after).isBlank()) return

        val myGeneration = generation.incrementAndGet()
        val debounce = debounceFor(trigger)
        requestJob = scope.launch {
            delay(debounce)
            if (!isCurrent(snapshot, myGeneration)) return@launch
            val model = chooseModel(snapshot)
            val maxTokens = chooseMaxTokens(snapshot)
            metrics.request()
            publish(PredictionUiState(active = true, loading = true, model = model))
            val requestStarted = System.nanoTime()
            try {
                val result = client.streamCompletion(
                    apiKey = key,
                    model = model,
                    systemPrompt = ContinuationPrompt.system,
                    userPrompt = ContinuationPrompt.user(snapshot, lastAccepted),
                    maxTokens = maxTokens
                ) { partial, firstTokenMs ->
                    withContext(Dispatchers.Main.immediate) {
                        if (!isCurrent(snapshot, myGeneration)) {
                            metrics.staleDiscard()
                            return@withContext
                        }
                        val valid = CompletionValidator.validate(partial, snapshot) ?: return@withContext
                        buffer.replace(valid)
                        val renderStarted = System.nanoTime()
                        publish(PredictionUiState(active = true, loading = true, suggestion = valid, model = model))
                        metrics.timing(debounce, firstTokenMs, firstTokenMs, (System.nanoTime() - renderStarted) / 1_000_000L)
                    }
                }
                if (!isCurrent(snapshot, myGeneration)) {
                    metrics.staleDiscard()
                    return@launch
                }
                val valid = CompletionValidator.validate(result.text, snapshot)
                if (valid == null) {
                    buffer.clear()
                    publish(PredictionUiState(active = true, loading = false, model = model))
                } else {
                    buffer.replace(valid)
                    metrics.suggestionShown(valid.length)
                    val total = (System.nanoTime() - requestStarted) / 1_000_000L
                    metrics.timing(debounce, result.networkMs, result.firstTokenMs, (total - result.networkMs).coerceAtLeast(0L))
                    publish(PredictionUiState(active = true, loading = false, suggestion = valid, model = model))
                }
            } catch (_: CancellationException) {
                // Expected when the user keeps typing or switches editor.
            } catch (e: SolarApiException) {
                buffer.clear()
                val label = when (e.statusCode) {
                    401, 403 -> "API 키 확인"
                    429 -> "요청 제한"
                    in 500..599 -> "Solar 일시 오류"
                    else -> "AI 오류"
                }
                publish(PredictionUiState(active = true, status = label))
            } catch (_: Throwable) {
                buffer.clear()
                publish(PredictionUiState(active = true, status = "오프라인"))
            }
        }
    }

    private fun isCurrent(snapshot: EditorSnapshot, generationId: Long): Boolean {
        val latest = latestSnapshot ?: return false
        return promptMode && generation.get() == generationId && snapshot.sameTextState(latest) && snapshot.sessionId == activeSessionId
    }

    private fun debounceFor(trigger: Trigger): Long = when (trigger) {
        Trigger.ACCEPT -> 0L
        Trigger.ACTIVATE -> 70L
        Trigger.SPACE, Trigger.PUNCTUATION -> 85L
        Trigger.CURSOR -> 180L
        Trigger.BACKSPACE -> 220L
        Trigger.KEY -> (emaIntervalMs * 1.25).toLong().coerceIn(170L, 430L)
    }

    private fun updateTypingRate(trigger: Trigger) {
        if (trigger != Trigger.KEY) return
        val now = System.nanoTime()
        if (lastKeyAt != 0L) {
            val dt = ((now - lastKeyAt) / 1_000_000.0).coerceIn(30.0, 1200.0)
            emaIntervalMs = emaIntervalMs * 0.72 + dt * 0.28
        }
        lastKeyAt = now
    }

    private fun chooseModel(snapshot: EditorSnapshot): String = when (preferences.modelMode) {
        ModelMode.MINI4 -> "solar-mini4"
        ModelMode.PRO4 -> "solar-pro4"
        ModelMode.AUTO -> {
            val text = snapshot.before
            val complex = text.length > 900 || snapshot.after.isNotBlank() ||
                (snapshot.multiline && listOf("class ", "fun ", "def ", "API", "architecture", "구현", "코드").any { text.contains(it, true) })
            if (complex) "solar-pro4" else "solar-mini4"
        }
    }

    private fun chooseMaxTokens(snapshot: EditorSnapshot): Int {
        val n = snapshot.before.trim().length
        return when {
            n < 6 -> 24
            n < 24 -> 42
            snapshot.after.isNotBlank() -> 54
            snapshot.multiline -> 88
            else -> 72
        }
    }

    private fun cancelAll() {
        generation.incrementAndGet()
        requestJob?.cancel()
        requestJob = null
        buffer.clear()
    }

    private fun publish(next: PredictionUiState) {
        state = next
        onState(next)
    }
}
