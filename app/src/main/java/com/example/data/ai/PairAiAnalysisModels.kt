package com.example.data.ai

import com.example.domain.model.Candle
import com.example.domain.model.Timeframe
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

enum class PairBias(val wire: String, val displayLabel: String) {
    BULLISH("BULLISH", "BULLISH SETUP"),
    BEARISH("BEARISH", "BEARISH SETUP"),
    NEUTRAL_WAIT("NEUTRAL_WAIT", "NEUTRAL • WAIT");

    companion object {
        fun fromWire(raw: String): PairBias =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
                ?: when {
                    raw.contains("BULL", ignoreCase = true) || raw.contains("LONG", ignoreCase = true) || raw.contains("BUY", ignoreCase = true) -> BULLISH
                    raw.contains("BEAR", ignoreCase = true) || raw.contains("SHORT", ignoreCase = true) || raw.contains("SELL", ignoreCase = true) -> BEARISH
                    else -> NEUTRAL_WAIT
                }
    }
}

data class AiPairAnalysisResult(
    val symbol: String,
    val displayName: String,
    val assetClassLabel: String,
    val timeframe: Timeframe,
    val feedProvider: String,
    val currentPrice: Double,
    val bias: PairBias,
    val confidenceScore: Int,
    val regime: String,
    val suggestedEntryLow: Double,
    val suggestedEntryHigh: Double,
    val volatilityAdjustedStopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val riskRewardRatio: Double,
    val atr14: Double,
    val rsi14: Double?,
    val adx14: Double?,
    val trendSummary: String,
    val riskEngineSizingSummary: String,
    val technicalThesis: String,
    val actionChecklist: List<String>,
    val keyRisks: List<String>,
    val traderSummary: String,
    val candlesUsedCount: Int,
    val providerName: String,
    val modelName: String,
    val analyzedAtUtc: String
)

sealed interface PairAiAnalysisState {
    data object Idle : PairAiAnalysisState
    data class Analyzing(val symbol: String, val timeframe: Timeframe) : PairAiAnalysisState
    data class Success(val result: AiPairAnalysisResult, val candles: List<Candle>) : PairAiAnalysisState
    data class Error(val symbol: String, val message: String) : PairAiAnalysisState
}

object PairAnalysisParser {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parseJson(
        rawJson: String,
        symbol: String,
        displayName: String,
        assetClassLabel: String,
        timeframe: Timeframe,
        feedProvider: String,
        currentPrice: Double,
        atr14: Double,
        rsi14: Double?,
        adx14: Double?,
        fallbackEntryLow: Double,
        fallbackEntryHigh: Double,
        fallbackStopLoss: Double,
        fallbackTp1: Double,
        fallbackTp2: Double,
        fallbackSizingSummary: String,
        candlesUsedCount: Int,
        providerName: String,
        modelName: String,
        analyzedAtUtc: String
    ): AiPairAnalysisResult {
        val cleaned = extractJsonObject(rawJson)
        val map = mapAdapter.fromJson(cleaned)
            ?: throw IllegalStateException("Gemini pair analysis response was empty or invalid JSON.")

        val bias = PairBias.fromWire((map["bias"] as? String).orEmpty())
        val confidence = ((map["confidenceScore"] as? Number)?.toInt() ?: 50).coerceIn(0, 100)
        val regime = (map["regime"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "MIXED"

        val entryLow = (map["suggestedEntryLow"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: fallbackEntryLow
        val entryHigh = (map["suggestedEntryHigh"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: fallbackEntryHigh
        val stopLoss = (map["volatilityAdjustedStopLoss"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: fallbackStopLoss
        val tp1 = (map["takeProfit1"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: fallbackTp1
        val tp2 = (map["takeProfit2"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: fallbackTp2

        val entryMid = (entryLow + entryHigh) / 2.0
        val stopDist = kotlin.math.abs(entryMid - stopLoss).coerceAtLeast(1e-9)
        val computedRr = kotlin.math.abs(tp1 - entryMid) / stopDist
        val rr = ((map["riskRewardRatio"] as? Number)?.toDouble()?.takeIf { it > 0.0 } ?: computedRr).coerceIn(0.1, 20.0)

        val trendSummary = (map["trendSummary"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "EMA & structure evaluation computed from closed bars."
        val sizingSummary = (map["riskEngineSizingSummary"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackSizingSummary
        val technicalThesis = (map["technicalThesis"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Review volatility-adjusted entry, stop, and RSI/ADX alignment before execution."

        val checklist = (map["actionChecklist"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
            .ifEmpty { listOf("Confirm closed-bar structure before entry", "Respect Risk Engine stop loss") }

        val keyRisks = (map["keyRisks"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
            .ifEmpty { listOf("Account for spread/slippage and upcoming macro releases") }

        val summary = (map["traderSummary"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "$symbol $timeframe AI analysis ready (informational only, not financial advice)."

        return AiPairAnalysisResult(
            symbol = symbol,
            displayName = displayName,
            assetClassLabel = assetClassLabel,
            timeframe = timeframe,
            feedProvider = feedProvider,
            currentPrice = currentPrice,
            bias = bias,
            confidenceScore = confidence,
            regime = regime,
            suggestedEntryLow = minOf(entryLow, entryHigh),
            suggestedEntryHigh = maxOf(entryLow, entryHigh),
            volatilityAdjustedStopLoss = stopLoss,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            riskRewardRatio = rr,
            atr14 = atr14,
            rsi14 = rsi14,
            adx14 = adx14,
            trendSummary = trendSummary,
            riskEngineSizingSummary = sizingSummary,
            technicalThesis = technicalThesis,
            actionChecklist = checklist,
            keyRisks = keyRisks,
            traderSummary = summary,
            candlesUsedCount = candlesUsedCount,
            providerName = providerName,
            modelName = modelName,
            analyzedAtUtc = analyzedAtUtc
        )
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        throw IllegalStateException("AI pair analysis output was not valid JSON.")
    }
}
