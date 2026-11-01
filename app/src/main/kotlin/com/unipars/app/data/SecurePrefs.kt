package com.unipars.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.unipars.ai.AiProvider
import java.security.KeyStore
import java.time.YearMonth
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM with a non-exportable key in the Android Keystore. Replaces the deprecated
 * androidx.security EncryptedSharedPreferences.
 */
class KeystoreCipher(private val alias: String = "unipars_secrets") {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(c.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()
}

/** API keys (encrypted) and AI/app options. */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher()

    fun apiKey(provider: AiProvider): String =
        prefs.getString("key_${provider.id}", null)?.let { cipher.decrypt(it) }.orEmpty()

    fun setApiKey(provider: AiProvider, value: String) {
        val v = value.trim()
        prefs.edit().apply { if (v.isEmpty()) remove("key_${provider.id}") else putString("key_${provider.id}", cipher.encrypt(v)) }.apply()
    }

    var provider: AiProvider
        get() = AiProvider.byId(prefs.getString("provider", null))
        set(v) = prefs.edit().putString("provider", v.id).apply()

    fun model(provider: AiProvider): String =
        prefs.getString("model_${provider.id}", null)?.takeIf { it.isNotBlank() } ?: provider.defaultModel

    fun setModel(provider: AiProvider, value: String) = prefs.edit().putString("model_${provider.id}", value.trim()).apply()

    val hasKey: Boolean get() = apiKey(provider).isNotBlank()

    /** low | medium | high (Claude only). */
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

    /** Estimated spend in USD for the current calendar month. */
    val spentThisMonthUsd: Double
        get() = if (prefs.getString("spent_month", null) == YearMonth.now().toString()) prefs.getFloat("spent_month_usd", 0f).toDouble() else 0.0

    fun addSpend(usd: Double) {
        val month = YearMonth.now().toString()
        val current = spentThisMonthUsd
        prefs.edit().putString("spent_month", month).putFloat("spent_month_usd", (current + usd).toFloat())
            .putFloat("spent_total_usd", prefs.getFloat("spent_total_usd", 0f) + usd.toFloat()).apply()
    }

    val spentTotalUsd: Double get() = prefs.getFloat("spent_total_usd", 0f).toDouble()

    /** 0 = no limit. */
    var monthlyLimitUsd: Double
        get() = prefs.getFloat("monthly_limit", 0f).toDouble()
        set(v) = prefs.edit().putFloat("monthly_limit", v.toFloat()).apply()

    var notificationsAsked: Boolean
        get() = prefs.getBoolean("notif_asked", false)
        set(v) = prefs.edit().putBoolean("notif_asked", v).apply()

    /** EXPANDED | COLLAPSED | HIDDEN — home-screen assistant panel. */
    var assistantMode: String
        get() = prefs.getString("assistant_mode", "EXPANDED") ?: "EXPANDED"
        set(v) = prefs.edit().putString("assistant_mode", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("update_check", 0)
        set(v) = prefs.edit().putLong("update_check", v).apply()

    var notifiedUpdate: String?
        get() = prefs.getString("update_notified", null)
        set(v) = prefs.edit().putString("update_notified", v).apply()
}
