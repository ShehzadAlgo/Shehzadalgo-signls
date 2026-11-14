package com.example.data.firestore

import com.example.domain.model.RiskConfig
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class RiskConfigRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val accountBalanceUsd: Double = 10_000.0,
    val riskPerTradePct: Double = 0.5,
    val maxOpenRiskPct: Double = 3.0,
    val maxClusterRiskPct: Double = 1.5,
    val maxOpenPerInstrument: Long = 1L,
    val dailyLossLimitPct: Double = 2.0,
    val weeklyLossLimitPct: Double = 4.0,
    val drawdownHalvePct: Double = 5.0,
    val drawdownHaltPct: Double = 8.0,
    val maxCostToStopRatio: Double = 0.15,
    val minLiquidationStops: Long = 3L,
    val newsBlackoutBeforeMin: Long = 30L,
    val newsBlackoutAfterMin: Long = 15L,
    val feedMaxLagSeconds: Long = 60L,
    val manualKillSwitch: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomain(): RiskConfig = RiskConfig(
        accountBalanceUsd = accountBalanceUsd,
        riskPerTradePct = riskPerTradePct,
        maxOpenRiskPct = maxOpenRiskPct,
        maxClusterRiskPct = maxClusterRiskPct,
        maxOpenPerInstrument = maxOpenPerInstrument.toInt(),
        dailyLossLimitPct = dailyLossLimitPct,
        weeklyLossLimitPct = weeklyLossLimitPct,
        drawdownHalvePct = drawdownHalvePct,
        drawdownHaltPct = drawdownHaltPct,
        maxCostToStopRatio = maxCostToStopRatio,
        minLiquidationStops = minLiquidationStops.toInt(),
        newsBlackoutBeforeMin = newsBlackoutBeforeMin.toInt(),
        newsBlackoutAfterMin = newsBlackoutAfterMin.toInt(),
        feedMaxLagSeconds = feedMaxLagSeconds.toInt(),
        manualKillSwitch = manualKillSwitch
    )

    fun toWriteMap(uid: String, existingCreatedAt: Timestamp? = null): Map<String, Any> = mapOf(
        "userId" to uid,
        "accountBalanceUsd" to accountBalanceUsd,
        "riskPerTradePct" to riskPerTradePct,
        "maxOpenRiskPct" to maxOpenRiskPct,
        "maxClusterRiskPct" to maxClusterRiskPct,
        "maxOpenPerInstrument" to maxOpenPerInstrument,
        "dailyLossLimitPct" to dailyLossLimitPct,
        "weeklyLossLimitPct" to weeklyLossLimitPct,
        "drawdownHalvePct" to drawdownHalvePct,
        "drawdownHaltPct" to drawdownHaltPct,
        "maxCostToStopRatio" to maxCostToStopRatio,
        "minLiquidationStops" to minLiquidationStops,
        "newsBlackoutBeforeMin" to newsBlackoutBeforeMin,
        "newsBlackoutAfterMin" to newsBlackoutAfterMin,
        "feedMaxLagSeconds" to feedMaxLagSeconds,
        "manualKillSwitch" to manualKillSwitch,
        "createdAt" to (existingCreatedAt ?: FieldValue.serverTimestamp()),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    companion object {
        fun fromDomain(uid: String, config: RiskConfig): RiskConfigRecord = RiskConfigRecord(
            id = "default",
            userId = uid,
            accountBalanceUsd = config.accountBalanceUsd,
            riskPerTradePct = config.riskPerTradePct,
            maxOpenRiskPct = config.maxOpenRiskPct,
            maxClusterRiskPct = config.maxClusterRiskPct,
            maxOpenPerInstrument = config.maxOpenPerInstrument.toLong(),
            dailyLossLimitPct = config.dailyLossLimitPct,
            weeklyLossLimitPct = config.weeklyLossLimitPct,
            drawdownHalvePct = config.drawdownHalvePct,
            drawdownHaltPct = config.drawdownHaltPct,
            maxCostToStopRatio = config.maxCostToStopRatio,
            minLiquidationStops = config.minLiquidationStops.toLong(),
            newsBlackoutBeforeMin = config.newsBlackoutBeforeMin.toLong(),
            newsBlackoutAfterMin = config.newsBlackoutAfterMin.toLong(),
            feedMaxLagSeconds = config.feedMaxLagSeconds.toLong(),
            manualKillSwitch = config.manualKillSwitch
        )
    }
}

