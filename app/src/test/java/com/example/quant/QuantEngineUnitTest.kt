package com.example.quant

import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.RiskConfig
import com.example.domain.model.RiskStatus
import com.example.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class QuantEngineUnitTest {

    private fun buildSyntheticTrendSeries(count: Int, stepMs: Long = 4 * 3600_000L): List<Candle> {
        val start = 1760000000000L
        var price = 1.0800
        return (0 until count).map { i ->
            val drift = if (i < count - 5) 0.0002 else 0.0025
            val noise = if (i % 2 == 0) 0.0004 else -0.0002
            val open = price
            val close = open + drift + noise
            val high = maxOf(open, close) + 0.0008
            val low = minOf(open, close) - 0.0006
            price = close
            Candle(
                openTimeEpochMs = start + i * stepMs,
                closeTimeEpochMs = start + (i + 1) * stepMs,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 1200.0 + i,
                isClosed = true
            )
        }
    }

    @Test
    fun closedBarFilter_dropsUnclosedTrailingBar() {
        val eurusd = InstrumentUniverse.find("EURUSD")!!
        val series = buildSyntheticTrendSeries(40, 3600_000L)
        val nowMs = series[series.size - 2].closeTimeEpochMs + 10_000L
        val res = DataQualityChecker.validateAndFilterClosed(
            rawCandles = series,
            instrument = eurusd,
            timeframe = Timeframe.H1,
            nowEpochMs = nowMs,
            feedMaxLagSeconds = 120,
            checkStaleness = false
        )
        assertEquals(39, res.closedCandles.size)
        assertTrue(res.closedCandles.all { it.closeTimeEpochMs <= nowMs })
    }

    @Test
    fun positionSizing_convertsJpyCrossToUsdAndCapsCryptoLeverage() {
        val usdjpy = InstrumentUniverse.find("USDJPY")!!
        val spotRates = mapOf("USDJPY" to 150.0)
        // $10,000 * 0.5% = $50 risk. Stop = 0.50 JPY (50 pips).
        // 1 lot USDJPY = 100,000 * (1/150) USD per 1.0 JPY = $666.67 per 1.0 JPY.
        // 0.50 JPY stop = $333.33 per lot -> $50 / $333.33 = 0.15 lots.
        val jpySizing = RiskEngine.calculatePositionSize(
            instrument = usdjpy,
            accountBalanceUsd = 10_000.0,
            riskPct = 0.5,
            entryPrice = 150.0,
            stopLoss = 149.50,
            spotRates = spotRates
        )
        assertTrue(jpySizing.valid)
        assertEquals(0.15, jpySizing.roundedSize, 0.01)

        val btc = InstrumentUniverse.find("BTCUSDT")!!
        val btcSizing = RiskEngine.calculatePositionSize(
            instrument = btc,
            accountBalanceUsd = 10_000.0,
            riskPct = 0.5,
            entryPrice = 90_000.0,
            stopLoss = 88_200.0, // 2% stop
            spotRates = spotRates,
            minLiquidationStops = 3
        )
        assertTrue(btcSizing.valid)
        assertNotNull(btcSizing.suggestedMaxLeverage)
        assertTrue(btcSizing.estLiquidationDistanceStops!! >= 2.99)
    }

    @Test
    fun riskEngine_enforcesKillSwitchAndCurrencyNetting() {
        val eurusd = InstrumentUniverse.find("EURUSD")!!
        val series = buildSyntheticTrendSeries(260)
        val s1 = S1TrendDonchianStrategy()
        val ctx = MarketContext(
            instrument = eurusd,
            timeframe = Timeframe.H4,
            nowEpochMs = series.last().closeTimeEpochMs,
            candles = series
        )
        val cand = s1.onBar(ctx).firstOrNull()
        if (cand != null) {
            val halted = RiskEngine.evaluate(
                candidate = cand,
                config = RiskConfig(manualKillSwitch = true),
                portfolio = PortfolioRiskState(),
                spotRates = emptyMap(),
                nowEpochMs = series.last().closeTimeEpochMs
            )
            assertEquals(RiskStatus.REJECTED, halted.status)
            assertTrue(halted.reasons.any { it.contains("KILL_SWITCH_ACTIVE") })
        }
        val netMap = RiskEngine.computeCurrencyNetExposurePct(
            listOf(
                OpenExposureItem(eurusd, Direction.LONG, 0.5)
            )
        )
        assertEquals(0.5, netMap["EUR"]!!, 1e-6)
        assertEquals(-0.5, netMap["USD"]!!, 1e-6)
    }

    @Test
    fun sessionClock_handlesDstLondonAndRollover() {
        // 16:58 NY time on a Wednesday should be inside rollover window and FX market open
        val nyWednesdayRollover = ZonedDateTime.of(2026, 11, 11, 16, 58, 0, 0, SessionClock.newYorkZone)
            .toInstant().toEpochMilli()
        assertTrue(SessionClock.isFxMarketOpen(nyWednesdayRollover))
        assertTrue(SessionClock.isFxRolloverWindow(nyWednesdayRollover))

        // Saturday should be closed
        val nySaturday = ZonedDateTime.of(2026, 11, 14, 12, 0, 0, 0, SessionClock.newYorkZone)
            .toInstant().toEpochMilli()
        assertFalse(SessionClock.isFxMarketOpen(nySaturday))
    }

    @Test
    fun causalityGate_truncatedHistoryMatchesFullHistory() {
        val eurusd = InstrumentUniverse.find("EURUSD")!!
        val series = buildSyntheticTrendSeries(280)
        val s1 = S1TrendDonchianStrategy()
        assertTrue(BacktestEngine.verifyCausality(s1, eurusd, Timeframe.H4, series))
    }
}
