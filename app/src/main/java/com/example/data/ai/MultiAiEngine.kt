package com.example.data.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "MultiAiEngine"
private const val AI_TIMEOUT_MS = 60_000L

sealed interface AiStreamChunk {
    data class Delta(val accumulatedText: String) : AiStreamChunk
    data class Complete(val fullText: String) : AiStreamChunk
}

class MultiAiEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val geminiDefaultModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content { text(QUANT_SYSTEM_PROMPT) }
        )
    }

    private val geminiLiteModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content { text(QUANT_SYSTEM_PROMPT) }
        )
    }

    fun streamResponse(
        provider: AiProviderType,
        modelName: String,
        apiKey: String,
        customEndpoint: String,
        userPrompt: String,
        deskContext: String?,
        history: List<AiChatMessage>
    ): Flow<AiStreamChunk> = flow {
        val fullUserMessage = buildString {
            if (!deskContext.isNullOrBlank()) {
                appendLine("=== LIVE SHEHZAD ALGO DESK CONTEXT (READ-ONLY) ===")
                appendLine(deskContext)
                appendLine("=== USER REQUEST ===")
            }
            append(userPrompt)
        }

        try {
            when (provider) {
                AiProviderType.FIREBASE_GEMINI -> {
                    val model = if (modelName == "gemini-3.5-flash-lite") {
                        geminiLiteModel
                    } else {
                        geminiDefaultModel
                    }
                    val recentHistory = history
                        .filter { !it.isError && it.content.isNotBlank() }
                        .takeLast(8)
                        .map { msg ->
                            val roleName = if (msg.role == ChatRole.USER) "user" else "model"
                            content(roleName) { text(msg.content) }
                        }
                    val chat = model.startChat(history = recentHistory)
                    val accumulated = StringBuilder()
                    withTimeout(AI_TIMEOUT_MS) {
                        chat.sendMessageStream(fullUserMessage).collect { chunk ->
                            val piece = chunk.text.orEmpty()
                            if (piece.isNotEmpty()) {
                                accumulated.append(piece)
                                emit(AiStreamChunk.Delta(accumulated.toString()))
                            }
                        }
                    }
                    val final = accumulated.toString().trim()
                    if (final.isEmpty()) {
                        throw IllegalStateException("Gemini returned an empty response.")
                    }
                    emit(AiStreamChunk.Complete(final))
                }

                AiProviderType.ANTHROPIC_CLAUDE -> {
                    if (apiKey.isBlank()) {
                        throw IllegalStateException("Anthropic Claude requires an API key. Add it in Provider Keys or switch to a Free No-Key provider.")
                    }
                    val text = withTimeout(AI_TIMEOUT_MS) {
                        callAnthropic(
                            endpoint = provider.defaultEndpoint,
                            apiKey = apiKey,
                            model = modelName.ifBlank { provider.defaultModel },
                            fullUserMessage = fullUserMessage,
                            history = history
                        )
                    }
                    emit(AiStreamChunk.Complete(text))
                }

                else -> {
                    if (provider.requiresApiKey && apiKey.isBlank()) {
                        throw IllegalStateException("${provider.displayName} requires an API key. Add your key in Provider Setup or switch to Gemini Built-in / Web Free AI.")
                    }
                    val endpoint = when (provider) {
                        AiProviderType.CUSTOM_OPENAI -> customEndpoint.trim().ifBlank { provider.defaultEndpoint }
                        else -> provider.defaultEndpoint
                    }
                    val text = withTimeout(AI_TIMEOUT_MS) {
                        callOpenAiCompatible(
                            provider = provider,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = modelName.ifBlank { provider.defaultModel },
                            fullUserMessage = fullUserMessage,
                            history = history
                        )
                    }
                    emit(AiStreamChunk.Complete(text))
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI request timed out for $provider", e)
            throw IllegalStateException("Request to ${provider.displayName} timed out after 60 seconds. Please retry or switch models.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI call failed for $provider", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun callOpenAiCompatible(
        provider: AiProviderType,
        endpoint: String,
        apiKey: String,
        model: String,
        fullUserMessage: String,
        history: List<AiChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", QUANT_SYSTEM_PROMPT)
            })
            history.filter { !it.isError && it.content.isNotBlank() }.takeLast(8).forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.role == ChatRole.USER) "user" else "assistant")
                    put("content", msg.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", fullUserMessage)
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.3)
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "ShehzadAlgo/1.0")
            .post(payload.toString().toRequestBody(jsonMedia))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        if (provider == AiProviderType.OPENROUTER) {
            requestBuilder.header("HTTP-Referer", "https://shehzadalgo.app")
            requestBuilder.header("X-Title", "Shehzad Algo")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = extractErrorDetail(bodyStr).ifBlank { response.message }
                throw IllegalStateException("${provider.displayName} HTTP ${response.code}: $detail")
            }

            // Some free web endpoints may return plain text or OpenAI JSON
            val trimmed = bodyStr.trim()
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val choices = json.optJSONArray("choices")
                val first = choices?.optJSONObject(0)
                val content = first?.optJSONObject("message")?.optString("content").orEmpty()
                if (content.isNotBlank()) return@withContext content.trim()

                val altText = json.optString("text").ifBlank { json.optString("content") }
                if (altText.isNotBlank()) return@withContext altText.trim()
                throw IllegalStateException("${provider.displayName} returned an empty completion payload.")
            }
            if (trimmed.isBlank()) {
                throw IllegalStateException("${provider.displayName} returned an empty response.")
            }
            trimmed
        }
    }

    private suspend fun callAnthropic(
        endpoint: String,
        apiKey: String,
        model: String,
        fullUserMessage: String,
        history: List<AiChatMessage>
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            history.filter { !it.isError && it.content.isNotBlank() }.takeLast(8).forEach { msg ->
                put(JSONObject().apply {
                    put("role", if (msg.role == ChatRole.USER) "user" else "assistant")
                    put("content", msg.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", fullUserMessage)
            })
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1200)
            put("system", QUANT_SYSTEM_PROMPT)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = extractErrorDetail(bodyStr).ifBlank { response.message }
                throw IllegalStateException("Claude HTTP ${response.code}: $detail")
            }
            val json = JSONObject(bodyStr)
            val contentArr = json.optJSONArray("content")
            val text = buildString {
                if (contentArr != null) {
                    for (i in 0 until contentArr.length()) {
                        val block = contentArr.optJSONObject(i)
                        if (block?.optString("type") == "text") {
                            append(block.optString("text"))
                        }
                    }
                }
            }.trim()
            if (text.isEmpty()) {
                throw IllegalStateException("Anthropic Claude returned an empty response.")
            }
            text
        }
    }

    private fun extractErrorDetail(raw: String): String =
        runCatching {
            val obj = JSONObject(raw)
            obj.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: obj.optString("message").takeIf { it.isNotBlank() }
                ?: raw.take(180)
        }.getOrDefault(raw.take(180))

    companion object {
        const val QUANT_SYSTEM_PROMPT =
            "You are the quantitative research and risk-analysis co-pilot inside Shehzad Algo, " +
                "an institutional Forex & Crypto signal platform. " +
                "Rules you MUST uphold: " +
                "1) Never invent or fabricate market prices, backtest metrics, win rates, or trade signals. Only analyze the factual data supplied in the desk context or general quantitative concepts. " +
                "2) Emphasize risk-first sizing, spread/slippage/funding costs, regime awareness, and closed-bar causality. " +
                "3) Never recommend martingale, averaging down, widening stops, or overriding the Risk Engine kill switch. " +
                "4) Keep answers concise, structured, and transparent about limitations. Include a brief reminder that output is informational only, not financial advice."
    }
}
