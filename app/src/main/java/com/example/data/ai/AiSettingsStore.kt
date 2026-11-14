package com.example.data.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class AiSettingsStore(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            "shehzad_algo_ai_prefs_secure",
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val settingsFlow: Flow<AiHubSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readSettings())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO)

    suspend fun setSelectedProvider(provider: AiProviderType) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_SELECTED_PROVIDER, provider.name).apply()
    }

    suspend fun setProviderConfig(
        provider: AiProviderType,
        model: String,
        apiKey: String,
        customUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        val cleanModel = model.trim()
        val modelKey = "model_${provider.name}"
        if (cleanModel.isNotEmpty()) {
            editor.putString(modelKey, cleanModel)
        } else {
            editor.remove(modelKey)
        }

        val cleanApiKey = apiKey.trim()
        val secretKey = "apikey_${provider.name}"
        if (cleanApiKey.isNotEmpty()) {
            editor.putString(secretKey, cleanApiKey)
        } else {
            editor.remove(secretKey)
        }

        if (customUrl != null) {
            editor.putString(KEY_CUSTOM_URL, customUrl.trim().ifBlank { AiProviderType.CUSTOM_OPENAI.defaultEndpoint })
        }

        editor.apply()
    }

    suspend fun setIncludeDeskContext(include: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_INCLUDE_CONTEXT, include).apply()
    }

    private fun readSettings(): AiHubSettings {
        val providerName = prefs.getString(KEY_SELECTED_PROVIDER, null)
        val selectedProvider = AiProviderType.entries.firstOrNull { it.name == providerName }
            ?: AiProviderType.FIREBASE_GEMINI

        val models = buildMap {
            AiProviderType.entries.forEach { provider ->
                val value = prefs.getString("model_${provider.name}", null)
                if (!value.isNullOrBlank()) put(provider, value)
            }
        }

        val keys = buildMap {
            AiProviderType.entries.forEach { provider ->
                val value = prefs.getString("apikey_${provider.name}", null)
                if (!value.isNullOrBlank()) put(provider, value)
            }
        }

        return AiHubSettings(
            selectedProvider = selectedProvider,
            modelByProvider = models,
            apiKeysByProvider = keys,
            customBaseUrl = prefs.getString(KEY_CUSTOM_URL, AiProviderType.CUSTOM_OPENAI.defaultEndpoint)
                ?: AiProviderType.CUSTOM_OPENAI.defaultEndpoint,
            includeDeskContext = prefs.getBoolean(KEY_INCLUDE_CONTEXT, true)
        )
    }

    private companion object {
        const val KEY_SELECTED_PROVIDER = "selected_provider"
        const val KEY_CUSTOM_URL = "custom_openai_url"
        const val KEY_INCLUDE_CONTEXT = "include_desk_context"
    }
}
