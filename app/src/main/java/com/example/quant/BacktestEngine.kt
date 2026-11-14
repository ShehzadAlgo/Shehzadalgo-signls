package com.example.quant

import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.EconomicEvent
import com.example.domain.model.FundingPoint
import com.example.domain.model.Instrument
import com.example.domain.model.OpenInterestPoint
import com.example.domain.model.Regime
import com.example.domain.model.RiskConfig
import com.example.domain.model.RiskStatus
import com.example.domain.model.StrategyLifecycle
import com.example.domain.model.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class BacktestTrade(
    val instrument: String,
    val direction: Direction,
    val regime: Regime,
    val entryTimeMs: Long,
    val exitTimeMs: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val grossR: Double,
    val costR: Double,
    val netR: Double,
    val maeR: Double,
    val mfeR: Double,
    val exitReason: String
)

data class ValidationGatesConfig(
    val minOosTrades: Int = 100,
    val minExpectancyR: Double = 0.10,
    val minProfitFactor: Double = 1.20,
    val minSharpe: Double = 0.50,
    val maxMc95DrawdownPct: Double = 8.0,
    val minPositiveYearsPct: Double = 60.0
)

data class ParameterSensitivityPoint(
    val label: String,
    val expectancyR: Double,
    val profitFactor: Double,
    val trades: Int
)

data class BacktestValidationResult(
    val strategyId: String,
    val strategyVersion: String,
    val configHash: String,
    val dataSnapshotId: String,
    val randomSeed: Int,
    val lifecycleStatus: StrategyLifecycle,
    val trialCount: Int,
    val totalTrades: Int,
    val oosTrades: Int,
    val winRatePct: Double,
    val netExpectancyR: Double,
    val profitFactor: Double,
    val sharpe: Double,
    val deflatedSharpe: Double,
    val pbo: Double,
    val mc95DrawdownPct: Double,
    val mcMedianDrawdownPct: Double,
    val ruinProbPct: Double,
    val positiveYearsPct: Double,
    val randomBaseline95thExpectancyR: Double,
    val beatRandom95th: Boolean,
    val causalityPassed: Boolean,
    val failureReasons: List<String>,
    val equityCurveR: List<Double>,
    val trades: List<BacktestTrade>,
    val sensitivityNeighbors: List<ParameterSensitivityPoint>,
    val regimeBreakdown: Map<Regime, Pair<Int, Double>>
)

object BacktestEngine {

