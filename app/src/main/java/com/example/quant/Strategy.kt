package com.example.quant

import com.example.domain.model.AssetClass
import com.example.domain.model.CandidateSignal
import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.EconomicEvent
import com.example.domain.model.FundingPoint
import com.example.domain.model.Instrument
import com.example.domain.model.OpenInterestPoint
import com.example.domain.model.Regime
import com.example.domain.model.RiskConfig
import com.example.domain.model.Timeframe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure closed-bar MarketContext. Exposes data strictly up to [nowEpochMs] only,
 * preventing any look-ahead bias across backtest, paper, and live execution.
 */
data class MarketContext(
    val instrument: Instrument,
    val timeframe: Timeframe,
    val nowEpochMs: Long,
    val candles: List<Candle>,
    val dailyCandles: List<Candle> = emptyList(),
    val fundingHistory: List<FundingPoint> = emptyList(),
    val openInterestHistory: List<OpenInterestPoint> = emptyList(),
    val economicEvents: List<EconomicEvent> = emptyList(),
    val riskConfig: RiskConfig = RiskConfig()
) {
    fun closedBars(): List<Candle> =
        candles.filter { it.closeTimeEpochMs <= nowEpochMs && it.isClosed }

    fun closedDailyBars(): List<Candle> =
        dailyCandles.filter { it.closeTimeEpochMs <= nowEpochMs && it.isClosed }

    fun closedFunding(): List<FundingPoint> =
        fundingHistory.filter { it.timestampEpochMs <= nowEpochMs }

    fun closedOpenInterest(): List<OpenInterestPoint> =
        openInterestHistory.filter { it.timestampEpochMs <= nowEpochMs }

    fun availableEvents(): List<EconomicEvent> =
        economicEvents
}

interface Strategy {
    val id: String
    val displayName: String
    val version: String
    val configHash: String
    val hypothesis: String
    val supportedAssetClasses: Set<AssetClass>
    val supportedTimeframes: Set<Timeframe>
    val regimesAllowed: Set<Regime>
    fun warmupBars(): Int
    fun onBar(ctx: MarketContext): List<CandidateSignal>
}

data class S1Params(
    val entryPeriod: Int = 55,
    val exitPeriod: Int = 20,
    val adxThreshold: Double = 20.0,
    val stopAtrMult: Double = 2.75,
    val tp1R: Double = 2.0,
    val tp2R: Double = 4.0
)

/**
 * S1 TREND_DONCHIAN: 4H and 1D, FX and Crypto, regime TREND.
 * Long on close above prior 55-bar high (when price > 200 EMA and ADX > 20),
 * short on close below prior 55-bar low (when price < 200 EMA and ADX > 20).
 * Initial stop 2.75 ATR, TP1 at 2R (50%), trail remainder.
 */
