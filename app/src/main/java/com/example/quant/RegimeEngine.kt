package com.example.quant

import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.EconomicEvent
import com.example.domain.model.Instrument
import com.example.domain.model.Regime
import com.example.domain.model.RiskConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

object SessionClock {
    val londonZone: ZoneId = ZoneId.of("Europe/London")
    val newYorkZone: ZoneId = ZoneId.of("America/New_York")
    val utcZone: ZoneId = ZoneId.of("UTC")

    fun isFxMarketOpen(epochMs: Long): Boolean {
        val ny = Instant.ofEpochMilli(epochMs).atZone(newYorkZone)
        val day = ny.dayOfWeek
        val minutesOfDay = ny.hour * 60 + ny.minute
        return when (day) {
            DayOfWeek.SATURDAY -> false
            DayOfWeek.SUNDAY -> minutesOfDay >= 17 * 60
            DayOfWeek.FRIDAY -> minutesOfDay < 17 * 60
            else -> true
        }
    }

    /**
     * Daily FX rollover liquidity gap around 16:55 to 17:15 America/New_York.
     */
    fun isFxRolloverWindow(epochMs: Long): Boolean {
        val ny = Instant.ofEpochMilli(epochMs).atZone(newYorkZone)
        val minutesOfDay = ny.hour * 60 + ny.minute
        return minutesOfDay in (16 * 60 + 55)..(17 * 60 + 15)
    }

    fun isTripleSwapWednesday(epochMs: Long): Boolean {
        val ny = Instant.ofEpochMilli(epochMs).atZone(newYorkZone)
        return ny.dayOfWeek == DayOfWeek.WEDNESDAY && ny.hour == 17
    }

    fun londonZoned(epochMs: Long): ZonedDateTime =
        Instant.ofEpochMilli(epochMs).atZone(londonZone)

    fun newYorkCloseForLondonDay(londonDate: java.time.LocalDate): Long {
        val nyFivePm = ZonedDateTime.of(
            londonDate.year,
            londonDate.monthValue,
            londonDate.dayOfMonth,
            17,
            0,
            0,
            0,
            newYorkZone
        )
        return nyFivePm.toInstant().toEpochMilli()
    }
}

data class RegimeSnapshot(
    val tags: Set<Regime>,
    val primaryRegime: Regime,
    val adx: Double,
    val atr: Double,
    val atrPercentile: Double,
    val ema200: Double,
    val bollingerBandwidth: Double,
    val bollingerBandwidthMedian: Double
)

object RegimeEngine {

    fun evaluateAt(
        candles: List<Candle>,
        index: Int,
        instrument: Instrument,
        events: List<EconomicEvent>,
        riskConfig: RiskConfig
    ): RegimeSnapshot? {
        if (index < 200 || index >= candles.size) return null

        val closes = candles.subList(0, index + 1).map { it.close }
        val subCandles = candles.subList(0, index + 1)
        val ema200List = Indicators.ema(closes, 200)
        val atrList = Indicators.atr(subCandles, 14)
        val adxList = Indicators.adx(subCandles, 14)
        val bbList = Indicators.bollingerBands(closes, 20, 2.0)

        val ema200 = ema200List.lastOrNull() ?: return null
        val atrNow = atrList.lastOrNull() ?: return null
        val adxNow = adxList.lastOrNull() ?: return null
        val adxPrev = adxList.getOrNull(adxList.size - 2) ?: adxNow
        val bbNow = bbList.lastOrNull() ?: return null
        val bwList = bbList.map { it?.bandwidth }
        val bwMedian = Indicators.rollingMedian(bwList, bwList.lastIndex, 100) ?: bbNow.bandwidth
        val atrPct = Indicators.atrPercentile(atrList, atrList.lastIndex, 250) ?: 50.0

        val tags = linkedSetOf<Regime>()
        val closeNow = closes.last()

        // TREND: ADX > 20 and rising, price on one side of 200 EMA
        if (adxNow > 20.0 && adxNow >= adxPrev && absDiffPercent(closeNow, ema200) > 0.0005) {
            tags.add(Regime.TREND)
        }
        // RANGE: ADX < 20 and Bollinger bandwidth below rolling median
        if (adxNow < 20.0 && bbNow.bandwidth <= bwMedian) {
            tags.add(Regime.RANGE)
        }
        if (atrPct > 80.0) tags.add(Regime.HIGH_VOL)
        if (atrPct < 20.0) tags.add(Regime.LOW_VOL)

        val barCloseMs = candles[index].closeTimeEpochMs
        if (isInsideNewsBlackout(barCloseMs, instrument, events, riskConfig)) {
            tags.add(Regime.EVENT)
        }

        if (tags.isEmpty()) {
            // Default structural classification when transitional
            tags.add(if (adxNow >= 20.0) Regime.TREND else Regime.RANGE)
        }

        val primary = when {
            Regime.EVENT in tags -> Regime.EVENT
            Regime.HIGH_VOL in tags && Regime.TREND !in tags -> Regime.HIGH_VOL
            Regime.LOW_VOL in tags && Regime.TREND !in tags -> Regime.LOW_VOL
            Regime.TREND in tags -> Regime.TREND
            else -> Regime.RANGE
        }

        return RegimeSnapshot(
            tags = tags,
            primaryRegime = primary,
            adx = adxNow,
            atr = atrNow,
            atrPercentile = atrPct,
            ema200 = ema200,
            bollingerBandwidth = bbNow.bandwidth,
            bollingerBandwidthMedian = bwMedian
        )
    }

    fun isInsideNewsBlackout(
        nowEpochMs: Long,
        instrument: Instrument,
        events: List<EconomicEvent>,
        riskConfig: RiskConfig
    ): Boolean {
        val beforeMs = riskConfig.newsBlackoutBeforeMin * 60_000L
        val afterMs = riskConfig.newsBlackoutAfterMin * 60_000L
        return events.any { ev ->
            if (!ev.impact.equals("HIGH", ignoreCase = true)) return@any false
            // Only use events already scheduled/released properly within blackout window
            val affectsInstrument = if (instrument.assetClass == AssetClass.FX) {
                ev.currency == instrument.baseCurrency || ev.currency == instrument.quoteCurrency || ev.currency == "USD"
            } else {
                ev.currency == "USD"
            }
            if (!affectsInstrument) return@any false
            nowEpochMs in (ev.releaseEpochMs - beforeMs)..(ev.releaseEpochMs + afterMs)
        }
    }

    private fun absDiffPercent(a: Double, b: Double): Double =
        if (b == 0.0) 0.0 else kotlin.math.abs(a - b) / kotlin.math.abs(b)
}