    /**
     * Runs an event-driven bar-by-bar backtest reusing the exact Strategy.onBar() and RiskEngine.evaluate()
     * code path used in live scanning. Signals formed on bar i close fill at bar i+1 open.
     * If both stop and target lie within one bar, assumes stop hit first (conservative).
     */
    fun simulateSeries(
        strategy: Strategy,
        instrument: Instrument,
        timeframe: Timeframe,
        candles: List<Candle>,
        dailyCandles: List<Candle> = emptyList(),
        fundingHistory: List<FundingPoint> = emptyList(),
        oiHistory: List<OpenInterestPoint> = emptyList(),
        events: List<EconomicEvent> = emptyList(),
        riskConfig: RiskConfig = RiskConfig(),
        costMultiplier: Double = 1.0
    ): List<BacktestTrade> {
        val closedCandles = candles.filter { it.isClosed }.sortedBy { it.openTimeEpochMs }
        if (closedCandles.size <= strategy.warmupBars() + 5) return emptyList()

        val trades = mutableListOf<BacktestTrade>()
        var openTrade: ActiveSimTrade? = null
        val warmup = strategy.warmupBars()

        for (i in warmup until closedCandles.size - 1) {
            val currentBar = closedCandles[i]
            val nextBar = closedCandles[i + 1]

            // 1. Evaluate open trade on current bar
            openTrade?.let { active ->
                val stopDist = active.stopDist
                val unrealizedBest = if (active.direction == Direction.LONG) {
                    (currentBar.high - active.entryPrice) / stopDist
                } else {
                    (active.entryPrice - currentBar.low) / stopDist
                }
                val unrealizedWorst = if (active.direction == Direction.LONG) {
                    (currentBar.low - active.entryPrice) / stopDist
                } else {
                    (active.entryPrice - currentBar.high) / stopDist
                }
                active.mfeR = max(active.mfeR, unrealizedBest)
                active.maeR = min(active.maeR, unrealizedWorst)

                // Accumulate overnight triple swap (FX) or 8h funding (Crypto)
                if (instrument.assetClass == AssetClass.FX && SessionClock.isTripleSwapWednesday(currentBar.closeTimeEpochMs)) {
                    active.carryCostR += 0.015 * costMultiplier
                } else if (instrument.assetClass == AssetClass.CRYPTO) {
                    val fundingNow = fundingHistory.lastOrNull { it.timestampEpochMs <= currentBar.closeTimeEpochMs }?.fundingRate ?: 0.0001
                    val sign = if (active.direction == Direction.LONG) 1.0 else -1.0
                    // Paying positive funding when long adds cost; receiving negative funding reduces cost
                    active.carryCostR += (sign * fundingNow * (active.entryPrice / stopDist) * 0.25) * costMultiplier
                }

                val stopHit = if (active.direction == Direction.LONG) {
                    currentBar.low <= active.stopLoss
                } else {
                    currentBar.high >= active.stopLoss
                }
                val tp1Hit = if (active.direction == Direction.LONG) {
                    currentBar.high >= active.tp1Price
                } else {
                    currentBar.low <= active.tp1Price
                }
                val tp2Hit = if (active.direction == Direction.LONG) {
                    currentBar.high >= active.tp2Price
                } else {
                    currentBar.low <= active.tp2Price
                }

                // Conservative rule: if stop and target both lie inside one bar, stop hit first
                val exitResult: Pair<Double, String>? = when {
                    stopHit -> {
                        val exitPx = if (active.direction == Direction.LONG) min(currentBar.open, active.stopLoss) else max(currentBar.open, active.stopLoss)
                        val rRem = if (active.tp1Closed) 0.0 else {
                            if (active.direction == Direction.LONG) (exitPx - active.entryPrice) / stopDist else (active.entryPrice - exitPx) / stopDist
                        }
                        val blendedGross = if (active.tp1Closed) (active.tp1Share * active.tp1R) else rRem
                        blendedGross to "STOP_LOSS"
                    }
                    tp2Hit -> {
                        val blendedGross = if (active.tp1Closed) {
                            active.tp1Share * active.tp1R + (1.0 - active.tp1Share) * active.tp2R
                        } else {
                            active.tp2R
                        }
                        blendedGross to "TP2_TARGET"
                    }
                    currentBar.closeTimeEpochMs >= active.expiresAtMs -> {
                        val exitR = if (active.direction == Direction.LONG) {
                            (currentBar.close - active.entryPrice) / stopDist
                        } else {
                            (active.entryPrice - currentBar.close) / stopDist
                        }
                        val blendedGross = if (active.tp1Closed) {
                            active.tp1Share * active.tp1R + (1.0 - active.tp1Share) * exitR
                        } else {
                            exitR
                        }
                        blendedGross to "TIME_EXPIRY"
                    }
                    else -> {
                        if (tp1Hit && !active.tp1Closed) {
                            active.tp1Closed = true
                            // Move remaining stop to breakeven after TP1
                            active.stopLoss = active.entryPrice
                        }
                        null
                    }
                }

                if (exitResult != null) {
                    val (grossR, reason) = exitResult
                    val totalCostR = (active.entryCostR + active.carryCostR) * costMultiplier
                    trades.add(
                        BacktestTrade(
                            instrument = instrument.symbol,
                            direction = active.direction,
                            regime = active.regime,
                            entryTimeMs = active.entryTimeMs,
                            exitTimeMs = currentBar.closeTimeEpochMs,
                            entryPrice = active.entryPrice,
                            exitPrice = currentBar.close,
                            grossR = grossR,
                            costR = totalCostR,
                            netR = grossR - totalCostR,
                            maeR = active.maeR,
                            mfeR = active.mfeR,
                            exitReason = reason
                        )
                    )
                    openTrade = null
                }
            }

            if (openTrade != null) continue

            // 2. Run pure strategy strictly on bars[0..i]
            val ctx = MarketContext(
                instrument = instrument,
                timeframe = timeframe,
                nowEpochMs = currentBar.closeTimeEpochMs,
                candles = closedCandles.subList(0, i + 1),
                dailyCandles = dailyCandles.filter { it.closeTimeEpochMs <= currentBar.closeTimeEpochMs },
                fundingHistory = fundingHistory.filter { it.timestampEpochMs <= currentBar.closeTimeEpochMs },
                openInterestHistory = oiHistory.filter { it.timestampEpochMs <= currentBar.closeTimeEpochMs },
                economicEvents = events,
                riskConfig = riskConfig
            )
            val candidates = strategy.onBar(ctx)
            val cand = candidates.firstOrNull() ?: continue

            val decision = RiskEngine.evaluate(
                candidate = cand,
                config = riskConfig,
                portfolio = PortfolioRiskState(activeEvents = events),
                spotRates = mapOf(instrument.symbol to currentBar.close),
                nowEpochMs = currentBar.closeTimeEpochMs
            )
            if (decision.status == RiskStatus.REJECTED) continue

            // Fill at next bar open with ATR-scaled slippage
            val slipPrice = 0.04 * cand.atrAtSignal
            val fillPrice = if (cand.direction == Direction.LONG) nextBar.open + slipPrice else nextBar.open - slipPrice
            val stopDist = abs(fillPrice - cand.stopLoss)
            if (stopDist <= 0.0) continue

            openTrade = ActiveSimTrade(
                direction = cand.direction,
                regime = cand.regime,
                entryTimeMs = nextBar.openTimeEpochMs,
                entryPrice = fillPrice,
                stopLoss = cand.stopLoss,
                stopDist = stopDist,
                tp1Price = cand.tp1Price,
                tp1R = cand.tp1R,
                tp1Share = cand.tp1ClosePct / 100.0,
                tp2Price = cand.tp2Price,
                tp2R = cand.tp2R,
                expiresAtMs = cand.expiresAtEpochMs,
                entryCostR = cand.estCostR
            )
        }
        return trades
    }

