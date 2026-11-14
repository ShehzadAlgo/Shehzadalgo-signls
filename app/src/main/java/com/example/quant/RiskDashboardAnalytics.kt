package com.example.quant

import com.example.data.firestore.SignalRecord
import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.PositionSizingResult
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import kotlin.math.abs

data class VolatilityStopProfile(
    val atr14: Double,
    val atrPct: Double,
    val volatilityRegime: String,
    val multiplier: Double,
    val direction: Direction,
    val entryPrice: Double,
    val recommendedStopPrice: Double,
    val target2RPrice: Double,
    val stopDistancePrice: Double,
    val stopPipsOrPoints: Double,
    val trailStopSeries: List<Double>,
    val outerBandSeries: List<Double>,
    val hasLiveCandles: Boolean
)

data class SizingSensitivityPoint(
    val atrMultiplier: Double,
    val stopDistancePrice: Double,
    val stopPipsOrPoints: Double,
    val roundedSize: Double,
    val notionalUsd: Double,
    val maxSafeLeverage: Double?
)

data class ExposureBreakdown(
    val openItems: List<OpenExposureItem>,
    val openCount: Int,
    val openRiskPct: Double,
    val remainingPortfolioRiskPct: Double,
    val projectedRiskPct: Double,
    val worstFxLegAbsPct: Double,
    val fxNetExposurePct: Map<String, Double>,
    val cryptoNetBtcBetaPct: Double,
    val cryptoClusterRiskPct: Map<String, Double>,
    val realizedDailyLossPct: Double,
    val realizedWeeklyLossPct: Double,
    val currentDrawdownPct: Double
)

object RiskDashboardAnalytics {

    fun computeVolatilityStopProfile(
        instrument: Instrument,
        candles: List<Candle>,
        entryPrice: Double,
        fallbackStopPrice: Double,
        direction: Direction,
        atrMultiplier: Double
    ): VolatilityStopProfile {
        val closed = candles.filter { it.isClosed }
        val atrSeries = if (closed.size >= 15) Indicators.atr(closed, 14) else emptyList()
        val liveAtr = atrSeries.lastOrNull { it != null }
        val safeEntry = when {
            entryPrice > 0.0 -> entryPrice
            closed.isNotEmpty() -> closed.last().close
            else -> if (instrument.assetClass == AssetClass.CRYPTO) 100.0 else 1.0
        }

        val mult = atrMultiplier.coerceIn(0.5, 4.0)
        val effectiveAtr = when {
            liveAtr != null && liveAtr > 0.0 -> liveAtr
            fallbackStopPrice > 0.0 && abs(safeEntry - fallbackStopPrice) > 0.0 ->
                (abs(safeEntry - fallbackStopPrice) / mult).coerceAtLeast(instrument.pipSize * 5.0)
            else -> if (instrument.assetClass == AssetClass.CRYPTO) safeEntry * 0.012 else instrument.pipSize * 20.0
        }

        val atrPct = if (safeEntry > 0.0) (effectiveAtr / safeEntry) * 100.0 else 0.0
        val regimeLabel = when {
            atrPct >= 2.5 || (instrument.assetClass == AssetClass.FX && atrPct >= 0.65) -> "HIGH VOL"
            atrPct <= 0.45 && instrument.assetClass == AssetClass.CRYPTO -> "COMPRESSED"
            atrPct <= 0.15 && instrument.assetClass == AssetClass.FX -> "LOW VOL"
            else -> "NORMAL VOL"
        }

        val stopDistance = effectiveAtr * mult
        val recommendedStop = if (direction == Direction.LONG) {
            (safeEntry - stopDistance).coerceAtLeast(instrument.tickSize)
        } else {
            safeEntry + stopDistance
        }
        val target2R = if (direction == Direction.LONG) {
            safeEntry + stopDistance * 2.0
        } else {
            (safeEntry - stopDistance * 2.0).coerceAtLeast(instrument.tickSize)
        }
        val stopPips = if (instrument.pipSize > 0.0) stopDistance / instrument.pipSize else stopDistance

        val trailWindow = closed.takeLast(36)
        val atrWindow = if (atrSeries.isNotEmpty()) atrSeries.takeLast(trailWindow.size) else emptyList()
        val trailSeries = if (trailWindow.isNotEmpty() && atrWindow.size == trailWindow.size) {
            trailWindow.mapIndexed { idx, c ->
                val barAtr = atrWindow[idx] ?: effectiveAtr
                if (direction == Direction.LONG) c.close - barAtr * mult else c.close + barAtr * mult
            }
        } else {
            emptyList()
        }
        val outerSeries = if (trailWindow.isNotEmpty() && atrWindow.size == trailWindow.size) {
            trailWindow.mapIndexed { idx, c ->
                val barAtr = atrWindow[idx] ?: effectiveAtr
                if (direction == Direction.LONG) c.close - barAtr * (mult + 0.75) else c.close + barAtr * (mult + 0.75)
            }
        } else {
            emptyList()
        }

        return VolatilityStopProfile(
            atr14 = effectiveAtr,
            atrPct = atrPct,
            volatilityRegime = regimeLabel,
            multiplier = mult,
            direction = direction,
            entryPrice = safeEntry,
            recommendedStopPrice = recommendedStop,
            target2RPrice = target2R,
            stopDistancePrice = stopDistance,
            stopPipsOrPoints = stopPips,
            trailStopSeries = trailSeries,
            outerBandSeries = outerSeries,
            hasLiveCandles = closed.size >= 15 && liveAtr != null
        )
    }

