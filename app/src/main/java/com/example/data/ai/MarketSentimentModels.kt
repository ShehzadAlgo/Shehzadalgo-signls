package com.example.data.ai

import com.example.data.market.MarketNewsItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.math.roundToInt

enum class SentimentRegime(val wire: String, val displayLabel: String) {
    EXTREME_FEAR("EXTREME_FEAR", "EXTREME FEAR"),
    RISK_OFF_BEARISH("RISK_OFF_BEARISH", "RISK-OFF BEARISH"),
    NEUTRAL_CAUTIOUS("NEUTRAL_CAUTIOUS", "NEUTRAL / MIXED"),
    RISK_ON_BULLISH("RISK_ON_BULLISH", "RISK-ON BULLISH"),
    EUPHORIA_OVERHEATED("EUPHORIA_OVERHEATED", "EUPHORIA / OVERHEATED");

    companion object {
        fun fromWire(raw: String): SentimentRegime =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
                ?: when {
                    raw.contains("EXTREME", ignoreCase = true) && raw.contains("FEAR", ignoreCase = true) -> EXTREME_FEAR
                    raw.contains("BEAR", ignoreCase = true) || raw.contains("RISK_OFF", ignoreCase = true) -> RISK_OFF_BEARISH
                    raw.contains("EUPHORIA", ignoreCase = true) || raw.contains("GREED", ignoreCase = true) -> EUPHORIA_OVERHEATED
                    raw.contains("BULL", ignoreCase = true) || raw.contains("RISK_ON", ignoreCase = true) -> RISK_ON_BULLISH
                    else -> NEUTRAL_CAUTIOUS
                }
    }
}

enum class RiskPostureRecommendation(
    val wire: String,
    val displayLabel: String,
    val riskScalar: Double,
    val defaultAtrBias: Double
) {
    DEFENSIVE_HALVE("DEFENSIVE_HALVE", "DEFENSIVE • 0.50x RISK", 0.50, 2.5),
    CAUTIOUS_REDUCE("CAUTIOUS_REDUCE", "CAUTIOUS • 0.75x RISK", 0.75, 2.5),
    NORMAL_DEPLOY("NORMAL_DEPLOY", "STANDARD • 1.00x RISK", 1.00, 2.0),
    TIGHT_TRAILING("TIGHT_TRAILING", "EXTENDED • 0.75x TIGHT SL", 0.75, 1.5);

    companion object {
        fun fromWire(raw: String): RiskPostureRecommendation =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
                ?: when {
                    raw.contains("DEFENSIVE", ignoreCase = true) || raw.contains("HALVE", ignoreCase = true) -> DEFENSIVE_HALVE
                    raw.contains("CAUTIOUS", ignoreCase = true) || raw.contains("REDUCE", ignoreCase = true) -> CAUTIOUS_REDUCE
                    raw.contains("TIGHT", ignoreCase = true) -> TIGHT_TRAILING
                    else -> NORMAL_DEPLOY
                }
    }
}

data class MarketSentimentReport(
    val overallScore: Int,
    val cryptoScore: Int,
    val forexScore: Int,
    val regime: SentimentRegime,
    val riskPosture: RiskPostureRecommendation,
    val recommendedRiskMultiplier: Double,
    val atrMultiplierBias: Double,
    val keyDrivers: List<String>,
    val riskWarning: String,
    val headlineSummary: String,
    val analyzedHeadlines: List<MarketNewsItem>,
    val providerName: String,
    val modelName: String,
    val updatedAtUtc: String
) {
    /** Maps signed [-100..+100] score to [0..100] Fear & Greed dial index. */
    val gaugeIndex0To100: Int
        get() = ((overallScore.coerceIn(-100, 100) + 100) / 2.0).roundToInt().coerceIn(0, 100)
}

sealed interface MarketSentimentState {
    data object Idle : MarketSentimentState
    data object Analyzing : MarketSentimentState
    data class Success(val report: MarketSentimentReport) : MarketSentimentState
    data class Error(val message: String) : MarketSentimentState
}

object MarketSentimentParser {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parseJson(
        rawJson: String,
        headlines: List<MarketNewsItem>,
        providerName: String,
        modelName: String,
        updatedAtUtc: String
    ): MarketSentimentReport {
        val cleaned = extractJsonObject(rawJson)
        val map = mapAdapter.fromJson(cleaned)
            ?: throw IllegalStateException("Gemini sentiment response was empty or invalid JSON.")

        val overall = ((map["overallScore"] as? Number)?.toInt() ?: 0).coerceIn(-100, 100)
        val crypto = ((map["cryptoScore"] as? Number)?.toInt() ?: overall).coerceIn(-100, 100)
        val forex = ((map["forexScore"] as? Number)?.toInt() ?: overall).coerceIn(-100, 100)

        val regimeRaw = (map["regime"] as? String).orEmpty()
        val regime = if (regimeRaw.isNotBlank()) {
            SentimentRegime.fromWire(regimeRaw)
        } else {
            when {
                overall <= -60 -> SentimentRegime.EXTREME_FEAR
                overall <= -20 -> SentimentRegime.RISK_OFF_BEARISH
                overall >= 60 -> SentimentRegime.EUPHORIA_OVERHEATED
                overall >= 20 -> SentimentRegime.RISK_ON_BULLISH
                else -> SentimentRegime.NEUTRAL_CAUTIOUS
            }
        }

        val postureRaw = (map["riskPosture"] as? String).orEmpty()
        val posture = if (postureRaw.isNotBlank()) {
            RiskPostureRecommendation.fromWire(postureRaw)
        } else {
            when (regime) {
                SentimentRegime.EXTREME_FEAR -> RiskPostureRecommendation.DEFENSIVE_HALVE
                SentimentRegime.RISK_OFF_BEARISH -> RiskPostureRecommendation.CAUTIOUS_REDUCE
                SentimentRegime.EUPHORIA_OVERHEATED -> RiskPostureRecommendation.TIGHT_TRAILING
                else -> RiskPostureRecommendation.NORMAL_DEPLOY
            }
        }

        // Safety invariant: AI sentiment can NEVER increase risk above 1.0x user cap
        val recRiskMult = ((map["recommendedRiskMultiplier"] as? Number)?.toDouble() ?: posture.riskScalar)
            .coerceIn(0.25, 1.0)
        val recAtrBias = ((map["atrMultiplierBias"] as? Number)?.toDouble() ?: posture.defaultAtrBias)
            .coerceIn(1.0, 3.0)

        val drivers = (map["keyDrivers"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
            .ifEmpty {
                headlines.take(3).map { "[${it.category}] ${it.title}" }
            }

        val riskWarning = (map["riskWarning"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Adjust risk sizing conservatively during macro/crypto headline volatility."

        val summary = (map["headlineSummary"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Processed ${headlines.size} live crypto/forex headlines."

        return MarketSentimentReport(
            overallScore = overall,
            cryptoScore = crypto,
            forexScore = forex,
            regime = regime,
            riskPosture = posture,
            recommendedRiskMultiplier = recRiskMult,
            atrMultiplierBias = recAtrBias,
            keyDrivers = drivers,
            riskWarning = riskWarning,
            headlineSummary = summary,
            analyzedHeadlines = headlines,
            providerName = providerName,
            modelName = modelName,
            updatedAtUtc = updatedAtUtc
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
        throw IllegalStateException("AI sentiment output was not valid JSON.")
    }
}