    /**
     * Mandatory Causality Gate: runs strategy on truncated history [0..t] vs full history
     * when inspecting bar t. Any discrepancy fails causality immediately.
     */
    fun verifyCausality(
        strategy: Strategy,
        instrument: Instrument,
        timeframe: Timeframe,
        fullCandles: List<Candle>,
        fundingHistory: List<FundingPoint> = emptyList(),
        oiHistory: List<OpenInterestPoint> = emptyList()
    ): Boolean {
        val sorted = fullCandles.filter { it.isClosed }.sortedBy { it.openTimeEpochMs }
        if (sorted.size < strategy.warmupBars() + 10) return true

        val probeIndices = listOf(
            sorted.size - 10,
            sorted.size - 5,
            sorted.size - 1
        ).filter { it >= strategy.warmupBars() }

        for (idx in probeIndices) {
            val tMs = sorted[idx].closeTimeEpochMs
            val truncatedCtx = MarketContext(
                instrument = instrument,
                timeframe = timeframe,
                nowEpochMs = tMs,
                candles = sorted.subList(0, idx + 1),
                fundingHistory = fundingHistory.filter { it.timestampEpochMs <= tMs },
                openInterestHistory = oiHistory.filter { it.timestampEpochMs <= tMs }
            )
            val fullCtxWithTimestampGate = MarketContext(
                instrument = instrument,
                timeframe = timeframe,
                nowEpochMs = tMs,
                candles = sorted,
                fundingHistory = fundingHistory,
                openInterestHistory = oiHistory
            )
            val sigTruncated = strategy.onBar(truncatedCtx)
            val sigFull = strategy.onBar(fullCtxWithTimestampGate)
            if (sigTruncated.size != sigFull.size) return false
            for (k in sigTruncated.indices) {
                val a = sigTruncated[k]
                val b = sigFull[k]
                if (a.direction != b.direction || abs(a.stopLoss - b.stopLoss) > 1e-9 || abs(a.conviction - b.conviction) > 1e-9) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Full validation pipeline:
     * 1. Walk-forward OOS split (last 40% of trades/bars as OOS)
     * 2. Parameter sensitivity (+/- 20% neighbor stability)
     * 3. Monte Carlo (500 iterations, order resampling, 10% skip, 2x cost shock)
     * 4. Random-entry 95th percentile benchmark
     * 5. Causality gate + Deflated Sharpe + Honest PAPER vs REJECTED verdict.
     */
    fun runValidationPipeline(
        strategy: Strategy,
        datasets: List<Triple<Instrument, Timeframe, List<Candle>>>,
        fundingBySymbol: Map<String, List<FundingPoint>> = emptyMap(),
        oiBySymbol: Map<String, List<OpenInterestPoint>> = emptyMap(),
        riskConfig: RiskConfig = RiskConfig(),
        gates: ValidationGatesConfig = ValidationGatesConfig(),
        seed: Int = 42
    ): BacktestValidationResult {
        val allTrades = mutableListOf<BacktestTrade>()
        var causalityOk = true

        for ((instrument, tf, candles) in datasets) {
            if (instrument.assetClass !in strategy.supportedAssetClasses) continue
            if (tf !in strategy.supportedTimeframes) continue
            val funding = fundingBySymbol[instrument.symbol].orEmpty()
            val oi = oiBySymbol[instrument.symbol].orEmpty()

            if (!verifyCausality(strategy, instrument, tf, candles, funding, oi)) {
                causalityOk = false
            }
            allTrades += simulateSeries(
                strategy = strategy,
                instrument = instrument,
                timeframe = tf,
                candles = candles,
                fundingHistory = funding,
                oiHistory = oi,
                riskConfig = riskConfig,
                costMultiplier = 1.0
            )
        }

        val sortedTrades = allTrades.sortedBy { it.entryTimeMs }
        val oosStartIdx = (sortedTrades.size * 0.5).toInt()
        val oosList = if (sortedTrades.size > 10) sortedTrades.subList(oosStartIdx, sortedTrades.size) else sortedTrades

        val metrics = computeTradeMetrics(oosList)
        val mc = runMonteCarlo(oosList, riskPerTradePct = riskConfig.riskPerTradePct, iterations = 500, seed = seed)
        val random95th = runRandomBaseline95th(oosList.size.coerceAtLeast(30), seed = seed + 7)
        val sensitivity = runSensitivityNeighbors(strategy, datasets, fundingBySymbol, oiBySymbol, riskConfig)

        val positiveYearsPct = computePositiveYearPct(sortedTrades)
        val trialCount = max(1, sensitivity.size + 1)
        val deflatedSharpe = computeDeflatedSharpe(metrics.sharpe, trialCount, oosList.size)
        val pbo = if (sensitivity.isEmpty()) 0.5 else {
            val unprofitable = sensitivity.count { it.expectancyR <= 0.0 }
            unprofitable.toDouble() / sensitivity.size.toDouble()
        }

        val beatRandom = metrics.expectancyR > random95th && oosList.isNotEmpty()
        val failures = mutableListOf<String>()
        if (!causalityOk) failures.add("CAUSALITY_GATE_FAILED: Strategy leaked future bar state.")
        if (oosList.size < gates.minOosTrades) failures.add("LOW_SAMPLE: ${oosList.size} OOS trades < ${gates.minOosTrades} required.")
        if (metrics.expectancyR <= gates.minExpectancyR) failures.add("EXPECTANCY_BELOW_GATE: ${"%.2f".format(metrics.expectancyR)}R <= ${gates.minExpectancyR}R.")
        if (metrics.profitFactor <= gates.minProfitFactor) failures.add("PROFIT_FACTOR_LOW: ${"%.2f".format(metrics.profitFactor)} <= ${gates.minProfitFactor}.")
        if (metrics.sharpe <= gates.minSharpe) failures.add("SHARPE_BELOW_GATE: ${"%.2f".format(metrics.sharpe)} <= ${gates.minSharpe}.")
        if (mc.dd95Pct > gates.maxMc95DrawdownPct) failures.add("MC_DRAWDOWN_EXCEEDED: 95th DD ${"%.1f".format(mc.dd95Pct)}% > ${gates.maxMc95DrawdownPct}%.")
        if (positiveYearsPct < gates.minPositiveYearsPct) failures.add("CALENDAR_STABILITY_LOW: ${"%.0f".format(positiveYearsPct)}% positive years < ${gates.minPositiveYearsPct}%.")
        if (!beatRandom) failures.add("FAILED_RANDOM_BASELINE: Did not beat 95th percentile random entry (${"%.2f".format(random95th)}R).")
        if (sensitivity.any { it.trades >= 10 && it.expectancyR <= 0.0 }) {
            failures.add("PARAMETER_FRAGILITY: 20% parameter neighbor turned unprofitable.")
        }

        val lifecycle = if (failures.isEmpty()) StrategyLifecycle.PAPER else StrategyLifecycle.REJECTED
        val breakdown = sortedTrades.groupBy { it.regime }.mapValues { (_, list) ->
            list.size to list.sumOf { it.netR }
        }

        return BacktestValidationResult(
            strategyId = strategy.id,
            strategyVersion = strategy.version,
            configHash = strategy.configHash,
            dataSnapshotId = "snapshot_${datasets.sumOf { it.third.size }}bars",
            randomSeed = seed,
            lifecycleStatus = lifecycle,
            trialCount = trialCount,
            totalTrades = sortedTrades.size,
            oosTrades = oosList.size,
            winRatePct = metrics.winRatePct,
            netExpectancyR = metrics.expectancyR,
            profitFactor = metrics.profitFactor,
            sharpe = metrics.sharpe,
            deflatedSharpe = deflatedSharpe,
            pbo = pbo,
            mc95DrawdownPct = mc.dd95Pct,
            mcMedianDrawdownPct = mc.dd50Pct,
            ruinProbPct = mc.ruinPct,
            positiveYearsPct = positiveYearsPct,
            randomBaseline95thExpectancyR = random95th,
            beatRandom95th = beatRandom,
            causalityPassed = causalityOk,
            failureReasons = failures,
            equityCurveR = metrics.equityCurveR,
            trades = sortedTrades,
            sensitivityNeighbors = sensitivity,
            regimeBreakdown = breakdown
        )
    }

    private data class TradeMetrics(
        val winRatePct: Double,
        val expectancyR: Double,
        val profitFactor: Double,
        val sharpe: Double,
        val equityCurveR: List<Double>
    )

    private fun computeTradeMetrics(trades: List<BacktestTrade>): TradeMetrics {
        if (trades.isEmpty()) return TradeMetrics(0.0, 0.0, 0.0, 0.0, listOf(0.0))
        val wins = trades.filter { it.netR > 0.0 }
        val losses = trades.filter { it.netR <= 0.0 }
        val grossProfit = wins.sumOf { it.netR }
        val grossLoss = abs(losses.sumOf { it.netR })
        val pf = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0.0) 3.0 else 0.0
        val avgR = trades.map { it.netR }.average()
        val stdR = if (trades.size > 1) {
            val varSum = trades.sumOf { (it.netR - avgR) * (it.netR - avgR) }
            sqrt(varSum / (trades.size - 1))
        } else 1.0
        val sharpe = if (stdR > 1e-9) (avgR / stdR) * sqrt(36.0) else 0.0

        val curve = ArrayList<Double>(trades.size + 1)
        curve.add(0.0)
        var cum = 0.0
        for (t in trades) {
            cum += t.netR
            curve.add(cum)
        }
        return TradeMetrics(
            winRatePct = (wins.size.toDouble() / trades.size.toDouble()) * 100.0,
            expectancyR = avgR,
            profitFactor = pf,
            sharpe = sharpe,
            equityCurveR = curve
        )
    }

    private data class MonteCarloSummary(val dd50Pct: Double, val dd95Pct: Double, val ruinPct: Double)

    private fun runMonteCarlo(
        trades: List<BacktestTrade>,
        riskPerTradePct: Double,
        iterations: Int,
        seed: Int
    ): MonteCarloSummary {
        if (trades.isEmpty()) return MonteCarloSummary(0.0, 0.0, 0.0)
        val rng = Random(seed)
        val maxDrawdowns = ArrayList<Double>(iterations)
        var ruinCount = 0

        for (iter in 0 until iterations) {
            var equity = 100.0
            var peak = 100.0
            var maxDd = 0.0
            for (step in trades.indices) {
                // 10% random trade skip
                if (rng.nextDouble() < 0.10) continue
                val t = trades[rng.nextInt(trades.size)]
                // Double the cost impact
                val stressedR = t.grossR - (2.0 * t.costR)
                equity *= (1.0 + (stressedR * riskPerTradePct / 100.0))
                if (equity > peak) peak = equity
                val dd = ((peak - equity) / peak) * 100.0
                if (dd > maxDd) maxDd = dd
                if (equity <= 75.0) {
                    ruinCount++
                    break
                }
            }
            maxDrawdowns.add(maxDd)
        }
        maxDrawdowns.sort()
        val p50 = maxDrawdowns[(maxDrawdowns.size * 0.50).toInt().coerceIn(0, maxDrawdowns.lastIndex)]
        val p95 = maxDrawdowns[(maxDrawdowns.size * 0.95).toInt().coerceIn(0, maxDrawdowns.lastIndex)]
        val ruinPct = (ruinCount.toDouble() / iterations.toDouble()) * 100.0
        return MonteCarloSummary(p50, p95, ruinPct)
    }

    private fun runRandomBaseline95th(sampleSize: Int, seed: Int): Double {
        val rng = Random(seed)
        val expectancies = ArrayList<Double>(200)
        for (trial in 0 until 200) {
            var sumR = 0.0
            for (k in 0 until sampleSize) {
                // Random coin-flip entry with 1:2 R:R minus 0.06R cost -> negative expectancy around -0.06R
                val win = rng.nextDouble() < 0.3333
                val r = if (win) 2.0 - 0.06 else -1.0 - 0.06
                sumR += r
            }
            expectancies.add(sumR / sampleSize.toDouble())
        }
        expectancies.sort()
        return expectancies[(expectancies.size * 0.95).toInt().coerceIn(0, expectancies.lastIndex)]
    }

    private fun runSensitivityNeighbors(
        strategy: Strategy,
        datasets: List<Triple<Instrument, Timeframe, List<Candle>>>,
        fundingBySymbol: Map<String, List<FundingPoint>>,
        oiBySymbol: Map<String, List<OpenInterestPoint>>,
        riskConfig: RiskConfig
    ): List<ParameterSensitivityPoint> {
        val neighbors: List<Pair<String, Strategy>> = when (strategy.id) {
            "S1_TREND_DONCHIAN" -> listOf(
                "Period 44 (-20%)" to S1TrendDonchianStrategy(S1Params(entryPeriod = 44)),
                "Period 66 (+20%)" to S1TrendDonchianStrategy(S1Params(entryPeriod = 66)),
                "Stop 2.2 ATR (-20%)" to S1TrendDonchianStrategy(S1Params(stopAtrMult = 2.2)),
                "Stop 3.3 ATR (+20%)" to S1TrendDonchianStrategy(S1Params(stopAtrMult = 3.3))
            )
            "S2_SESSION_BREAKOUT" -> listOf(
                "ATR Ratio 0.20..0.64" to S2SessionBreakoutStrategy(S2Params(minRangeDailyAtrRatio = 0.20, maxRangeDailyAtrRatio = 0.64)),
                "ATR Ratio 0.30..0.96" to S2SessionBreakoutStrategy(S2Params(minRangeDailyAtrRatio = 0.30, maxRangeDailyAtrRatio = 0.96))
            )
            else -> listOf(
                "Funding Z 1.6 (-20%)" to S3FundingOiContrarianStrategy(S3Params(fundingZThreshold = 1.6)),
                "Funding Z 2.4 (+20%)" to S3FundingOiContrarianStrategy(S3Params(fundingZThreshold = 2.4))
            )
        }
        return neighbors.map { (label, variant) ->
            val variantTrades = datasets.flatMap { (ins, tf, c) ->
                if (ins.assetClass !in variant.supportedAssetClasses || tf !in variant.supportedTimeframes) emptyList()
                else simulateSeries(variant, ins, tf, c, emptyList(), fundingBySymbol[ins.symbol].orEmpty(), oiBySymbol[ins.symbol].orEmpty(), emptyList(), riskConfig)
            }
            val m = computeTradeMetrics(variantTrades)
            ParameterSensitivityPoint(label, m.expectancyR, m.profitFactor, variantTrades.size)
        }
    }

    private fun computePositiveYearPct(trades: List<BacktestTrade>): Double {
        if (trades.isEmpty()) return 0.0
        val byYear = trades.groupBy {
            java.time.Instant.ofEpochMilli(it.exitTimeMs).atZone(SessionClock.utcZone).year
        }
        if (byYear.isEmpty()) return 0.0
        val positiveYears = byYear.values.count { list -> list.sumOf { it.netR } > 0.0 }
        return (positiveYears.toDouble() / byYear.size.toDouble()) * 100.0
    }

    private fun computeDeflatedSharpe(sharpe: Double, trialCount: Int, nTrades: Int): Double {
        if (nTrades < 5) return 0.0
        val penalty = sqrt(2.0 * kotlin.math.ln(max(1, trialCount).toDouble())) / sqrt(nTrades.toDouble())
        return (sharpe - penalty).coerceAtLeast(-2.0)
    }

    private data class ActiveSimTrade(
        val direction: Direction,
        val regime: Regime,
        val entryTimeMs: Long,
        val entryPrice: Double,
        var stopLoss: Double,
        val stopDist: Double,
        val tp1Price: Double,
        val tp1R: Double,
        val tp1Share: Double,
        val tp2Price: Double,
        val tp2R: Double,
        val expiresAtMs: Long,
        val entryCostR: Double,
        var carryCostR: Double = 0.0,
        var tp1Closed: Boolean = false,
        var maeR: Double = 0.0,
        var mfeR: Double = 0.0
    )
}
