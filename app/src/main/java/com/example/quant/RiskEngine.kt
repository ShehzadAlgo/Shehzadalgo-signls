package com.example.quant

import com.example.domain.model.AssetClass
import com.example.domain.model.CandidateSignal
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Direction
import com.example.domain.model.EconomicEvent
import com.example.domain.model.Instrument
import com.example.domain.model.PositionSizingResult
import com.example.domain.model.RiskConfig
import com.example.domain.model.RiskDecision
import com.example.domain.model.RiskStatus
import kotlin.math.abs
import kotlin.math.floor

data class OpenExposureItem(
    val instrument: Instrument,
    val direction: Direction,
    val riskPct: Double,
    val betaToBtc: Double = 1.0
)

data class PortfolioRiskState(
    val openSignals: List<OpenExposureItem> = emptyList(),
    val realizedDailyR: Double = 0.0,
    val realizedWeeklyR: Double = 0.0,
    val currentDrawdownPct: Double = 0.0,
    val feedLagSeconds: Long = 0L,
    val blockingQualityIssues: List<DataQualityIssue> = emptyList(),
    val activeEvents: List<EconomicEvent> = emptyList(),
    val btcBetaBySymbol: Map<String, Double> = emptyMap()
)

data class CombinedCandidate(
    val candidate: CandidateSignal,
    val idempotencyKey: String,
    val decisionLog: String
)

object SignalCombiner {

    fun idempotencyKey(candidate: CandidateSignal): String =
        "${candidate.strategyId}|${candidate.instrument.symbol}|${candidate.timeframe.wire}|${candidate.barCloseEpochMs}"

    /**
     * Deduplicates by idempotency key and resolves opposite signals on the same instrument:
     * skips opposing signals unless one conviction dominates by >= 0.20.
     */
    fun combine(
        candidates: List<CandidateSignal>,
        existingIdempotencyKeys: Set<String>
    ): Pair<List<CombinedCandidate>, List<String>> {
        val logs = mutableListOf<String>()
        val fresh = candidates.filter { c ->
            val key = idempotencyKey(c)
            if (key in existingIdempotencyKeys) {
                logs.add("Skipped duplicate signal $key")
                false
            } else {
                true
            }
        }

        val grouped = fresh.groupBy { it.instrument.symbol }
        val accepted = mutableListOf<CombinedCandidate>()

        for ((symbol, group) in grouped) {
            val longs = group.filter { it.direction == Direction.LONG }
            val shorts = group.filter { it.direction == Direction.SHORT }
            if (longs.isNotEmpty() && shorts.isNotEmpty()) {
                val bestLong = longs.maxBy { it.conviction }
                val bestShort = shorts.maxBy { it.conviction }
                val diff = abs(bestLong.conviction - bestShort.conviction)
                if (diff < 0.20) {
                    logs.add("Conflict on $symbol: LONG (${bestLong.conviction}) vs SHORT (${bestShort.conviction}) skipped (margin < 0.20).")
                    continue
                } else {
                    val winner = if (bestLong.conviction > bestShort.conviction) bestLong else bestShort
                    logs.add("Conflict on $symbol resolved in favor of ${winner.direction} (${winner.strategyId}, conv=${"%.2f".format(winner.conviction)}).")
                    accepted.add(CombinedCandidate(winner, idempotencyKey(winner), "Dominant conflict winner"))
                }
            } else {
                val winner = group.maxBy { it.conviction }
                accepted.add(CombinedCandidate(winner, idempotencyKey(winner), "Uncontested candidate"))
            }
        }
        return accepted to logs
    }
}

object RiskEngine {

    /**
     * Converts 1 unit of quote currency P&L into USD account currency.
     * Supports direct USD quotes (EURUSD, XAUUSD, BTCUSDT), USD base pairs (USDJPY, USDCAD, USDCHF),
     * and cross pairs (EURJPY, GBPJPY) via live FX spot map (e.g. "USDJPY" -> 154.2).
     */
    fun quoteToUsdFactor(instrument: Instrument, spotRates: Map<String, Double>): Double {
        if (instrument.quoteCurrency == "USD" || instrument.quoteCurrency == "USDT") return 1.0
        val usdQuotePair = "USD${instrument.quoteCurrency}"
        val directRate = spotRates[usdQuotePair]
        if (directRate != null && directRate > 0.0) {
            return 1.0 / directRate
        }
        val quoteUsdPair = "${instrument.quoteCurrency}USD"
        val inverseRate = spotRates[quoteUsdPair]
        if (inverseRate != null && inverseRate > 0.0) {
            return inverseRate
        }
        // Conservative fallback reference rates if live rate unavailable
        return when (instrument.quoteCurrency) {
            "JPY" -> 1.0 / 150.0
            "CAD" -> 1.0 / 1.38
            "CHF" -> 1.0 / 0.88
            else -> 1.0
        }
    }