class S1TrendDonchianStrategy(
    private val params: S1Params = S1Params()
) : Strategy {
    override val id: String = "S1_TREND_DONCHIAN"
    override val displayName: String = "S1 Trend Donchian"
    override val version: String = "1.0.0"
    override val configHash: String = HashChainUtils.sha256Hex("S1:${params.entryPeriod}:${params.exitPeriod}:${params.adxThreshold}:${params.stopAtrMult}").take(12)
    override val hypothesis: String = "Multi-day breakouts aligned with the 200 EMA macro trend and rising ADX capture fat-tailed directional persistence; low win rate offset by asymmetric R winners."
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.FX, AssetClass.CRYPTO)
    override val supportedTimeframes: Set<Timeframe> = setOf(Timeframe.H4, Timeframe.D1)
    override val regimesAllowed: Set<Regime> = setOf(Regime.TREND)

    override fun warmupBars(): Int = 210

    override fun onBar(ctx: MarketContext): List<CandidateSignal> {
        if (ctx.instrument.assetClass !in supportedAssetClasses) return emptyList()
        if (ctx.timeframe !in supportedTimeframes) return emptyList()

        val bars = ctx.closedBars()
        if (bars.size < warmupBars()) return emptyList()
        val idx = bars.lastIndex

        val regimeSnap = RegimeEngine.evaluateAt(bars, idx, ctx.instrument, ctx.availableEvents(), ctx.riskConfig)
            ?: return emptyList()
        if (Regime.EVENT in regimeSnap.tags) return emptyList()
        if (regimeSnap.tags.none { it in regimesAllowed }) return emptyList()

        val bar = bars[idx]
        val atr = regimeSnap.atr
        if (atr <= 0.0 || regimeSnap.adx <= params.adxThreshold) return emptyList()

        val donchianHigh = Indicators.priorDonchianHigh(bars, idx, params.entryPeriod) ?: return emptyList()
        val donchianLow = Indicators.priorDonchianLow(bars, idx, params.entryPeriod) ?: return emptyList()
        val exitLow = Indicators.priorDonchianLow(bars, idx, params.exitPeriod) ?: donchianLow
        val exitHigh = Indicators.priorDonchianHigh(bars, idx, params.exitPeriod) ?: donchianHigh

        val direction = when {
            bar.close > donchianHigh && bar.close > regimeSnap.ema200 -> Direction.LONG
            bar.close < donchianLow && bar.close < regimeSnap.ema200 -> Direction.SHORT
            else -> return emptyList()
        }

        val entryMid = bar.close
        val bandHalf = 0.08 * atr
        val stopDist = params.stopAtrMult * atr
        val stopLoss = if (direction == Direction.LONG) entryMid - stopDist else entryMid + stopDist
        val tp1 = if (direction == Direction.LONG) entryMid + params.tp1R * stopDist else entryMid - params.tp1R * stopDist
        val tp2 = if (direction == Direction.LONG) entryMid + params.tp2R * stopDist else entryMid - params.tp2R * stopDist

        val estSpread = ctx.instrument.baseSpreadPrice
        val estComm = entryMid * ctx.instrument.commissionPerUnitPct * 2.0
        val estSlip = 0.05 * atr
        val estCostR = ((estSpread + estComm + estSlip) / stopDist).coerceIn(0.01, 0.99)

        val adxScore = ((regimeSnap.adx - params.adxThreshold) / 25.0).coerceIn(0.0, 1.0)
        val breakoutStrength = (abs(bar.close - (if (direction == Direction.LONG) donchianHigh else donchianLow)) / atr).coerceIn(0.0, 1.0)
        val conviction = (0.45 + 0.35 * adxScore + 0.20 * breakoutStrength).coerceIn(0.1, 0.95)

        val expiryMs = bar.closeTimeEpochMs + ctx.timeframe.durationMinutes * 60_000L * 4L

        return listOf(
            CandidateSignal(
                instrument = ctx.instrument,
                timeframe = ctx.timeframe,
                barCloseEpochMs = bar.closeTimeEpochMs,
                direction = direction,
                strategyId = id,
                strategyVersion = version,
                configHash = configHash,
                regime = regimeSnap.primaryRegime,
                allRegimes = regimeSnap.tags,
                conviction = conviction,
                entryLow = entryMid - bandHalf,
                entryHigh = entryMid + bandHalf,
                stopLoss = stopLoss,
                tp1Price = tp1,
                tp1R = params.tp1R,
                tp1ClosePct = 50,
                tp2Price = tp2,
                tp2R = params.tp2R,
                tp2ClosePct = 50,
                estCostR = estCostR,
                expiresAtEpochMs = expiryMs,
                invalidation = if (direction == Direction.LONG) {
                    "Close below 20-bar channel (${formatNum(exitLow, ctx.instrument)}) or 2.75 ATR trail"
                } else {
                    "Close above 20-bar channel (${formatNum(exitHigh, ctx.instrument)}) or 2.75 ATR trail"
                },
                rationale = "Close broke ${params.entryPeriod}-bar ${if (direction == Direction.LONG) "high" else "low"} with ADX ${"%.1f".format(regimeSnap.adx)} & 200 EMA alignment.",
                featureSnapshot = mapOf(
                    "adx" to regimeSnap.adx,
                    "atr" to atr,
                    "ema200" to regimeSnap.ema200,
                    "donchianHigh" to donchianHigh,
                    "donchianLow" to donchianLow
                ),
                atrAtSignal = atr
            )
        )
    }

    private fun formatNum(v: Double, ins: Instrument): String =
        if (ins.quoteCurrency == "JPY") "%.3f".format(v) else "%.5f".format(v)
}

