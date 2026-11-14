package com.example.quant

import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Instrument
import com.example.domain.model.QualityIssueType
import com.example.domain.model.Timeframe
import kotlin.math.abs

data class QualityCheckResult(
    val closedCandles: List<Candle>,
    val issues: List<DataQualityIssue>,
    val isHealthy: Boolean
)

object DataQualityChecker {

    /**
     * Enforces closed-bar discipline: drops any in-progress trailing candle whose closeTime > nowEpochMs.
     * Then checks duplicates, non-monotonic timestamps, gaps, >10 ATR spikes, zero-volume runs, and staleness.
     */
    fun validateAndFilterClosed(
        rawCandles: List<Candle>,
        instrument: Instrument,
        timeframe: Timeframe,
        nowEpochMs: Long,
        feedMaxLagSeconds: Int,
        checkStaleness: Boolean = true
    ): QualityCheckResult {
        val issues = mutableListOf<DataQualityIssue>()
        val closed = rawCandles
            .filter { it.closeTimeEpochMs <= nowEpochMs && it.isClosed }

        if (closed.size < 25) {
            issues.add(
                DataQualityIssue(
                    instrument = instrument.symbol,
                    timeframe = timeframe,
                    issueType = QualityIssueType.INSUFFICIENT_BARS,
                    description = "Only ${closed.size} closed bars available; insufficient history.",
                    detectedAtEpochMs = nowEpochMs,
                    blocksSignals = true
                )
            )
            return QualityCheckResult(closed, issues, isHealthy = false)
        }

        // Check monotonic timestamps & duplicates
        val seenTimes = HashSet<Long>(closed.size)
        var nonMonotonicCount = 0
        var duplicateCount = 0
        for (i in closed.indices) {
            val t = closed[i].openTimeEpochMs
            if (!seenTimes.add(t)) duplicateCount++
            if (i > 0 && t <= closed[i - 1].openTimeEpochMs) nonMonotonicCount++
        }
        if (duplicateCount > 0) {
            issues.add(
                DataQualityIssue(
                    instrument = instrument.symbol,
                    timeframe = timeframe,
                    issueType = QualityIssueType.DUPLICATE_TIMESTAMP,
                    description = "Detected $duplicateCount duplicate candle timestamps.",
                    detectedAtEpochMs = nowEpochMs,
                    blocksSignals = true
                )
            )
        }
        if (nonMonotonicCount > 0) {
            issues.add(
                DataQualityIssue(
                    instrument = instrument.symbol,
                    timeframe = timeframe,
                    issueType = QualityIssueType.NON_MONOTONIC,
                    description = "Detected $nonMonotonicCount non-monotonic timestamps.",
                    detectedAtEpochMs = nowEpochMs,
                    blocksSignals = true
                )
            )
        }

        val sortedUnique = closed.distinctBy { it.openTimeEpochMs }.sortedBy { it.openTimeEpochMs }
        val expectedStepMs = timeframe.durationMinutes * 60_000L
        var largeGapCount = 0
        for (i in 1 until sortedUnique.size) {
            val delta = sortedUnique[i].openTimeEpochMs - sortedUnique[i - 1].openTimeEpochMs
            // Allow FX weekend breaks (up to 56h) without flagging as missing intraday feed
            val maxAllowed = if (instrument.assetClass == AssetClass.FX) {
                maxOf(expectedStepMs * 3, 56L * 3600_000L)
            } else {
                expectedStepMs * 3
            }
            if (delta > maxAllowed) largeGapCount++
        }
        if (largeGapCount > 0) {
            issues.add(
                DataQualityIssue(
                    instrument = instrument.symbol,
                    timeframe = timeframe,
                    issueType = QualityIssueType.GAP,
                    description = "Detected $largeGapCount abnormal data gap(s) exceeding allowed interval.",
                    detectedAtEpochMs = nowEpochMs,
                    blocksSignals = false // Warn unless recent gap
                )
            )
        }

        // Price spike > 10 ATR check on recent bars
        val atrSeries = Indicators.atr(sortedUnique, 14)
        var spikeDetected = false
        for (i in 20 until sortedUnique.size) {
            val atr = atrSeries[i - 1] ?: continue
            if (atr > 0.0) {
                val barMove = abs(sortedUnique[i].close - sortedUnique[i - 1].close)
                if (barMove > 10.0 * atr) {
                    spikeDetected = true
                    break
                }
            }
        }
        if (spikeDetected) {
            issues.add(
                DataQualityIssue(
                    instrument = instrument.symbol,
                    timeframe = timeframe,
                    issueType = QualityIssueType.PRICE_SPIKE_10ATR,
                    description = "Price spike exceeding 10 ATR detected in series.",
                    detectedAtEpochMs = nowEpochMs,
                    blocksSignals = true
                )
            )
        }

        // Zero-volume run check (crypto only, since spot FX often reports tick volume = 0)
        if (instrument.assetClass == AssetClass.CRYPTO && sortedUnique.size >= 10) {
            val tailZeroVol = sortedUnique.takeLast(6).all { it.volume <= 0.0 }
            if (tailZeroVol) {
                issues.add(
                    DataQualityIssue(
                        instrument = instrument.symbol,
                        timeframe = timeframe,
                        issueType = QualityIssueType.ZERO_VOLUME_RUN,
                        description = "6+ consecutive zero-volume bars on crypto perpetual feed.",
                        detectedAtEpochMs = nowEpochMs,
                        blocksSignals = true
                    )
                )
            }
        }

        // Stale feed check
        if (checkStaleness) {
            val latestClose = sortedUnique.last().closeTimeEpochMs
            val lagSeconds = (nowEpochMs - latestClose) / 1000L
            // A closed bar can be up to one bar duration + feedMaxLagSeconds old before next bar closes
            val maxExpectedAgeSec = timeframe.durationMinutes * 60L + feedMaxLagSeconds
            if (lagSeconds > maxExpectedAgeSec && (instrument.assetClass == AssetClass.CRYPTO || SessionClock.isFxMarketOpen(nowEpochMs))) {
                issues.add(
                    DataQualityIssue(
                        instrument = instrument.symbol,
                        timeframe = timeframe,
                        issueType = QualityIssueType.STALE_FEED,
                        description = "Feed lag ${lagSeconds}s exceeds threshold (${maxExpectedAgeSec}s).",
                        detectedAtEpochMs = nowEpochMs,
                        blocksSignals = true
                    )
                )
            }
        }

        val blocking = issues.any { it.blocksSignals }
        return QualityCheckResult(sortedUnique, issues, isHealthy = !blocking)
    }
}
