package kr.buyong.promptime.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kr.buyong.promptime.data.ImePreferences
import kr.buyong.promptime.data.ModelMode
import kr.buyong.promptime.metrics.MetricsStore

class SettingsActivity : Activity() {
    private lateinit var prefs: ImePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ImePreferences(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(247, 247, 249))
        }

        root.addView(TextView(this).apply {
            text = "Prompt IME 설정"
            textSize = 24f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "Solar API는 키보드에서 직접 호출됩니다. API 키는 Android Keystore 기반으로 이 기기에 암호화 저장되며 Prompt Mode에서만 사용됩니다."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(18))
        })

        val keyInput = EditText(this).apply {
            hint = if (prefs.apiKey().isBlank()) "Upstage API key" else "API key 저장됨 — 새 키를 입력하면 교체"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(keyInput, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "API 키 저장"
            setOnClickListener {
                val value = keyInput.text.toString().trim()
                if (value.isNotBlank()) {
                    prefs.saveApiKey(value)
                    keyInput.text.clear()
                    keyInput.hint = "API key 저장됨 — 새 키를 입력하면 교체"
                    Toast.makeText(this@SettingsActivity, "저장됨", Toast.LENGTH_SHORT).show()
                }
            }
        })

        root.addView(TextView(this).apply {
            text = "모델"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, dp(18), 0, dp(6))
        })
        val radio = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modes = listOf(
            ModelMode.AUTO to "자동 — 일반 mini4 / 복잡한 문맥 pro4",
            ModelMode.MINI4 to "solar-mini4 — 지연 우선",
            ModelMode.PRO4 to "solar-pro4 — 품질 우선"
        )
        modes.forEachIndexed { idx, (mode, label) ->
            radio.addView(RadioButton(this).apply {
                id = 1000 + idx
                text = label
                tag = mode
                isChecked = prefs.modelMode == mode
            })
        }
        radio.setOnCheckedChangeListener { group, checkedId ->
            (group.findViewById<RadioButton>(checkedId)?.tag as? ModelMode)?.let { prefs.modelMode = it }
        }
        root.addView(radio)

        root.addView(Switch(this).apply {
            text = "로컬 개인화 신호 저장"
            isChecked = prefs.personalizationEnabled
            setOnCheckedChangeListener { _, checked -> prefs.personalizationEnabled = checked }
            setPadding(0, dp(10), 0, dp(8))
        })

        root.addView(Button(this).apply {
            text = "1. 키보드 사용 허용"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Prompt IME 선택"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })

        root.addView(TextView(this).apply {
            val m = MetricsStore(this@SettingsActivity).snapshot()
            text = "로컬 지표: accepted=${m["accepted_chars"] ?: 0} chars / manual=${m["manual_chars"] ?: 0} chars · requests=${m["request_count"] ?: 0}"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