data class S2Params(
    val asianStartHourLondon: Int = 0,
    val asianEndHourLondon: Int = 8,
    val entryEndHourLondon: Int = 11,
    val minRangeDailyAtrRatio: Double = 0.25,
    val maxRangeDailyAtrRatio: Double = 0.80,
    val tp1R: Double = 1.5,
    val tp2R: Double = 3.0
)

/**
 * S2 SESSION_BREAKOUT: 15m and 1H, FX only, regimes LOW_VOL or TREND.
 * Asian range 00:00–08:00 Europe/London. Enter on 15m/1H close beyond range between 08:00–11:00 London
 * when range height is 0.25 to 0.80 of daily ATR. One attempt per pair per day.
 */
class S2SessionBreakoutStrategy(
    private val params: S2Params = S2Params()
) : Strategy {
    override val id: String = "S2_SESSION_BREAKOUT"
    override val displayName: String = "S2 Session Breakout"
    override val version: String = "1.0.0"
    override val configHash: String = HashChainUtils.sha256Hex("S2:${params.asianStartHourLondon}:${params.asianEndHourLondon}:${params.entryEndHourLondon}:${params.minRangeDailyAtrRatio}:${params.maxRangeDailyAtrRatio}").take(12)
    override val hypothesis: String = "FX volatility compresses during the 00:00-08:00 London Asian session and resolves directionally during the London institutional open when the Asian range is compact relative to daily ATR."
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.FX)
    override val supportedTimeframes: Set<Timeframe> = setOf(Timeframe.M15, Timeframe.H1)
    override val regimesAllowed: Set<Regime> = setOf(Regime.LOW_VOL, Regime.TREND)

    override fun warmupBars(): Int = 210

    override fun onBar(ctx: MarketContext): List<CandidateSignal> {
        if (ctx.instrument.assetClass != AssetClass.FX) return emptyList()
        if (ctx.timeframe !in supportedTimeframes) return emptyList()

        val bars = ctx.closedBars()
        if (bars.size < warmupBars()) return emptyList()
        val idx = bars.lastIndex
        val bar = bars[idx]

        val londonNow = SessionClock.londonZoned(bar.closeTimeEpochMs)
        val hour = londonNow.hour
        if (hour < params.asianEndHourLondon || hour >= params.entryEndHourLondon) {
            return emptyList()
        }

        val regimeSnap = RegimeEngine.evaluateAt(bars, idx, ctx.instrument, ctx.availableEvents(), ctx.riskConfig)
            ?: return emptyList()
        if (Regime.EVENT in regimeSnap.tags) return emptyList()
        if (regimeSnap.tags.none { it in regimesAllowed }) return emptyList()

        val todayLondon = londonNow.toLocalDate()
        val asianBars = bars.filter { c ->
            val z = SessionClock.londonZoned(c.openTimeEpochMs)
            z.toLocalDate() == todayLondon &&
                z.hour >= params.asianStartHourLondon &&
                z.hour < params.asianEndHourLondon
        }
        if (asianBars.size < 2) return emptyList()

        val asianHigh = asianBars.maxOf { it.high }
        val asianLow = asianBars.minOf { it.low }
        val rangeHeight = asianHigh - asianLow
        if (rangeHeight <= 0.0) return emptyList()

        // Daily ATR from closed daily bars (or approximate from intraday ATR * sqrt(bars/day))
        val closedDaily = ctx.closedDailyBars()
        val dailyAtr = if (closedDaily.size >= 15) {
            Indicators.atr(closedDaily, 14).lastOrNull() ?: (regimeSnap.atr * 4.0)
        } else {
            val scale = if (ctx.timeframe == Timeframe.M15) 6.5 else 3.8
            regimeSnap.atr * scale
        }
        if (dailyAtr <= 0.0) return emptyList()

        val ratio = rangeHeight / dailyAtr
        if (ratio < params.minRangeDailyAtrRatio || ratio > params.maxRangeDailyAtrRatio) {
            return emptyList()
        }

        // Enforce one attempt per pair per London day: check if an earlier bar today already closed outside the range
        val earlierBreakToday = bars.dropLast(1).any { c ->
            val z = SessionClock.londonZoned(c.closeTimeEpochMs)
            z.toLocalDate() == todayLondon &&
                z.hour >= params.asianEndHourLondon &&
                z.hour < params.entryEndHourLondon &&
                (c.close > asianHigh || c.close < asianLow)
        }
        if (earlierBreakToday) return emptyList()

        val direction = when {
            bar.close > asianHigh -> Direction.LONG
            bar.close < asianLow -> Direction.SHORT
            else -> return emptyList()
        }

        val entryMid = bar.close
        val bandHalf = max(ctx.instrument.pipSize * 1.5, 0.05 * regimeSnap.atr)
        // Stop at opposite side of Asian range or 1 daily ATR, whichever is closer
        val stopDist = min(
            if (direction == Direction.LONG) entryMid - asianLow else asianHigh - entryMid,
            dailyAtr
        ).coerceAtLeast(ctx.instrument.pipSize * 8.0)

        val stopLoss = if (direction == Direction.LONG) entryMid - stopDist else entryMid + stopDist
        val tp1 = if (direction == Direction.LONG) entryMid + params.tp1R * stopDist else entryMid - params.tp1R * stopDist
        val tp2 = if (direction == Direction.LONG) entryMid + params.tp2R * stopDist else entryMid - params.tp2R * stopDist

        val estCostR = ((ctx.instrument.baseSpreadPrice + entryMid * ctx.instrument.commissionPerUnitPct * 2.0 + 0.03 * regimeSnap.atr) / stopDist)
            .coerceIn(0.01, 0.99)

        val compressionScore = (1.0 - ((ratio - 0.25) / 0.55)).coerceIn(0.0, 1.0)
        val conviction = (0.48 + 0.32 * compressionScore + 0.20 * (if (Regime.TREND in regimeSnap.tags) 1.0 else 0.5))
            .coerceIn(0.1, 0.95)

        val nyCloseMs = SessionClock.newYorkCloseForLondonDay(todayLondon)
        val expiresAt = if (nyCloseMs > bar.closeTimeEpochMs) nyCloseMs else bar.closeTimeEpochMs + 12 * 3600_000L

        return listOf(
            CandidateSignal(
                instrument = ctx.instrument,
                timeframe = ctx.timeframe,
                barCloseEpochMs = bar.closeTimeEpochMs,
                direction = direction,
                strategyId = id,
                strategyVersion = version,
                configHash = configHash,
                regime = regimeSnap.primaryRegime,
                allRegimes = regimeSnap.tags,
                conviction = conviction,
                entryLow = entryMid - bandHalf,
                entryHigh = entryMid + bandHalf,
                stopLoss = stopLoss,
                tp1Price = tp1,
                tp1R = params.tp1R,
                tp1ClosePct = 50,
                tp2Price = tp2,
                tp2R = params.tp2R,
                tp2ClosePct = 50,
                estCostR = estCostR,
                expiresAtEpochMs = expiresAt,
                invalidation = "Close back inside the Asian range (${formatPrice(asianLow, ctx.instrument)} - ${formatPrice(asianHigh, ctx.instrument)})",
                rationale = "London open breakout above/below Asian range (${"%.2f".format(ratio)}x daily ATR).",
                featureSnapshot = mapOf(
                    "asianHigh" to asianHigh,
                    "asianLow" to asianLow,
                    "dailyAtrRatio" to ratio,
                    "dailyAtr" to dailyAtr
                ),
                atrAtSignal = regimeSnap.atr
            )
        )
    }

    private fun formatPrice(v: Double, ins: Instrument): String =
        if (ins.quoteCurrency == "JPY") "%.3f".format(v) else "%.5f".format(v)
}

