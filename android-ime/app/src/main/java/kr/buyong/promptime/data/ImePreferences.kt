package kr.buyong.promptime.data

import android.content.Context

enum class ModelMode(val wire: String) {
    AUTO("auto"), MINI4("solar-mini4"), PRO4("solar-pro4");

    companion object {
        fun from(value: String?): ModelMode = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

class ImePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ime_preferences", Context.MODE_PRIVATE)
    private val secure = SecureApiKeyStore(context)

    var modelMode: ModelMode
        get() = ModelMode.from(prefs.getString("model_mode", ModelMode.AUTO.wire))
        set(value) { prefs.edit().putString("model_mode", value.wire).apply() }

    var personalizationEnabled: Boolean
        get() = prefs.getBoolean("personalization_enabled", true)
        set(value) { prefs.edit().putBoolean("personalization_enabled", value).apply() }

    fun apiKey(): String = secure.read().orEmpty()
    fun saveApiKey(value: String) {
        if (value.isBlank()) secure.clear() else secure.write(value.trim())
    }
}