    fun calculatePositionSize(
        instrument: Instrument,
        accountBalanceUsd: Double,
        riskPct: Double,
        entryPrice: Double,
        stopLoss: Double,
        spotRates: Map<String, Double>,
        minLiquidationStops: Int = 3
    ): PositionSizingResult {
        val stopDist = abs(entryPrice - stopLoss)
        val stopPips = if (instrument.pipSize > 0.0) stopDist / instrument.pipSize else stopDist
        val riskAmountUsd = accountBalanceUsd * (riskPct / 100.0)
        val quoteToUsd = quoteToUsdFactor(instrument, spotRates)
        val unitValueInUsd = instrument.contractSize * quoteToUsd

        if (stopDist <= 0.0 || unitValueInUsd <= 0.0 || accountBalanceUsd <= 0.0) {
            return PositionSizingResult(
                instrument = instrument.symbol,
                assetClass = instrument.assetClass,
                accountBalanceUsd = accountBalanceUsd,
                riskPct = riskPct,
                riskAmountUsd = riskAmountUsd,
                stopDistancePrice = stopDist,
                stopPipsOrPoints = stopPips,
                unitValuePerPriceInUsd = unitValueInUsd,
                rawSize = 0.0,
                roundedSize = 0.0,
                sizeUnitLabel = if (instrument.assetClass == AssetClass.FX) "lots" else "contracts",
                notionalUsd = 0.0,
                suggestedMaxLeverage = null,
                estLiquidationDistanceStops = null,
                valid = false,
                rejectionReason = "Invalid stop distance or account balance."
            )
        }

        val rawSize = riskAmountUsd / (stopDist * unitValueInUsd)
        val steps = floor(rawSize / instrument.lotStep)
        val roundedSize = (steps * instrument.lotStep).let { kotlin.math.round(it * 1_000_000.0) / 1_000_000.0 }

        if (roundedSize < instrument.minLot) {
            return PositionSizingResult(
                instrument = instrument.symbol,
                assetClass = instrument.assetClass,
                accountBalanceUsd = accountBalanceUsd,
                riskPct = riskPct,
                riskAmountUsd = riskAmountUsd,
                stopDistancePrice = stopDist,
                stopPipsOrPoints = stopPips,
                unitValuePerPriceInUsd = unitValueInUsd,
                rawSize = rawSize,
                roundedSize = 0.0,
                sizeUnitLabel = if (instrument.assetClass == AssetClass.FX) "lots" else "contracts",
                notionalUsd = 0.0,
                suggestedMaxLeverage = null,
                estLiquidationDistanceStops = null,
                valid = false,
                rejectionReason = "Calculated size ${"%.4f".format(rawSize)} is below min lot ${instrument.minLot}."
            )
        }

        val notionalUsd = roundedSize * instrument.contractSize * entryPrice * quoteToUsd
        val stopFractionOfEntry = if (entryPrice > 0.0) stopDist / entryPrice else 0.0
        val maxLeverage = if (instrument.assetClass == AssetClass.CRYPTO && stopFractionOfEntry > 0.0) {
            // Liquidation distance >= minLiquidationStops * stopFraction => 1 / leverage >= minLiquidationStops * stopFraction
            (1.0 / (minLiquidationStops * stopFractionOfEntry)).coerceIn(1.0, 20.0)
        } else {
            null
        }
        val liqDistStops = if (maxLeverage != null && stopFractionOfEntry > 0.0) {
            (1.0 / maxLeverage) / stopFractionOfEntry
        } else {
            null
        }

        return PositionSizingResult(
            instrument = instrument.symbol,
            assetClass = instrument.assetClass,
            accountBalanceUsd = accountBalanceUsd,
            riskPct = riskPct,
            riskAmountUsd = riskAmountUsd,
            stopDistancePrice = stopDist,
            stopPipsOrPoints = stopPips,
            unitValuePerPriceInUsd = unitValueInUsd,
            rawSize = rawSize,
            roundedSize = roundedSize,
            sizeUnitLabel = if (instrument.assetClass == AssetClass.FX) "lots" else "contracts",
            notionalUsd = notionalUsd,
            suggestedMaxLeverage = maxLeverage,
            estLiquidationDistanceStops = liqDistStops,
            valid = true
        )
    }

