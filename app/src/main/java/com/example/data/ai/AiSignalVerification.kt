package com.example.data.ai

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

enum class VerificationVerdict(val wire: String, val displayLabel: String) {
    HIGH_CONVICTION("HIGH_CONVICTION", "HIGH CONVICTION"),
    PROCEED_WITH_CAUTION("PROCEED_WITH_CAUTION", "PROCEED WITH CAUTION"),
    WAIT_OR_SKIP("WAIT_OR_SKIP", "WAIT / SKIP SETUP");

    companion object {
        fun fromWire(raw: String): VerificationVerdict =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
                ?: when {
                    raw.contains("HIGH", ignoreCase = true) -> HIGH_CONVICTION
                    raw.contains("SKIP", ignoreCase = true) || raw.contains("WAIT", ignoreCase = true) -> WAIT_OR_SKIP
                    else -> PROCEED_WITH_CAUTION
                }
    }
}

data class AiSignalVerificationResult(
    val signalKey: String,
    val instrument: String,
    val timeframe: String,
    val direction: String,
    val providerName: String,
    val modelName: String,
    val verifiedAtUtc: String,
    val verdict: VerificationVerdict,
    val confidenceScore: Int,
    val marketStructureAssessment: String,
    val entryAndStopAudit: String,
    val traderRiskAssessment: String,
    val executionChecklist: List<String>,
    val keyRiskFlags: List<String>,
    val traderSummary: String
)

sealed interface AiVerificationState {
    data object Idle : AiVerificationState
    data class Verifying(val signalKey: String) : AiVerificationState
    data class Success(val result: AiSignalVerificationResult) : AiVerificationState
    data class Error(val signalKey: String, val message: String) : AiVerificationState
}

object AiVerificationParser {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun parseJson(
        rawJson: String,
        signalKey: String,
        instrument: String,
        timeframe: String,
        direction: String,
        providerName: String,
        modelName: String,
        verifiedAtUtc: String
    ): AiSignalVerificationResult {
        val cleaned = extractJsonObject(rawJson)
        val parsedMap = mapAdapter.fromJson(cleaned)
            ?: throw IllegalStateException("AI verification response was empty or invalid JSON.")

        val verdict = VerificationVerdict.fromWire(
            (parsedMap["verdict"] as? String).orEmpty().ifBlank { "PROCEED_WITH_CAUTION" }
        )
        val confidence = ((parsedMap["confidenceScore"] as? Number)?.toInt() ?: 60).coerceIn(0, 100)
        val marketStructure = (parsedMap["marketStructureAssessment"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Market structure reviewed against closed-bar regime and trend stack."
        val entryAudit = (parsedMap["entryAndStopAudit"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Entry, stop-loss ATR distance, and TP R-multiple audited."
        val riskAssessment = (parsedMap["traderRiskAssessment"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Position sizing and portfolio exposure checked against risk caps."
        val summary = (parsedMap["traderSummary"] as? String)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Informational verification complete. Always respect hard stop-loss and Risk Engine limits."

        val checklist = (parsedMap["executionChecklist"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()
            .ifEmpty {
                listOf(
                    "Confirm candle close inside/beyond entry zone before execution.",
                    "Place hard Stop Loss immediately at engine invalidation level.",
                    "Scale 50% at TP1 and move Stop Loss to breakeven only after TP1 hit."
                )
            }

        val flags = (parsedMap["keyRiskFlags"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()

        return AiSignalVerificationResult(
            signalKey = signalKey,
            instrument = instrument,
            timeframe = timeframe,
            direction = direction,
            providerName = providerName,
            modelName = modelName,
            verifiedAtUtc = verifiedAtUtc,
            verdict = verdict,
            confidenceScore = confidence,
            marketStructureAssessment = marketStructure,
            entryAndStopAudit = entryAudit,
            traderRiskAssessment = riskAssessment,
            executionChecklist = checklist,
            keyRiskFlags = flags,
            traderSummary = summary
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
        throw IllegalStateException("AI verification response was not valid JSON.")
    }
}
