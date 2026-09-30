package kr.buyong.promptime.ime

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.buyong.promptime.core.ContextSelector
import kr.buyong.promptime.core.HangulComposer
import kr.buyong.promptime.core.PromptActivation
import kr.buyong.promptime.data.ImePreferences
import kr.buyong.promptime.metrics.MetricsStore
import kr.buyong.promptime.network.SolarApiClient
import kr.buyong.promptime.prediction.EditorSnapshot
import kr.buyong.promptime.prediction.PredictionCoordinator
import kr.buyong.promptime.prediction.PredictionUiState
import kr.buyong.promptime.prediction.Trigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PromptImeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val composer = HangulComposer()
    private lateinit var coordinator: PredictionCoordinator
    private lateinit var prefs: ImePreferences

    private var sessionId = 0L
    private var promptActive = false
    private var korean = true
    private var shift = false
    private var symbols = false
    private var selectionStart = 0
    private var lastInternalEditNanos = 0L

    private lateinit var root: LinearLayout
    private lateinit var aiBar: LinearLayout
    private lateinit var modeLabel: TextView
    private lateinit var suggestionText: TextView
    private lateinit var acceptWord: Button
    private lateinit var acceptPhrase: Button
    private lateinit var acceptAll: Button
    private lateinit var keyboardRows: LinearLayout

    override fun onCreate() {
        super.onCreate()
        prefs = ImePreferences(this)
        coordinator = PredictionCoordinator(
            scope = scope,
            preferences = prefs,
            client = SolarApiClient(),
            metrics = MetricsStore(this),
            onState = { renderPrediction(it) }
        )
    }

    override fun onDestroy() {
        coordinator.endSession()
        scope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(6))
            setBackgroundColor(Color.rgb(238, 239, 243))
        }

        aiBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(5))
        }
        modeLabel = TextView(this).apply {
            text = "Prompt"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(42, 108, 73))
            gravity = Gravity.CENTER
        }
        suggestionText = TextView(this).apply {
            textSize = 14f
            maxLines = 2
            setTextColor(Color.rgb(35, 35, 39))
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        acceptWord = smallAction("단어") { acceptSuggestion(AcceptKind.WORD) }
        acceptPhrase = smallAction("구") { acceptSuggestion(AcceptKind.PHRASE) }
        acceptAll = smallAction("전체") { acceptSuggestion(AcceptKind.ALL) }

        aiBar.addView(modeLabel, LinearLayout.LayoutParams(dp(54), dp(40)))
        aiBar.addView(suggestionText, LinearLayout.LayoutParams(0, dp(48), 1f))
        aiBar.addView(acceptWord, LinearLayout.LayoutParams(dp(48), dp(40)))
        aiBar.addView(acceptPhrase, LinearLayout.LayoutParams(dp(42), dp(40)))
        aiBar.addView(acceptAll, LinearLayout.LayoutParams(dp(48), dp(40)))
        root.addView(aiBar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        keyboardRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboardRows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rebuildKeyboard()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sessionId++
        promptActive = false
        composer.reset()
        shift = false
        symbols = false
        selectionStart = info?.initialSelStart?.coerceAtLeast(0) ?: 0
        coordinator.startSession(sessionId)
        if (::aiBar.isInitialized) {
            aiBar.visibility = View.GONE
            rebuildKeyboard()
        }
    }

    override fun onFinishInput() {
        promptActive = false
        composer.reset()
        coordinator.endSession()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart.coerceAtLeast(0)
        if (!promptActive) return
        val msSinceInternal = (System.nanoTime() - lastInternalEditNanos) / 1_000_000L
        if (msSinceInternal < 120L) return
        composer.reset()
        coordinator.observeEditorChange(captureSnapshot(), Trigger.CURSOR)
    }

    private fun rebuildKeyboard() {
        if (!::keyboardRows.isInitialized) return
        keyboardRows.removeAllViews()
        if (symbols) buildSymbols() else buildLetters()
    }

    private fun buildLetters() {
        val rows = listOf("qwertyuiop", "asdfghjkl")
        rows.forEach { chars -> addKeyRow(chars.map { KeySpec(labelFor(it), 1f) { onLetter(it) } }) }

        val third = mutableListOf<KeySpec>()
        third += KeySpec(if (shift) "⇧" else "↑", 1.25f) { shift = !shift; rebuildKeyboard() }
        "zxcvbnm".forEach { c -> third += KeySpec(labelFor(c), 1f) { onLetter(c) } }
        third += KeySpec("⌫", 1.25f) { onBackspace() }
        addKeyRow(third)

        addKeyRow(listOf(
            KeySpec("?123", 1.2f) { finishCompositionForBoundary(); symbols = true; rebuildKeyboard() },
            KeySpec(if (korean) "한/EN" else "EN/한", 1.2f) { toggleLanguage() },
            KeySpec("/", 0.8f) { commitLiteral("/", Trigger.PUNCTUATION) },
            KeySpec("space", 3.6f) { onSpace() },
            KeySpec(".", 0.8f) { commitLiteral(".", Trigger.PUNCTUATION) },
            KeySpec("↵", 1.2f) { onEnter() }
        ))
    }

    private fun buildSymbols() {
        addKeyRow("1234567890".map { c -> KeySpec(c.toString(), 1f) { commitLiteral(c.toString(), Trigger.KEY) } })
        addKeyRow(listOf("@", "#", "₩", "_", "%", "&", "-", "+", "(", ")").map { s -> KeySpec(s, 1f) { commitLiteral(s, Trigger.PUNCTUATION) } })
        addKeyRow(listOf(
            KeySpec("ABC", 1.3f) { symbols = false; rebuildKeyboard() },
            KeySpec("*", 1f) { commitLiteral("*", Trigger.PUNCTUATION) },
            KeySpec(""", 1f) { commitLiteral(""", Trigger.PUNCTUATION) },
            KeySpec("'", 1f) { commitLiteral("'", Trigger.PUNCTUATION) },
            KeySpec(":", 1f) { commitLiteral(":", Trigger.PUNCTUATION) },
            KeySpec(";", 1f) { commitLiteral(";", Trigger.PUNCTUATION) },
            KeySpec("!", 1f) { commitLiteral("!", Trigger.PUNCTUATION) },
            KeySpec("?", 1f) { commitLiteral("?", Trigger.PUNCTUATION) },
            KeySpec("⌫", 1.3f) { onBackspace() }
        ))
        addKeyRow(listOf(
            KeySpec("ABC", 1.2f) { symbols = false; rebuildKeyboard() },
            KeySpec(",", 0.9f) { commitLiteral(",", Trigger.PUNCTUATION) },
            KeySpec("/", 0.9f) { commitLiteral("/", Trigger.PUNCTUATION) },
            KeySpec("space", 4f) { onSpace() },
            KeySpec("↵", 1.3f) { onEnter() }
        ))
    }

    private fun addKeyRow(specs: List<KeySpec>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        specs.forEach { spec ->
            val button = keyButton(spec.label, spec.action)
            row.addView(button, LinearLayout.LayoutParams(0, dp(49), spec.weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        keyboardRows.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun onLetter(latin: Char) {
        if (!promptActive && latin == 'p') {
            val before = currentInputConnection?.getTextBeforeCursor(256, 0)?.toString().orEmpty()
            if (PromptActivation.shouldActivate(before, 'p', isUriLikeEditor())) {
                markInternalEdit()
                currentInputConnection?.deleteSurroundingText(1, 0)
                currentInputConnection?.finishComposingText()
                composer.reset()
                promptActive = !isSensitiveEditor()
                if (promptActive) {
                    aiBar.visibility = View.VISIBLE
                    coordinator.enablePrompt(captureSnapshot())
                }
                shift = false
                return
            }
        }

        if (korean) {
            val jamo = koreanJamo(latin, shift)
            markInternalEdit()
            val edit = composer.input(jamo)
            if (edit.commit.isNotEmpty()) currentInputConnection?.commitText(edit.commit, 1)
            if (edit.composing.isNotEmpty()) currentInputConnection?.setComposingText(edit.composing, 1)
            else currentInputConnection?.finishComposingText()
            if (promptActive) {
                if (edit.commit.isNotEmpty()) coordinator.observeTyped(captureSnapshot(), edit.commit, Trigger.KEY)
                else coordinator.observeEditorChange(captureSnapshot(), Trigger.KEY)
            }
        } else {
            val out = if (shift) latin.uppercaseChar() else latin
            markInternalEdit()
            currentInputConnection?.commitText(out.toString(), 1)
            if (promptActive) coordinator.observeTyped(captureSnapshot(), out.toString(), Trigger.KEY)
        }
        if (shift) {
            shift = false
            rebuildKeyboard()
        }
    }

    private fun onSpace() {
        val composed = finishCompositionForBoundary()
        markInternalEdit()
        currentInputConnection?.commitText(" ", 1)
        if (promptActive) coordinator.observeTyped(captureSnapshot(), composed + " ", Trigger.SPACE)
    }

    private fun commitLiteral(text: String, trigger: Trigger) {
        val composed = finishCompositionForBoundary()
        markInternalEdit()
        currentInputConnection?.commitText(text, 1)
        if (promptActive) coordinator.observeTyped(captureSnapshot(), composed + text, trigger)
    }

    private fun onEnter() {
        val composed = finishCompositionForBoundary()
        val info = currentInputEditorInfo
        val multiline = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        markInternalEdit()
        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
            if (promptActive && composed.isNotEmpty()) coordinator.observeTyped(captureSnapshot(), composed, Trigger.PUNCTUATION)
        } else {
            currentInputConnection?.commitText("
", 1)
            if (promptActive) coordinator.observeTyped(captureSnapshot(), composed + "
", Trigger.PUNCTUATION)
        }
    }

    private fun onBackspace() {
        markInternalEdit()
        val edit = composer.backspace()
        if (edit != null) {
            currentInputConnection?.setComposingText(edit.composing, 1)
            if (edit.composing.isEmpty()) currentInputConnection?.finishComposingText()
        } else {
            currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
        }
        if (promptActive) coordinator.observeEditorChange(captureSnapshot(), Trigger.BACKSPACE)
    }

    private fun toggleLanguage() {
        finishCompositionForBoundary()
        korean = !korean
        shift = false
        rebuildKeyboard()
    }

    private fun finishCompositionForBoundary(): String {
        val composed = composer.flush()
        if (composed.isNotEmpty()) {
            markInternalEdit()
            currentInputConnection?.finishComposingText()
        }
        return composed
    }

    private fun acceptSuggestion(kind: AcceptKind) {
        if (!promptActive || coordinator.suggestion().isEmpty()) return
        finishCompositionForBoundary()
        val accepted = when (kind) {
            AcceptKind.WORD -> coordinator.takeWord()
            AcceptKind.PHRASE -> coordinator.takePhrase()
            AcceptKind.ALL -> coordinator.takeAll()
        }
        if (accepted.isEmpty()) return
        markInternalEdit()
        currentInputConnection?.commitText(accepted, 1)
        coordinator.afterAccepted(captureSnapshot(), accepted)
    }

    private fun captureSnapshot(): EditorSnapshot {
        val ic = currentInputConnection
        val selected = ContextSelector.select(
            beforeRaw = ic?.getTextBeforeCursor(2400, 0)?.toString().orEmpty(),
            afterRaw = ic?.getTextAfterCursor(800, 0)?.toString().orEmpty()
        )
        return EditorSnapshot(
            sessionId = sessionId,
            before = selected.before,
            after = selected.after,
            selectionStart = selectionStart,
            inputType = currentInputEditorInfo?.inputType ?: 0,
            language = selected.language,
            multiline = selected.multiline
        )
    }

    private fun renderPrediction(state: PredictionUiState) {
        if (!::aiBar.isInitialized) return
        aiBar.visibility = if (promptActive && !isSensitiveEditor()) View.VISIBLE else View.GONE
        if (aiBar.visibility != View.VISIBLE) return
        modeLabel.text = when {
            state.loading -> "Prompt ·"
            state.model.endsWith("pro4") -> "Prompt P"
            state.model.endsWith("mini4") -> "Prompt M"
            else -> "Prompt"
        }
        suggestionText.text = when {
            state.suggestion.isNotEmpty() -> state.suggestion
            state.status.isNotEmpty() -> state.status
            state.loading -> "예측 중…"
            else -> ""
        }
        val enabled = state.suggestion.isNotEmpty()
        acceptWord.isEnabled = enabled
        acceptPhrase.isEnabled = enabled
        acceptAll.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.35f
        acceptWord.alpha = alpha
        acceptPhrase.alpha = alpha
        acceptAll.alpha = alpha
    }

    private fun isSensitiveEditor(): Boolean {
        val inputType = currentInputEditorInfo?.inputType ?: return false
        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun isUriLikeEditor(): Boolean {
        val inputType = currentInputEditorInfo?.inputType ?: return false
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }

    private fun labelFor(latin: Char): String {
        if (!korean) return (if (shift) latin.uppercaseChar() else latin).toString()
        return koreanJamo(latin, shift).toString()
    }

    private fun koreanJamo(latin: Char, shifted: Boolean): Char {
        val normal = mapOf(
            'q' to 'ㅂ','w' to 'ㅈ','e' to 'ㄷ','r' to 'ㄱ','t' to 'ㅅ','y' to 'ㅛ','u' to 'ㅕ','i' to 'ㅑ','o' to 'ㅐ','p' to 'ㅔ',
            'a' to 'ㅁ','s' to 'ㄴ','d' to 'ㅇ','f' to 'ㄹ','g' to 'ㅎ','h' to 'ㅗ','j' to 'ㅓ','k' to 'ㅏ','l' to 'ㅣ',
            'z' to 'ㅋ','x' to 'ㅌ','c' to 'ㅊ','v' to 'ㅍ','b' to 'ㅠ','n' to 'ㅜ','m' to 'ㅡ'
        )
        val shiftedMap = mapOf('q' to 'ㅃ','w' to 'ㅉ','e' to 'ㄸ','r' to 'ㄲ','t' to 'ㅆ','o' to 'ㅒ','p' to 'ㅖ')
        return if (shifted) shiftedMap[latin] ?: normal.getValue(latin) else normal.getValue(latin)
    }

    private fun keyButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = if (label.length > 4) 12f else 18f
        isAllCaps = false
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        setTextColor(Color.rgb(28, 28, 31))
        setOnClickListener { action() }
    }

    private fun smallAction(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(223, 226, 233))
        setTextColor(Color.rgb(40, 42, 48))
        setOnClickListener { action() }
    }

    private fun markInternalEdit() {
        lastInternalEditNanos = System.nanoTime()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class KeySpec(val label: String, val weight: Float, val action: () -> Unit)
    private enum class AcceptKind { WORD, PHRASE, ALL }
}