data class S3Params(
    val fundingZThreshold: Double = 2.0,
    val oiRisingLookback: Int = 6,
    val structureBreakLookback: Int = 5,
    val tp1R: Double = 1.5,
    val tp2R: Double = 2.5
)

/**
 * S3 FUNDING_OI_CONTRARIAN: 1H and 4H, crypto perpetuals, regimes HIGH_VOL or RANGE.
 * Funding-rate z-score beyond +/-2 over rolling 90 days (or available funding history),
 * open interest rising while price stalls, entry on structure break against the crowd.
 * Hypothesis to validate honestly; failure in validation is an expected acceptable outcome.
 */
class S3FundingOiContrarianStrategy(
    private val params: S3Params = S3Params()
) : Strategy {
    override val id: String = "S3_FUNDING_OI_CONTRARIAN"
    override val displayName: String = "S3 Funding/OI Contrarian"
    override val version: String = "1.0.0"
    override val configHash: String = HashChainUtils.sha256Hex("S3:${params.fundingZThreshold}:${params.oiRisingLookback}:${params.structureBreakLookback}").take(12)
    override val hypothesis: String = "Crowded perpetual leverage reflected by extreme funding z-scores (+/-2) alongside rising open interest and stalling price unwinds sharply on an opposite structure break."
    override val supportedAssetClasses: Set<AssetClass> = setOf(AssetClass.CRYPTO)
    override val supportedTimeframes: Set<Timeframe> = setOf(Timeframe.H1, Timeframe.H4)
    override val regimesAllowed: Set<Regime> = setOf(Regime.HIGH_VOL, Regime.RANGE)

    override fun warmupBars(): Int = 210

    override fun onBar(ctx: MarketContext): List<CandidateSignal> {
        if (ctx.instrument.assetClass != AssetClass.CRYPTO) return emptyList()
        if (ctx.timeframe !in supportedTimeframes) return emptyList()

        val bars = ctx.closedBars()
        if (bars.size < warmupBars()) return emptyList()
        val funding = ctx.closedFunding()
        val oi = ctx.closedOpenInterest()
        if (funding.size < 20 || oi.size < params.oiRisingLookback + 1) return emptyList()

        val idx = bars.lastIndex
        val bar = bars[idx]
        val regimeSnap = RegimeEngine.evaluateAt(bars, idx, ctx.instrument, ctx.availableEvents(), ctx.riskConfig)
            ?: return emptyList()
        if (Regime.EVENT in regimeSnap.tags) return emptyList()
        if (regimeSnap.tags.none { it in regimesAllowed }) return emptyList()

        val fundingRates = funding.map { it.fundingRate }
        val fundingZ = Indicators.zScore(fundingRates, fundingRates.lastIndex, minOf(270, fundingRates.size))
            ?: return emptyList()
        if (abs(fundingZ) < params.fundingZThreshold) return emptyList()

        val oiWindow = oi.takeLast(params.oiRisingLookback)
        val oiRising = oiWindow.last().openInterest > oiWindow.first().openInterest * 1.01
        if (!oiRising) return emptyList()

        // Price stalling: 6-bar net return within +/- 1.5 ATR
        val priceRef = bars[max(0, idx - params.oiRisingLookback)].close
        val stalling = abs(bar.close - priceRef) <= 1.5 * regimeSnap.atr
        if (!stalling) return emptyList()

        val priorHigh = Indicators.priorDonchianHigh(bars, idx, params.structureBreakLookback) ?: return emptyList()
        val priorLow = Indicators.priorDonchianLow(bars, idx, params.structureBreakLookback) ?: return emptyList()

        // Positive extreme funding -> crowded longs -> look for short structure break
        val direction = when {
            fundingZ >= params.fundingZThreshold && bar.close < priorLow -> Direction.SHORT
            fundingZ <= -params.fundingZThreshold && bar.close > priorHigh -> Direction.LONG
            else -> return emptyList()
        }

        val entryMid = bar.close
        val bandHalf = 0.08 * regimeSnap.atr
        val swingExtreme = if (direction == Direction.SHORT) {
            bars.subList(max(0, idx - params.structureBreakLookback), idx + 1).maxOf { it.high } + 0.25 * regimeSnap.atr
        } else {
            bars.subList(max(0, idx - params.structureBreakLookback), idx + 1).minOf { it.low } - 0.25 * regimeSnap.atr
        }

        val stopDist = abs(swingExtreme - entryMid).coerceAtLeast(0.8 * regimeSnap.atr)
        val stopLoss = if (direction == Direction.LONG) entryMid - stopDist else entryMid + stopDist
        val tp1 = if (direction == Direction.LONG) entryMid + params.tp1R * stopDist else entryMid - params.tp1R * stopDist
        val tp2 = if (direction == Direction.LONG) entryMid + params.tp2R * stopDist else entryMid - params.tp2R * stopDist

        val estCostR = ((ctx.instrument.baseSpreadPrice + entryMid * ctx.instrument.commissionPerUnitPct * 2.0 + 0.08 * regimeSnap.atr) / stopDist)
            .coerceIn(0.01, 0.99)
        val conviction = (0.40 + 0.25 * ((abs(fundingZ) - 2.0) / 2.0).coerceIn(0.0, 1.0) + 0.20 * if (Regime.HIGH_VOL in regimeSnap.tags) 1.0 else 0.5)
            .coerceIn(0.1, 0.90)

        return listOf(
            CandidateSignal(
                instrument = ctx.instrument,
                timeframe = ctx.timeframe,
                barCloseEpochMs = bar.closeTimeEpochMs,
                direction = direction,
                strategyId = id,
                strategyVersion = version,
                configHash = configHash,
                regime = regimeSnap.primaryRegime,
                allRegimes = regimeSnap.tags,
                conviction = conviction,
                entryLow = entryMid - bandHalf,
                entryHigh = entryMid + bandHalf,
                stopLoss = stopLoss,
                tp1Price = tp1,
                tp1R = params.tp1R,
                tp1ClosePct = 60,
                tp2Price = tp2,
                tp2R = params.tp2R,
                tp2ClosePct = 40,
                estCostR = estCostR,
                expiresAtEpochMs = bar.closeTimeEpochMs + ctx.timeframe.durationMinutes * 60_000L * 4L,
                invalidation = "Close back above/below swing extreme with OI expanding",
                rationale = "Funding z-score ${"%.2f".format(fundingZ)} with rising OI and structure break against crowded positioning.",
                featureSnapshot = mapOf(
                    "fundingZ" to fundingZ,
                    "latestFunding" to fundingRates.last(),
                    "oiStart" to oiWindow.first().openInterest,
                    "oiEnd" to oiWindow.last().openInterest
                ),
                atrAtSignal = regimeSnap.atr
            )
        )
    }
}

object StrategyRegistry {
    val mvpStrategies: List<Strategy> = listOf(
        S1TrendDonchianStrategy(),
        S2SessionBreakoutStrategy(),
        S3FundingOiContrarianStrategy()
    )

    fun byId(id: String): Strategy? = mvpStrategies.firstOrNull { it.id == id }
}