@IgnoreExtraProperties
data class SignalRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val signalUuid: String = "",
    val createdAtIso: String = "",
    val instrument: String = "",
    val assetClass: String = "fx",
    val direction: String = "long",
    val strategy: String = "",
    val strategyVersion: String = "1.0.0",
    val configHash: String = "",
    val timeframe: String = "1H",
    val regime: String = "TREND",
    val conviction: Double = 0.5,
    val entryType: String = "zone",
    val entryLow: Double = 0.0,
    val entryHigh: Double = 0.0,
    val stopLoss: Double = 0.0,
    val tp1Price: Double = 0.0,
    val tp1R: Double = 1.5,
    val tp1ClosePct: Long = 50L,
    val tp2Price: Double = 0.0,
    val tp2R: Double = 3.0,
    val tp2ClosePct: Long = 50L,
    val suggestedRiskPct: Double = 0.5,
    val estCostR: Double = 0.05,
    val expiresAtIso: String = "",
    val invalidation: String = "",
    val rationale: String = "",
    val riskStatus: String = "APPROVED",
    val riskAdjustments: List<String> = emptyList(),
    val riskReasons: List<String> = emptyList(),
    val idempotencyKey: String = "",
    val executionMode: String = "PAPER",
    val status: String = "PENDING",
    val realizedR: Double = 0.0,
    val maeR: Double = 0.0,
    val mfeR: Double = 0.0,
    val durationMinutes: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "signalUuid" to signalUuid,
        "createdAtIso" to createdAtIso,
        "instrument" to instrument,
        "assetClass" to assetClass,
        "direction" to direction,
        "strategy" to strategy,
        "strategyVersion" to strategyVersion,
        "configHash" to configHash,
        "timeframe" to timeframe,
        "regime" to regime,
        "conviction" to conviction,
        "entryType" to entryType,
        "entryLow" to entryLow,
        "entryHigh" to entryHigh,
        "stopLoss" to stopLoss,
        "tp1Price" to tp1Price,
        "tp1R" to tp1R,
        "tp1ClosePct" to tp1ClosePct,
        "tp2Price" to tp2Price,
        "tp2R" to tp2R,
        "tp2ClosePct" to tp2ClosePct,
        "suggestedRiskPct" to suggestedRiskPct,
        "estCostR" to estCostR,
        "expiresAtIso" to expiresAtIso,
        "invalidation" to invalidation,
        "rationale" to rationale,
        "riskStatus" to riskStatus,
        "riskAdjustments" to riskAdjustments,
        "riskReasons" to riskReasons,
        "idempotencyKey" to idempotencyKey,
        "executionMode" to executionMode,
        "status" to status,
        "realizedR" to realizedR,
        "maeR" to maeR,
        "mfeR" to mfeR,
        "durationMinutes" to durationMinutes,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class SignalEventRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val signalId: String = "",
    val sequenceIndex: Long = 0L,
    val eventType: String = "",
    val timestampIso: String = "",
    val payloadSummary: String = "",
    val prevHash: String = "",
    val eventHash: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    fun toCreateMap(uid: String): Map<String, Any> = mapOf(
        "userId" to uid,
        "signalId" to signalId,
        "sequenceIndex" to sequenceIndex,
        "eventType" to eventType,
        "timestampIso" to timestampIso,
        "payloadSummary" to payloadSummary,
        "prevHash" to prevHash,
        "eventHash" to eventHash,
        "createdAt" to FieldValue.serverTimestamp()
    )
}

@IgnoreExtraProperties
data class ValidationReportRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val strategyId: String = "",
    val strategyVersion: String = "1.0.0",
    val configHash: String = "",
    val dataSnapshotId: String = "",
    val randomSeed: Long = 42L,
    val lifecycleStatus: String = "DRAFT",
    val trialCount: Long = 1L,
    val oosTrades: Long = 0L,
    val netExpectancyR: Double = 0.0,
    val profitFactor: Double = 0.0,
    val sharpe: Double = 0.0,
    val deflatedSharpe: Double = 0.0,
    val pbo: Double = 0.5,
    val mc95DrawdownPct: Double = 0.0,
    val ruinProbPct: Double = 0.0,
    val positiveYearsPct: Double = 0.0,
    val causalityPassed: Boolean = true,
    val beatRandom95th: Boolean = false,
    val failureReasons: List<String> = emptyList(),
    val equityCurveR: List<Double> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toWriteMap(uid: String, existingCreatedAt: Timestamp? = null): Map<String, Any> = mapOf(
        "userId" to uid,
        "strategyId" to strategyId,
        "strategyVersion" to strategyVersion,
        "configHash" to configHash,
        "dataSnapshotId" to dataSnapshotId,
        "randomSeed" to randomSeed,
        "lifecycleStatus" to lifecycleStatus,
        "trialCount" to trialCount,
        "oosTrades" to oosTrades,
        "netExpectancyR" to netExpectancyR,
        "profitFactor" to profitFactor,
        "sharpe" to sharpe,
        "deflatedSharpe" to deflatedSharpe,
        "pbo" to pbo,
        "mc95DrawdownPct" to mc95DrawdownPct,
        "ruinProbPct" to ruinProbPct,
        "positiveYearsPct" to positiveYearsPct,
        "causalityPassed" to causalityPassed,
        "beatRandom95th" to beatRandom95th,
        "failureReasons" to failureReasons,
        "equityCurveR" to equityCurveR.take(500),
        "createdAt" to (existingCreatedAt ?: FieldValue.serverTimestamp()),
        "updatedAt" to FieldValue.serverTimestamp()
    )
}
