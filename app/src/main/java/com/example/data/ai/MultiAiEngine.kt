package com.example.data.ai

import android.util.Log
import com.example.data.firestore.SignalRecord
import com.example.data.market.MarketFeedBundle
import com.example.data.market.MarketNewsItem
import com.example.domain.model.AssetClass
import com.example.domain.model.Direction
import com.example.domain.model.EconomicEvent
import com.example.domain.model.RiskConfig
import com.example.quant.Indicators
import com.example.quant.MarketAnalyticsSnapshot
import com.example.quant.RiskEngine
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

    private val verificationSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "verdict" to Schema.enumeration(
                    values = listOf("HIGH_CONVICTION", "PROCEED_WITH_CAUTION", "WAIT_OR_SKIP"),
                    description = "Pro trader signal verification verdict"
                ),
                "confidenceScore" to Schema.integer(
                    description = "Trader confidence score from 0 to 100 based strictly on supplied analytics and risk"
                ),
                "marketStructureAssessment" to Schema.string(
                    description = "Concise evaluation of EMA20/50/200 stack, ADX trend strength, RSI, ATR regime, and swing levels"
                ),
                "entryAndStopAudit" to Schema.string(
                    description = "Audit of entry zone, stop loss ATR distance, TP1/TP2 reward-to-risk, and spread/slippage cost ratio"
                ),
                "traderRiskAssessment" to Schema.string(
                    description = "Evaluation of dollar risk, lot/contract size, safe leverage, and portfolio currency/beta exposure"
                ),
                "executionChecklist" to Schema.array(
                    items = Schema.string(),
                    description = "3 to 4 actionable execution and trade-management steps for a disciplined trader"
                ),
                "keyRiskFlags" to Schema.array(
                    items = Schema.string(),
                    description = "Specific risk warnings or structural caveats found in the data"
                ),
                "traderSummary" to Schema.string(
                    description = "2-sentence institutional trader summary with informational disclaimer"
                )
            )
        )
    }

    private val geminiVerifierDefaultModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = verificationSchema
            },
            systemInstruction = content { text(TRADER_VERIFIER_SYSTEM_PROMPT) }
        )
    }

    private val geminiVerifierLiteModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = verificationSchema
            },
            systemInstruction = content { text(TRADER_VERIFIER_SYSTEM_PROMPT) }
        )
    }

    private val sentimentSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "overallScore" to Schema.integer(
                    description = "Composite Crypto+Forex market sentiment score from -100 (Extreme Fear) to +100 (Euphoria)"
                ),
                "cryptoScore" to Schema.integer(
                    description = "Crypto market sentiment score from -100 to +100 based on crypto headlines"
                ),
                "forexScore" to Schema.integer(
                    description = "Forex/Macro risk sentiment score from -100 (Risk-off/Panic) to +100 (Risk-on) based on FX/central bank headlines"
                ),
                "regime" to Schema.enumeration(
                    values = listOf(
                        "EXTREME_FEAR",
                        "RISK_OFF_BEARISH",
                        "NEUTRAL_CAUTIOUS",
                        "RISK_ON_BULLISH",
                        "EUPHORIA_OVERHEATED"
                    ),
                    description = "Discrete macro/crypto sentiment regime"
                ),
                "riskPosture" to Schema.enumeration(
                    values = listOf(
                        "DEFENSIVE_HALVE",
                        "CAUTIOUS_REDUCE",
                        "NORMAL_DEPLOY",
                        "TIGHT_TRAILING"
                    ),
                    description = "Recommended Risk Engine posture scaling"
                ),
                "recommendedRiskMultiplier" to Schema.double(
                    description = "Defensive position risk scalar between 0.25 and 1.00 (never > 1.0)"
                ),
                "atrMultiplierBias" to Schema.double(
                    description = "Volatility-adjusted ATR stop multiplier between 1.5 and 3.0"
                ),
                "keyDrivers" to Schema.array(
                    items = Schema.string(),
                    description = "Top 3-4 concise market catalysts extracted directly from the headlines"
                ),
                "riskWarning" to Schema.string(
                    description = "1-2 sentence Risk Engine implication covering stops, event risk, or portfolio heat"
                ),
                "headlineSummary" to Schema.string(
                    description = "Concise synthesis of current Crypto & Forex macro tape (informational only)"
                )
            )
        )
    }

    private val geminiSentimentDefaultModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = sentimentSchema
            },
            systemInstruction = content { text(SENTIMENT_SYSTEM_PROMPT) }
        )
    }

    private val geminiSentimentLiteModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = sentimentSchema
            },
            systemInstruction = content { text(SENTIMENT_SYSTEM_PROMPT) }
        )
    }

    private val pairAnalysisSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "bias" to Schema.enumeration(
                    values = listOf("BULLISH", "BEARISH", "NEUTRAL_WAIT"),
                    description = "Directional institutional bias based strictly on supplied closed-bar indicators"
                ),
                "confidenceScore" to Schema.integer(
                    description = "Setup conviction 0-100 based on trend, ADX, RSI, and volatility structure"
                ),
                "regime" to Schema.string(
                    description = "Current volatility & trend regime label (e.g., TRENDING BULLISH, RANGE, HIGH VOL BREAKOUT)"
                ),
                "suggestedEntryLow" to Schema.double(description = "Lower bound of realistic entry zone"),
                "suggestedEntryHigh" to Schema.double(description = "Upper bound of realistic entry zone"),
                "volatilityAdjustedStopLoss" to Schema.double(description = "ATR-based invalidation stop loss price"),
                "takeProfit1" to Schema.double(description = "Primary target around 1.5R to 2.0R"),
                "takeProfit2" to Schema.double(description = "Extended target around 3.0R to 4.0R"),
                "riskRewardRatio" to Schema.double(description = "Reward-to-risk ratio to TP1"),
                "trendSummary" to Schema.string(description = "Concise summary of EMA20/50/200 alignment, ADX, RSI, and 20-bar range"),
                "riskEngineSizingSummary" to Schema.string(description = "Exact position sizing and max risk evaluation"),
                "technicalThesis" to Schema.string(description = "Detailed multi-factor technical and price-action breakdown"),
                "actionChecklist" to Schema.array(
                    items = Schema.string(),
                    description = "3 to 4 actionable execution triggers and stop-management rules"
                ),
                "keyRisks" to Schema.array(
                    items = Schema.string(),
                    description = "Top 2 to 3 invalidation or macro/spread risks"
                ),
                "traderSummary" to Schema.string(description = "2-sentence institutional trader brief (informational only)")
            )
        )
    }

    private val geminiPairAnalyzerDefaultModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = pairAnalysisSchema
            },
            systemInstruction = content { text(PAIR_ANALYZER_SYSTEM_PROMPT) }
        )
    }

    private val geminiPairAnalyzerLiteModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = pairAnalysisSchema
            },
            systemInstruction = content { text(PAIR_ANALYZER_SYSTEM_PROMPT) }
        )
    }

    suspend fun analyzeAnyPairWithAi(
        provider: AiProviderType,
        modelName: String,
        apiKey: String,
        customEndpoint: String,
        bundle: MarketFeedBundle,
        riskConfig: RiskConfig,
        spotRates: Map<String, Double>,
        deskContext: String?
    ): AiPairAnalysisResult {
        val bars = bundle.candles.filter { it.isClosed }
        if (bars.size < 20) {
            throw IllegalStateException("Insufficient closed market bars (${bars.size}) returned for ${bundle.instrument.symbol}.")
        }
        val closes = bars.map { it.close }
        val last = bars.last()
        val idx = bars.lastIndex
        val ema20 = Indicators.ema(closes, 20).lastOrNull { it != null }
        val ema50 = Indicators.ema(closes, 50).lastOrNull { it != null }
        val ema200 = if (bars.size >= 200) Indicators.ema(closes, 200).lastOrNull { it != null } else null
        val atr14 = Indicators.atr(bars, 14).lastOrNull { it != null }
            ?: ((last.high - last.low).coerceAtLeast(last.close * 0.002))
        val rsi14 = Indicators.rsi(closes, 14).lastOrNull { it != null }
        val adx14 = Indicators.adx(bars, 14).lastOrNull { it != null }
        val recentSlice = bars.takeLast(20)
        val high20 = recentSlice.maxOf { it.high }
        val low20 = recentSlice.minOf { it.low }

        val preliminaryLong = (ema20 ?: last.close) >= (ema50 ?: last.close)
        val stopDist = atr14 * 2.0
        val entryLow = last.close - atr14 * 0.12
        val entryHigh = last.close + atr14 * 0.12
        val fallbackSl = if (preliminaryLong) {
            (last.close - stopDist).coerceAtLeast(bundle.instrument.tickSize)
        } else {
            last.close + stopDist
        }
        val fallbackTp1 = if (preliminaryLong) last.close + stopDist * 2.0 else (last.close - stopDist * 2.0).coerceAtLeast(bundle.instrument.tickSize)
        val fallbackTp2 = if (preliminaryLong) last.close + stopDist * 3.5 else (last.close - stopDist * 3.5).coerceAtLeast(bundle.instrument.tickSize)

        val sizing = RiskEngine.calculatePositionSize(
            instrument = bundle.instrument,
            accountBalanceUsd = riskConfig.accountBalanceUsd,
            riskPct = riskConfig.riskPerTradePct,
            entryPrice = last.close,
            stopLoss = fallbackSl,
            spotRates = spotRates,
            minLiquidationStops = riskConfig.minLiquidationStops
        )
        val sizingSummary = if (sizing.valid) {
            "At ${riskConfig.riskPerTradePct}% risk ($${"%.2f".format(sizing.riskAmountUsd)} on $${"%.0f".format(riskConfig.accountBalanceUsd)}): " +
                "${sizing.roundedSize} ${sizing.sizeUnitLabel} (Notional $${"%.0f".format(sizing.notionalUsd)})" +
                (sizing.suggestedMaxLeverage?.let { " | Max safe lev ${"%.1f".format(it)}x" } ?: "")
        } else {
            sizing.rejectionReason ?: "Adjust balance/stop distance to meet min lot ${bundle.instrument.minLot}."
        }

        val decimals = when {
            bundle.instrument.quoteCurrency == "JPY" -> 3
            bundle.instrument.assetClass == AssetClass.FX && last.close < 20.0 -> 5
            last.close < 5.0 -> 4
            else -> 2
        }
        val fmt: (Double) -> String = { "%.${decimals}f".format(it) }

        val analysisPrompt = buildString {
            appendLine("Perform a complete pro-trader quantitative + price-action analysis for ${bundle.instrument.symbol} (${bundle.instrument.displayName}) on ${bundle.timeframe.wire}.")
            appendLine("Feed Provider: ${bundle.providerName} | Closed Bars: ${bars.size}")
            appendLine("Current Close Price: ${fmt(last.close)} | 20-Bar Range: ${fmt(low20)} .. ${fmt(high20)}")
            appendLine("ATR(14): ${fmt(atr14)} (${"%.2f".format(atr14 / last.close * 100.0)}% of price)")
            appendLine("EMA20: ${ema20?.let(fmt) ?: "N/A"} | EMA50: ${ema50?.let(fmt) ?: "N/A"} | EMA200: ${ema200?.let(fmt) ?: "N/A"}")
            appendLine("RSI(14): ${rsi14?.let { "%.1f".format(it) } ?: "N/A"} | ADX(14): ${adx14?.let { "%.1f".format(it) } ?: "N/A"}")
            appendLine("Recent 6 Closed Candles (O/H/L/C):")
            bars.takeLast(6).forEachIndexed { idxBar, c ->
                appendLine("  Bar-${6 - idxBar}: O=${fmt(c.open)} H=${fmt(c.high)} L=${fmt(c.low)} C=${fmt(c.close)}")
            }
            appendLine("Risk Engine Volatility Baseline (${if (preliminaryLong) Direction.LONG.name else Direction.SHORT.name}, 2.0x ATR): Entry ${fmt(entryLow)}-${fmt(entryHigh)}, SL=${fmt(fallbackSl)}, TP1=${fmt(fallbackTp1)}, TP2=${fmt(fallbackTp2)}")
            appendLine("Risk Engine Sizing: $sizingSummary")
            if (!deskContext.isNullOrBlank()) {
                appendLine("Portfolio Context:")
                appendLine(deskContext)
            }
            appendLine("Return ONLY valid JSON matching the schema.")
        }

        val nowUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            .substringAfter('T').take(5) + " UTC"

        val effectiveProvider = if (provider == AiProviderType.FIREBASE_GEMINI || (provider.requiresApiKey && apiKey.isBlank())) {
            AiProviderType.FIREBASE_GEMINI
        } else {
            provider
        }
        val effectiveModel = if (effectiveProvider == AiProviderType.FIREBASE_GEMINI && provider != AiProviderType.FIREBASE_GEMINI) {
            AiProviderType.FIREBASE_GEMINI.defaultModel
        } else {
            modelName.ifBlank { effectiveProvider.defaultModel }
        }

        try {
            val rawJson = when (effectiveProvider) {
                AiProviderType.FIREBASE_GEMINI -> {
                    val model = if (effectiveModel == "gemini-3.5-flash-lite") {
                        geminiPairAnalyzerLiteModel
                    } else {
                        geminiPairAnalyzerDefaultModel
                    }
                    val response = withTimeout(AI_TIMEOUT_MS) {
                        model.generateContent(analysisPrompt)
                    }
                    response.text ?: throw IllegalStateException("Gemini pair analysis returned empty text.")
                }
                AiProviderType.ANTHROPIC_CLAUDE -> {
                    withTimeout(AI_TIMEOUT_MS) {
                        callAnthropic(
                            endpoint = effectiveProvider.defaultEndpoint,
                            apiKey = apiKey,
                            model = effectiveModel,
                            fullUserMessage = analysisPrompt,
                            history = emptyList()
                        )
                    }
                }
                else -> {
                    val endpoint = if (effectiveProvider == AiProviderType.CUSTOM_OPENAI) {
                        customEndpoint.trim().ifBlank { effectiveProvider.defaultEndpoint }
                    } else {
                        effectiveProvider.defaultEndpoint
                    }
                    withTimeout(AI_TIMEOUT_MS) {
                        callOpenAiCompatible(
                            provider = effectiveProvider,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = effectiveModel,
                            fullUserMessage = analysisPrompt,
                            history = emptyList()
                        )
                    }
                }
            }

            return PairAnalysisParser.parseJson(
                rawJson = rawJson,
                symbol = bundle.instrument.symbol,
                displayName = bundle.instrument.displayName,
                assetClassLabel = if (bundle.instrument.assetClass == AssetClass.CRYPTO) "CRYPTO" else "FX / MACRO",
                timeframe = bundle.timeframe,
                feedProvider = bundle.providerName,
                currentPrice = last.close,
                atr14 = atr14,
                rsi14 = rsi14,
                adx14 = adx14,
                fallbackEntryLow = entryLow,
                fallbackEntryHigh = entryHigh,
                fallbackStopLoss = fallbackSl,
                fallbackTp1 = fallbackTp1,
                fallbackTp2 = fallbackTp2,
                fallbackSizingSummary = sizingSummary,
                candlesUsedCount = bars.size,
                providerName = effectiveProvider.displayName,
                modelName = effectiveModel,
                analyzedAtUtc = nowUtc
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Pair AI analysis timed out for ${bundle.instrument.symbol}", e)
            throw IllegalStateException("AI pair analysis timed out after 60 seconds. Please retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pair AI analysis failed for ${bundle.instrument.symbol}", e)
            throw e
        }
    }

    suspend fun analyzeMarketSentimentWithAi(
        provider: AiProviderType,
        modelName: String,
        apiKey: String,
        customEndpoint: String,
        headlines: List<MarketNewsItem>,
        upcomingEvents: List<EconomicEvent>,
        riskConfig: RiskConfig,
        openRiskPct: Double
    ): MarketSentimentReport {
        val sentimentPrompt = buildString {
            appendLine("Analyze the real-time Crypto & Forex market headlines below to compute a quantitative market sentiment score and Risk Engine posture adjustment.")
            appendLine()
            appendLine("=== CURRENT RISK ENGINE STATE ===")
            appendLine("Open Portfolio Heat: ${"%.2f".format(openRiskPct)}% / ${riskConfig.maxOpenRiskPct}% max cap")
            appendLine("Base Risk/Trade: ${riskConfig.riskPerTradePct}% | Max Cluster Cap: ${riskConfig.maxClusterRiskPct}%")
            appendLine("Manual Kill Switch: ${riskConfig.manualKillSwitch}")
            if (upcomingEvents.isNotEmpty()) {
                appendLine("Upcoming Macro Calendar: ${upcomingEvents.take(3).joinToString { "${it.title} (${it.currency}, ${it.impact})" }}")
            }
            appendLine()
            appendLine("=== LIVE CRYPTO & FOREX HEADLINES (${headlines.size}) ===")
            headlines.forEachIndexed { idx, item ->
                appendLine("${idx + 1}. [${item.category}] ${item.title} (${item.source}, ${item.publishedAtIso.ifBlank { "recent" }})")
            }
            appendLine()
            appendLine("Return ONLY valid JSON matching the sentiment schema.")
        }

        val nowUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            .substringAfter('T').take(5) + " UTC"

        // Use Firebase Gemini directly unless an external API provider is explicitly configured with a key
        val effectiveProvider = if (provider == AiProviderType.FIREBASE_GEMINI || (provider.requiresApiKey && apiKey.isBlank())) {
            AiProviderType.FIREBASE_GEMINI
        } else {
            provider
        }
        val effectiveModel = if (effectiveProvider == AiProviderType.FIREBASE_GEMINI && provider != AiProviderType.FIREBASE_GEMINI) {
            AiProviderType.FIREBASE_GEMINI.defaultModel
        } else {
            modelName.ifBlank { effectiveProvider.defaultModel }
        }

        try {
            val rawJson = when (effectiveProvider) {
                AiProviderType.FIREBASE_GEMINI -> {
                    val model = if (effectiveModel == "gemini-3.5-flash-lite") {
                        geminiSentimentLiteModel
                    } else {
                        geminiSentimentDefaultModel
                    }
                    val response = withTimeout(AI_TIMEOUT_MS) {
                        model.generateContent(sentimentPrompt)
                    }
                    response.text ?: throw IllegalStateException("Gemini sentiment analysis returned empty text.")
                }
                AiProviderType.ANTHROPIC_CLAUDE -> {
                    withTimeout(AI_TIMEOUT_MS) {
                        callAnthropic(
                            endpoint = effectiveProvider.defaultEndpoint,
                            apiKey = apiKey,
                            model = effectiveModel,
                            fullUserMessage = sentimentPrompt,
                            history = emptyList()
                        )
                    }
                }
                else -> {
                    val endpoint = if (effectiveProvider == AiProviderType.CUSTOM_OPENAI) {
                        customEndpoint.trim().ifBlank { effectiveProvider.defaultEndpoint }
                    } else {
                        effectiveProvider.defaultEndpoint
                    }
                    withTimeout(AI_TIMEOUT_MS) {
                        callOpenAiCompatible(
                            provider = effectiveProvider,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = effectiveModel,
                            fullUserMessage = sentimentPrompt,
                            history = emptyList()
                        )
                    }
                }
            }

            return MarketSentimentParser.parseJson(
                rawJson = rawJson,
                headlines = headlines,
                providerName = effectiveProvider.displayName,
                modelName = effectiveModel,
                updatedAtUtc = nowUtc
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Market sentiment timed out for $effectiveProvider", e)
            throw IllegalStateException("Sentiment analysis timed out after 60 seconds. Please retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Market sentiment analysis failed", e)
            throw e
        }
    }

    suspend fun verifySignalWithAi(
        provider: AiProviderType,
        modelName: String,
        apiKey: String,
        customEndpoint: String,
        signal: SignalRecord,
        analytics: MarketAnalyticsSnapshot,
        deskContext: String?,
        signalKey: String
    ): AiSignalVerificationResult {
        val verificationPrompt = buildString {
            appendLine("Verify this trade signal as an institutional prop-desk trader using ONLY the real market analytics and risk numbers below.")
            appendLine()
            appendLine(analytics.toTraderPromptContext(signal))
            if (!deskContext.isNullOrBlank()) {
                appendLine()
                appendLine("=== DESK SNAPSHOT ===")
                appendLine(deskContext)
            }
            appendLine()
            appendLine("Return ONLY a valid JSON object with fields: verdict (HIGH_CONVICTION | PROCEED_WITH_CAUTION | WAIT_OR_SKIP), confidenceScore (0-100 int), marketStructureAssessment, entryAndStopAudit, traderRiskAssessment, executionChecklist (array of strings), keyRiskFlags (array of strings), traderSummary.")
        }

        val nowUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            .substringAfter('T').take(5) + " UTC"

        try {
            val rawJson = when (provider) {
                AiProviderType.FIREBASE_GEMINI -> {
                    val model = if (modelName == "gemini-3.5-flash-lite") {
                        geminiVerifierLiteModel
                    } else {
                        geminiVerifierDefaultModel
                    }
                    val response = withTimeout(AI_TIMEOUT_MS) {
                        model.generateContent(verificationPrompt)
                    }
                    response.text ?: throw IllegalStateException("Gemini verification returned an empty response.")
                }

                AiProviderType.ANTHROPIC_CLAUDE -> {
                    if (apiKey.isBlank()) {
                        throw IllegalStateException("Anthropic Claude requires an API key. Add it in Keys & Models or switch to Gemini Built-in.")
                    }
                    withTimeout(AI_TIMEOUT_MS) {
                        callAnthropic(
                            endpoint = provider.defaultEndpoint,
                            apiKey = apiKey,
                            model = modelName.ifBlank { provider.defaultModel },
                            fullUserMessage = verificationPrompt,
                            history = emptyList()
                        )
                    }
                }

                else -> {
                    if (provider.requiresApiKey && apiKey.isBlank()) {
                        throw IllegalStateException("${provider.displayName} requires an API key. Add your key in Keys & Models or switch to Gemini Built-in.")
                    }
                    val endpoint = when (provider) {
                        AiProviderType.CUSTOM_OPENAI -> customEndpoint.trim().ifBlank { provider.defaultEndpoint }
                        else -> provider.defaultEndpoint
                    }
                    withTimeout(AI_TIMEOUT_MS) {
                        callOpenAiCompatible(
                            provider = provider,
                            endpoint = endpoint,
                            apiKey = apiKey,
                            model = modelName.ifBlank { provider.defaultModel },
                            fullUserMessage = verificationPrompt,
                            history = emptyList()
                        )
                    }
                }
            }

            return AiVerificationParser.parseJson(
                rawJson = rawJson,
                signalKey = signalKey,
                instrument = signal.instrument,
                timeframe = signal.timeframe,
                direction = signal.direction,
                providerName = provider.displayName,
                modelName = modelName.ifBlank { provider.defaultModel },
                verifiedAtUtc = nowUtc
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Signal verification timed out for $provider", e)
            throw IllegalStateException("Signal verification timed out after 60 seconds. Please retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Signal verification failed for $provider", e)
            throw e
        }
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

        const val TRADER_VERIFIER_SYSTEM_PROMPT =
            "You are the Senior Risk & Signal Verification Officer at Shehzad Algo. " +
                "Audit every setup like a disciplined institutional trader: " +
                "1) Never invent or alter prices, indicator values, or lot sizes; analyze strictly the provided closed-bar analytics and Risk Engine outputs. " +
                "2) If RiskStatus is REJECTED, or hasDataQualityBlocker is true, or stopDistanceAtrMultiple < 0.6, or rewardToCostRatio < 4.0, reflect that directly with WAIT_OR_SKIP or PROCEED_WITH_CAUTION and lower confidence. " +
                "3) Never suggest widening stops, removing stop losses, martingale, or overriding risk limits. " +
                "4) Always output clean, concise trader insights (informational only, not financial advice)."

        const val SENTIMENT_SYSTEM_PROMPT =
            "You are the Macro & Crypto Sentiment Risk Officer at Shehzad Algo. " +
                "Score market sentiment strictly from the supplied live Crypto & Forex headlines and Risk Engine telemetry: " +
                "1) overallScore, cryptoScore, and forexScore range from -100 (panic/extreme fear) to +100 (euphoria/risk-on). " +
                "2) recommendedRiskMultiplier MUST stay in [0.25, 1.00] — never suggest increasing risk above 1.0x user limits. " +
                "3) When volatility, central-bank shock, geopolitical stress, or crypto liquidation headlines appear, recommend DEFENSIVE_HALVE or CAUTIOUS_REDUCE and wider ATR stop buffers (2.5x). " +
                "4) Keep drivers factual to the provided headlines (informational only, not financial advice)."

        const val PAIR_ANALYZER_SYSTEM_PROMPT =
            "You are the Chief Quantitative Strategist & Risk Officer at Shehzad Algo. " +
                "Analyze any requested FX, Metal, or Crypto pair using ONLY the supplied closed-bar OHLCV data, ATR(14), EMA20/50/200, RSI(14), ADX(14), and Risk Engine parameters. " +
                "Rules: " +
                "1) Never invent prices or fabricate indicators; anchor suggestedEntryLow, suggestedEntryHigh, volatilityAdjustedStopLoss, takeProfit1, and takeProfit2 strictly to the supplied price levels and ATR14. " +
                "2) If ADX < 18 or signals conflict, set bias = NEUTRAL_WAIT and lower confidenceScore accordingly. " +
                "3) Always recommend a defined volatility-adjusted stop loss (>= 1.2 ATR away) and never recommend martingale or averaging down. " +
                "4) Keep analysis concise, trader-ready, and explicitly informational only (not financial advice)."
    }
}
