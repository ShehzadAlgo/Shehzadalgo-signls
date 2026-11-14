package com.example.quant

import com.example.data.firestore.SignalRecord
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Direction
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.PositionSizingResult
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import kotlin.math.abs

data class MarketAnalyticsSnapshot(
    val instrument: String,
    val timeframe: String,
    val barsAnalyzed: Int,
    val latestClose: Double,
    val ema20: Double?,
    val ema50: Double?,
    val ema200: Double?,
    val trendBias: String,
    val rsi14: Double?,
    val adx14: Double?,
    val atr14: Double?,
    val atrPctOfPrice: Double?,
    val atrPercentile: Double?,
    val recentSwingHigh: Double?,
    val recentSwingLow: Double?,
    val stopDistancePrice: Double,
    val stopDistanceAtrMultiple: Double?,
    val rewardToCostRatio: Double,
    val sizingResult: PositionSizingResult,
    val openPortfolioRiskPct: Double,
    val projectedOpenRiskPct: Double,
    val fxLegExposures: Map<String, Double>,
    val cryptoBtcBetaNetPct: Double,
    val hasDataQualityBlocker: Boolean
) {
    fun toTraderPromptContext(signal: SignalRecord): String = buildString {
        val decimals = if (instrument.endsWith("JPY")) 3 else if (signal.assetClass == "crypto") 2 else 5
        val fmt: (Double) -> String = { "%.${decimals}f".format(it) }
        appendLine("=== SIGNAL UNDER VERIFICATION ===")
        appendLine("Instrument: ${signal.instrument} (${signal.assetClass.uppercase()}) | Direction: ${signal.direction.uppercase()} | Timeframe: ${signal.timeframe}")
        appendLine("Strategy: ${signal.strategy} v${signal.strategyVersion} | Regime: ${signal.regime} | Engine Conviction: ${(signal.conviction * 100).toInt()}%")
        appendLine("Entry Zone: ${fmt(signal.entryLow)} - ${fmt(signal.entryHigh)} | Stop Loss: ${fmt(signal.stopLoss)}")
        appendLine("TP1: ${fmt(signal.tp1Price)} (${signal.tp1R}R, close ${signal.tp1ClosePct}%) | TP2: ${fmt(signal.tp2Price)} (${signal.tp2R}R, close ${signal.tp2ClosePct}%)")
        appendLine("Est Cost: ${"%.2f".format(signal.estCostR)}R | TP1-to-Cost Ratio: ${"%.1f".format(rewardToCostRatio)}x")
        appendLine("Risk Engine Decision: ${signal.riskStatus} (Suggested Risk: ${"%.2f".format(signal.suggestedRiskPct)}%) | Reasons: ${signal.riskReasons.joinToString()}")
        if (signal.riskAdjustments.isNotEmpty()) {
            appendLine("Risk Adjustments: ${signal.riskAdjustments.joinToString()}")
        }
        appendLine("Engine Rationale: ${signal.rationale}")
        appendLine("Engine Invalidation: ${signal.invalidation}")
        appendLine()
        appendLine("=== CLOSED-BAR MARKET ANALYTICS ($barsAnalyzed bars) ===")
        if (barsAnalyzed > 0) {
            appendLine("Latest Closed Price: ${fmt(latestClose)} | Trend Bias: $trendBias")
            appendLine("EMA20: ${ema20?.let(fmt) ?: "N/A"} | EMA50: ${ema50?.let(fmt) ?: "N/A"} | EMA200: ${ema200?.let(fmt) ?: "N/A"}")
            appendLine("RSI(14): ${rsi14?.let { "%.1f".format(it) } ?: "N/A"} | ADX(14): ${adx14?.let { "%.1f".format(it) } ?: "N/A"}")
            appendLine("ATR(14): ${atr14?.let(fmt) ?: "N/A"} (${atrPctOfPrice?.let { "%.2f".format(it) } ?: "N/A"}% of price, ${atrPercentile?.let { "%.0f".format(it) } ?: "N/A"}th pct)")
            appendLine("20-Bar Swing High/Low: ${recentSwingHigh?.let(fmt) ?: "N/A"} / ${recentSwingLow?.let(fmt) ?: "N/A"}")
            appendLine("Stop Distance in ATRs: ${stopDistanceAtrMultiple?.let { "%.2f".format(it) } ?: "N/A"} ATR")
        } else {
            appendLine("No cached closed candles available yet for $instrument; evaluate strictly from signal parameters, costs, and portfolio risk.")
        }
        appendLine()
        appendLine("=== TRADER PORTFOLIO & SIZING RISK ===")
        appendLine("Account Risk ($): $${"%.2f".format(sizingResult.riskAmountUsd)} (${"%.2f".format(sizingResult.riskPct)}% of $${"%.0f".format(sizingResult.accountBalanceUsd)})")
        if (sizingResult.valid) {
            appendLine("Recommended Size: ${sizingResult.roundedSize} ${sizingResult.sizeUnitLabel} | Notional: $${"%.0f".format(sizingResult.notionalUsd)}")
            sizingResult.suggestedMaxLeverage?.let { lev ->
                appendLine("Max Safe Leverage: ${"%.1f".format(lev)}x | Liq Distance: ${"%.1f".format(sizingResult.estLiquidationDistanceStops ?: 3.0)}x stop")
            }
        } else {
            appendLine("Sizing Warning: ${sizingResult.rejectionReason}")
        }
        appendLine("Open Portfolio Heat: ${"%.2f".format(openPortfolioRiskPct)}% current -> ${"%.2f".format(projectedOpenRiskPct)}% projected")
        if (fxLegExposures.isNotEmpty()) {
            appendLine("FX Net Currency Legs: ${fxLegExposures.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}%" }}")
        }
        if (signal.assetClass == "crypto") {
            appendLine("Crypto Net BTC-Beta Risk: ${"%.2f".format(cryptoBtcBetaNetPct)}%")
        }
        appendLine("Data Quality Blocker Active: $hasDataQualityBlocker")
    }.trim()
}

