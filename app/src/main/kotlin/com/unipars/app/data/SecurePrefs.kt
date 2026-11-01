package com.unipars.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.unipars.ai.ClaudeClient

/** API key and AI options, encrypted with a key held in the Android Keystore. */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: ClaudeClient.DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** low | medium | high */
    var effort: String
        get() = prefs.getString("effort", "medium") ?: "medium"
        set(v) = prefs.edit().putString("effort", v).apply()

    var webResearch: Boolean
        get() = prefs.getBoolean("web_research", true)
        set(v) = prefs.edit().putBoolean("web_research", v).apply()

    var spentInputTokens: Long
        get() = prefs.getLong("spent_in", 0)
        set(v) = prefs.edit().putLong("spent_in", v).apply()

    var spentOutputTokens: Long
        get() = prefs.getLong("spent_out", 0)
        set(v) = prefs.edit().putLong("spent_out", v).apply()

    var onboardingShown: Boolean
        get() = prefs.getBoolean("onboarding", false)
        set(v) = prefs.edit().putBoolean("onboarding", v).apply()
}