    /**
     * Computes net currency exposure for FX (e.g. long EURUSD => +EUR, -USD)
     * and beta-weighted exposure to BTC for crypto perpetuals.
     */
    fun computeCurrencyNetExposurePct(openSignals: List<OpenExposureItem>): Map<String, Double> {
        val map = linkedMapOf<String, Double>()
        for (item in openSignals.filter { it.instrument.assetClass == AssetClass.FX }) {
            val sign = if (item.direction == Direction.LONG) 1.0 else -1.0
            map[item.instrument.baseCurrency] = (map[item.instrument.baseCurrency] ?: 0.0) + sign * item.riskPct
            map[item.instrument.quoteCurrency] = (map[item.instrument.quoteCurrency] ?: 0.0) - sign * item.riskPct
        }
        return map
    }

    fun computeCryptoClusterAndBetaExposure(
        openSignals: List<OpenExposureItem>,
        btcBetaBySymbol: Map<String, Double>
    ): Pair<Double, Map<String, Double>> {
        var netBtcBetaRisk = 0.0
        val clusterRisk = linkedMapOf<String, Double>()
        for (item in openSignals.filter { it.instrument.assetClass == AssetClass.CRYPTO }) {
            val sign = if (item.direction == Direction.LONG) 1.0 else -1.0
            val beta = btcBetaBySymbol[item.instrument.symbol] ?: item.betaToBtc
            netBtcBetaRisk += sign * item.riskPct * beta
            val cluster = item.instrument.cryptoCluster.ifBlank { "CRYPTO" }
            clusterRisk[cluster] = (clusterRisk[cluster] ?: 0.0) + item.riskPct
        }
        return netBtcBetaRisk to clusterRisk
    }