object MarketAnalyticsEngine {

    fun compute(
        signal: SignalRecord,
        candles: List<Candle>,
        riskConfig: RiskConfig,
        allSignals: List<SignalRecord>,
        spotRates: Map<String, Double>,
        qualityIssues: List<DataQualityIssue>
    ): MarketAnalyticsSnapshot {
        val ins: Instrument = InstrumentUniverse.find(signal.instrument) ?: InstrumentUniverse.all.first()
        val entryMid = (signal.entryLow + signal.entryHigh) / 2.0
        val stopDist = abs(entryMid - signal.stopLoss)

        val closed = candles.filter { it.isClosed }.sortedBy { it.openTimeEpochMs }
        val closes = closed.map { it.close }
        val latestClose = closes.lastOrNull() ?: entryMid

        val ema20 = Indicators.ema(closes, 20).lastOrNull()
        val ema50 = Indicators.ema(closes, 50).lastOrNull()
        val ema200 = Indicators.ema(closes, 200).lastOrNull()
        val rsi14 = Indicators.rsi(closes, 14).lastOrNull()
        val atrSeries = Indicators.atr(closed, 14)
        val atr14 = atrSeries.lastOrNull()
        val adx14 = Indicators.adx(closed, 14).lastOrNull()
        val atrPct = if (atr14 != null && latestClose > 0.0) (atr14 / latestClose) * 100.0 else null
        val atrPctile = if (atrSeries.isNotEmpty()) Indicators.atrPercentile(atrSeries, atrSeries.lastIndex, 250) else null

        val lookback20 = closed.takeLast(20)
        val swingHigh = lookback20.maxOfOrNull { it.high }
        val swingLow = lookback20.minOfOrNull { it.low }

        val trendBias = when {
            ema20 != null && ema50 != null && ema200 != null &&
                latestClose > ema20 && ema20 > ema50 && ema50 > ema200 -> "STRONG BULLISH STACK"
            ema20 != null && ema50 != null && ema200 != null &&
                latestClose < ema20 && ema20 < ema50 && ema50 < ema200 -> "STRONG BEARISH STACK"
            ema20 != null && ema50 != null && latestClose > ema20 && ema20 > ema50 -> "SHORT-TERM BULLISH"
            ema20 != null && ema50 != null && latestClose < ema20 && ema20 < ema50 -> "SHORT-TERM BEARISH"
            closed.isEmpty() -> "NO CANDLE CACHE (SIGNAL-ONLY)"
            else -> "MIXED / CONSOLIDATION"
        }

        val stopAtrMult = if (atr14 != null && atr14 > 0.0) stopDist / atr14 else null
        val rewardToCost = if (signal.estCostR > 0.0) signal.tp1R / signal.estCostR else signal.tp1R

        val effectiveRiskPct = if (signal.suggestedRiskPct > 0.0) signal.suggestedRiskPct else riskConfig.riskPerTradePct
        val sizing = RiskEngine.calculatePositionSize(
            instrument = ins,
            accountBalanceUsd = riskConfig.accountBalanceUsd,
            riskPct = effectiveRiskPct,
            entryPrice = entryMid,
            stopLoss = signal.stopLoss,
            spotRates = spotRates,
            minLiquidationStops = riskConfig.minLiquidationStops
        )

        val openSignals = allSignals.filter { SignalStatus.fromWire(it.status).isOpen }
        val openItems = openSignals.mapNotNull { rec ->
            val found = InstrumentUniverse.find(rec.instrument) ?: return@mapNotNull null
            OpenExposureItem(found, Direction.fromWire(rec.direction), rec.suggestedRiskPct)
        }
        val openRiskPct = openItems.sumOf { it.riskPct }
        val alreadyIncluded = openSignals.any { it.id == signal.id || it.signalUuid == signal.signalUuid }
        val projectedOpenRisk = if (alreadyIncluded) openRiskPct else openRiskPct + effectiveRiskPct

        val fxLegs = RiskEngine.computeCurrencyNetExposurePct(openItems)
        val (btcBetaNet, _) = RiskEngine.computeCryptoClusterAndBetaExposure(openItems, emptyMap())
        val hasBlocker = qualityIssues.any { it.instrument == signal.instrument && it.blocksSignals }

        return MarketAnalyticsSnapshot(
            instrument = signal.instrument,
            timeframe = signal.timeframe,
            barsAnalyzed = closed.size,
            latestClose = latestClose,
            ema20 = ema20,
            ema50 = ema50,
            ema200 = ema200,
            trendBias = trendBias,
            rsi14 = rsi14,
            adx14 = adx14,
            atr14 = atr14,
            atrPctOfPrice = atrPct,
            atrPercentile = atrPctile,
            recentSwingHigh = swingHigh,
            recentSwingLow = swingLow,
            stopDistancePrice = stopDist,
            stopDistanceAtrMultiple = stopAtrMult,
            rewardToCostRatio = rewardToCost,
            sizingResult = sizing,
            openPortfolioRiskPct = openRiskPct,
            projectedOpenRiskPct = projectedOpenRisk,
            fxLegExposures = fxLegs,
            cryptoBtcBetaNetPct = btcBetaNet,
            hasDataQualityBlocker = hasBlocker
        )
    }
}