    fun computeSizingSensitivityCurve(
        instrument: Instrument,
        accountBalanceUsd: Double,
        riskPct: Double,
        entryPrice: Double,
        atr14: Double,
        direction: Direction,
        spotRates: Map<String, Double>,
        minLiquidationStops: Int = 3,
        multipliers: List<Double> = listOf(0.75, 1.0, 1.25, 1.5, 2.0, 2.5, 3.0, 3.5)
    ): List<SizingSensitivityPoint> {
        if (entryPrice <= 0.0 || atr14 <= 0.0 || accountBalanceUsd <= 0.0 || riskPct <= 0.0) {
            return emptyList()
        }
        return multipliers.map { mult ->
            val stopDist = atr14 * mult
            val sl = if (direction == Direction.LONG) {
                (entryPrice - stopDist).coerceAtLeast(instrument.tickSize)
            } else {
                entryPrice + stopDist
            }
            val sizing: PositionSizingResult = RiskEngine.calculatePositionSize(
                instrument = instrument,
                accountBalanceUsd = accountBalanceUsd,
                riskPct = riskPct,
                entryPrice = entryPrice,
                stopLoss = sl,
                spotRates = spotRates,
                minLiquidationStops = minLiquidationStops
            )
            SizingSensitivityPoint(
                atrMultiplier = mult,
                stopDistancePrice = stopDist,
                stopPipsOrPoints = sizing.stopPipsOrPoints,
                roundedSize = sizing.roundedSize,
                notionalUsd = sizing.notionalUsd,
                maxSafeLeverage = sizing.suggestedMaxLeverage
            )
        }
    }

    fun computeExposureBreakdown(
        signals: List<SignalRecord>,
        riskConfig: RiskConfig,
        proposedRiskPct: Double = 0.0
    ): ExposureBreakdown {
        val openItems = signals.filter { SignalStatus.fromWire(it.status).isOpen }.mapNotNull { rec ->
            val ins = InstrumentUniverse.find(rec.instrument) ?: return@mapNotNull null
            OpenExposureItem(
                instrument = ins,
                direction = Direction.fromWire(rec.direction),
                riskPct = rec.suggestedRiskPct.coerceAtLeast(0.0)
            )
        }
        val openRiskPct = openItems.sumOf { it.riskPct }
        val remaining = (riskConfig.maxOpenRiskPct - openRiskPct).coerceAtLeast(0.0)
        val projected = openRiskPct + proposedRiskPct.coerceAtLeast(0.0)
        val fxNet = RiskEngine.computeCurrencyNetExposurePct(openItems)
        val worstFx = fxNet.values.maxOfOrNull { abs(it) } ?: 0.0
        val (btcBetaNet, cryptoClusters) = RiskEngine.computeCryptoClusterAndBetaExposure(openItems, emptyMap())

        val closedTodayR = signals.filter {
            SignalStatus.fromWire(it.status) in setOf(SignalStatus.WIN, SignalStatus.LOSS, SignalStatus.BREAKEVEN)
        }
        val realizedRTotal = closedTodayR.sumOf { it.realizedR }
        var peakR = 0.0
        var cumR = 0.0
        var maxDdR = 0.0
        for (sig in closedTodayR.sortedBy { it.createdAtIso }) {
            cumR += sig.realizedR
            if (cumR > peakR) peakR = cumR
            val dd = peakR - cumR
            if (dd > maxDdR) maxDdR = dd
        }
        val dailyLossPct = (-realizedRTotal.coerceAtMost(0.0)) * riskConfig.riskPerTradePct
        val weeklyLossPct = dailyLossPct
        val drawdownPct = maxDdR * riskConfig.riskPerTradePct

        return ExposureBreakdown(
            openItems = openItems,
            openCount = openItems.size,
            openRiskPct = openRiskPct,
            remainingPortfolioRiskPct = remaining,
            projectedRiskPct = projected,
            worstFxLegAbsPct = worstFx,
            fxNetExposurePct = fxNet,
            cryptoNetBtcBetaPct = btcBetaNet,
            cryptoClusterRiskPct = cryptoClusters,
            realizedDailyLossPct = dailyLossPct,
            realizedWeeklyLossPct = weeklyLossPct,
            currentDrawdownPct = drawdownPct
        )
    }
}
