package com.example.data.ai

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiDataStore by preferencesDataStore(name = "shehzad_algo_ai_prefs")

class AiSettingsStore(private val context: Context) {

    val settingsFlow: Flow<AiHubSettings> = context.aiDataStore.data.map { prefs ->
        val providerName = prefs[KEY_SELECTED_PROVIDER]
        val selectedProvider = AiProviderType.entries.firstOrNull { it.name == providerName }
            ?: AiProviderType.FIREBASE_GEMINI

        val models = buildMap {
            AiProviderType.entries.forEach { p ->
                val v = prefs[stringPreferencesKey("model_${p.name}")]
                if (!v.isNullOrBlank()) put(p, v)
            }
        }
        val keys = buildMap {
            AiProviderType.entries.forEach { p ->
                val v = prefs[stringPreferencesKey("apikey_${p.name}")]
                if (!v.isNullOrBlank()) put(p, v)
            }
        }
        AiHubSettings(
            selectedProvider = selectedProvider,
            modelByProvider = models,
            apiKeysByProvider = keys,
            customBaseUrl = prefs[KEY_CUSTOM_URL] ?: AiProviderType.CUSTOM_OPENAI.defaultEndpoint,
            includeDeskContext = prefs[KEY_INCLUDE_CONTEXT] ?: true
        )
    }

    suspend fun setSelectedProvider(provider: AiProviderType) {
        context.aiDataStore.edit { prefs ->
            prefs[KEY_SELECTED_PROVIDER] = provider.name
        }
    }

    suspend fun setProviderConfig(
        provider: AiProviderType,
        model: String,
        apiKey: String,
        customUrl: String? = null
    ) {
        context.aiDataStore.edit { prefs ->
            val cleanModel = model.trim()
            val modelKey = stringPreferencesKey("model_${provider.name}")
            if (cleanModel.isNotEmpty()) {
                prefs[modelKey] = cleanModel
            } else {
                prefs.remove(modelKey)
            }

            val cleanApiKey = apiKey.trim()
            val secretKey = stringPreferencesKey("apikey_${provider.name}")
            if (cleanApiKey.isNotEmpty()) {
                prefs[secretKey] = cleanApiKey
            } else {
                prefs.remove(secretKey)
            }

            if (customUrl != null) {
                prefs[KEY_CUSTOM_URL] = customUrl.trim().ifBlank {
                    AiProviderType.CUSTOM_OPENAI.defaultEndpoint
                }
            }
        }
    }

    suspend fun setIncludeDeskContext(include: Boolean) {
        context.aiDataStore.edit { prefs ->
            prefs[KEY_INCLUDE_CONTEXT] = include
        }
    }

    private companion object {
        val KEY_SELECTED_PROVIDER = stringPreferencesKey("selected_provider")
        val KEY_CUSTOM_URL = stringPreferencesKey("custom_openai_url")
        val KEY_INCLUDE_CONTEXT = booleanPreferencesKey("include_desk_context")
    }
}