    fun evaluate(
        candidate: CandidateSignal,
        config: RiskConfig,
        portfolio: PortfolioRiskState,
        spotRates: Map<String, Double>,
        nowEpochMs: Long
    ): RiskDecision {
        val reasons = mutableListOf<String>()
        val adjustments = mutableListOf<String>()

        if (config.manualKillSwitch) {
            reasons.add("KILL_SWITCH_ACTIVE: Manual kill switch is engaged.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        if (portfolio.currentDrawdownPct >= config.drawdownHaltPct) {
            reasons.add("DRAWDOWN_HALT: Drawdown ${"%.1f".format(portfolio.currentDrawdownPct)}% >= ${config.drawdownHaltPct}% halt limit.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        val dailyLossPct = -portfolio.realizedDailyR * config.riskPerTradePct
        if (dailyLossPct >= config.dailyLossLimitPct) {
            reasons.add("DAILY_LOSS_LIMIT: Daily loss ${"%.2f".format(dailyLossPct)}% hit ${config.dailyLossLimitPct}% cap.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        val weeklyLossPct = -portfolio.realizedWeeklyR * config.riskPerTradePct
        if (weeklyLossPct >= config.weeklyLossLimitPct) {
            reasons.add("WEEKLY_LOSS_LIMIT: Weekly loss ${"%.2f".format(weeklyLossPct)}% hit ${config.weeklyLossLimitPct}% cap.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        val blockingForIns = portfolio.blockingQualityIssues.filter {
            it.instrument == candidate.instrument.symbol && it.blocksSignals
        }
        if (blockingForIns.isNotEmpty()) {
            reasons.add("DATA_QUALITY_BLOCK: ${blockingForIns.first().issueType.name} on ${candidate.instrument.symbol}.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        if (portfolio.feedLagSeconds > config.feedMaxLagSeconds) {
            reasons.add("STALE_FEED: Feed lag ${portfolio.feedLagSeconds}s > ${config.feedMaxLagSeconds}s.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        if (candidate.instrument.assetClass == AssetClass.FX) {
            if (!SessionClock.isFxMarketOpen(nowEpochMs)) {
                reasons.add("FX_WEEKEND_CLOSED: FX market is closed.")
                return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
            }
            if (SessionClock.isFxRolloverWindow(nowEpochMs)) {
                reasons.add("FX_ROLLOVER_WINDOW: Skipped during thin NY 17:00 rollover.")
                return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
            }
        }

        if (RegimeEngine.isInsideNewsBlackout(nowEpochMs, candidate.instrument, portfolio.activeEvents, config)) {
            reasons.add("NEWS_BLACKOUT: High-impact macro event within blackout window.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        if (candidate.estCostR > config.maxCostToStopRatio) {
            reasons.add("COST_TOO_HIGH: Est cost ${"%.2f".format(candidate.estCostR)}R > max ${config.maxCostToStopRatio}R.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        val openSameInstrument = portfolio.openSignals.count { it.instrument.symbol == candidate.instrument.symbol }
        if (openSameInstrument >= config.maxOpenPerInstrument) {
            reasons.add("MAX_PER_INSTRUMENT: Already $openSameInstrument open signal(s) on ${candidate.instrument.symbol}.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        var riskPct = config.riskPerTradePct
        if (portfolio.currentDrawdownPct >= config.drawdownHalvePct) {
            riskPct *= 0.5
            adjustments.add("DRAWDOWN_LADDER: Halved risk to ${"%.2f".format(riskPct)}% at ${"%.1f".format(portfolio.currentDrawdownPct)}% DD.")
        }

        val currentOpenRisk = portfolio.openSignals.sumOf { it.riskPct }
        val remainingPortfolioRisk = config.maxOpenRiskPct - currentOpenRisk
        if (remainingPortfolioRisk <= 0.05) {
            reasons.add("MAX_OPEN_RISK: Portfolio open risk ${"%.2f".format(currentOpenRisk)}% reached ${config.maxOpenRiskPct}% cap.")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }
        if (riskPct > remainingPortfolioRisk) {
            riskPct = remainingPortfolioRisk
            adjustments.add("PORTFOLIO_CAP_REDUCED: Clamped risk to ${"%.2f".format(riskPct)}% to stay within ${config.maxOpenRiskPct}% max open risk.")
        }

        // Currency leg / Crypto cluster netting check
        if (candidate.instrument.assetClass == AssetClass.FX) {
            val currentNet = computeCurrencyNetExposurePct(portfolio.openSignals)
            val sign = if (candidate.direction == Direction.LONG) 1.0 else -1.0
            val baseAfter = abs((currentNet[candidate.instrument.baseCurrency] ?: 0.0) + sign * riskPct)
            val quoteAfter = abs((currentNet[candidate.instrument.quoteCurrency] ?: 0.0) - sign * riskPct)
            val worstLeg = maxOf(baseAfter, quoteAfter)
            if (worstLeg > config.maxClusterRiskPct) {
                val available = (config.maxClusterRiskPct - maxOf(
                    abs(currentNet[candidate.instrument.baseCurrency] ?: 0.0),
                    abs(currentNet[candidate.instrument.quoteCurrency] ?: 0.0)
                )).coerceAtLeast(0.0)
                if (available < 0.15) {
                    reasons.add("CURRENCY_CAP: Net ${candidate.instrument.baseCurrency}/${candidate.instrument.quoteCurrency} risk exceeds ${config.maxClusterRiskPct}%.")
                    return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
                }
                riskPct = minOf(riskPct, available)
                adjustments.add("CURRENCY_NET_REDUCED: Reduced to ${"%.2f".format(riskPct)}% for currency cap.")
            }
        } else {
            val (netBtcBeta, clusterMap) = computeCryptoClusterAndBetaExposure(portfolio.openSignals, portfolio.btcBetaBySymbol)
            val beta = portfolio.btcBetaBySymbol[candidate.instrument.symbol] ?: 1.0
            val sign = if (candidate.direction == Direction.LONG) 1.0 else -1.0
            val projectedBeta = abs(netBtcBeta + sign * riskPct * beta)
            val clusterKey = candidate.instrument.cryptoCluster.ifBlank { "CRYPTO" }
            val projectedCluster = (clusterMap[clusterKey] ?: 0.0) + riskPct
            if (projectedBeta > config.maxClusterRiskPct * 1.5 || projectedCluster > config.maxClusterRiskPct) {
                reasons.add("CRYPTO_CLUSTER_CAP: Projected cluster/BTC-beta risk exceeds limit.")
                return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
            }
        }

        // Verify lot/contract rounding & min lot
        val entryMid = (candidate.entryLow + candidate.entryHigh) / 2.0
        val sizing = calculatePositionSize(
            instrument = candidate.instrument,
            accountBalanceUsd = config.accountBalanceUsd,
            riskPct = riskPct,
            entryPrice = entryMid,
            stopLoss = candidate.stopLoss,
            spotRates = spotRates,
            minLiquidationStops = config.minLiquidationStops
        )
        if (!sizing.valid) {
            reasons.add("SIZING_REJECTED: ${sizing.rejectionReason ?: "Below minimum lot"}")
            return RiskDecision(RiskStatus.REJECTED, 0.0, adjustments, reasons)
        }

        val finalStatus = if (adjustments.isNotEmpty()) RiskStatus.REDUCED else RiskStatus.APPROVED
        if (finalStatus == RiskStatus.APPROVED) {
            reasons.add("ALL_RISK_GATES_PASSED")
        } else {
            reasons.add("APPROVED_WITH_SIZE_REDUCTION")
        }
        return RiskDecision(finalStatus, riskPct, adjustments, reasons)
    }
}
