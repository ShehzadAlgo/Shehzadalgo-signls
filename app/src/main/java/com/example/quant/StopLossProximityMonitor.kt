package com.example.quant

import com.example.data.firestore.SignalRecord
import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.SignalStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

enum class AlertSeverity {
    WARNING,
    CRITICAL
}

data class RiskAlertEvent(
    val id: String,
    val symbol: String,
    val direction: Direction,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val currentPrice: Double,
    val stopPrice: Double,
    val distanceAtr: Double?,
    val distancePctOfRisk: Double,
    val timestampUtc: String
)

object StopLossProximityMonitor {

    /**
     * Evaluates open ledger positions (and an optional active dashboard setup) against current spot/candle prices
     * and volatility-adjusted ATR14 to trigger alerts when price approaches (<= 0.50 ATR or <= 25% of risk distance)
     * or breaches the stop-loss level.
     */
    fun evaluateOpenPositions(
        signals: List<SignalRecord>,
        candlesBySymbol: Map<String, List<Candle>>,
        spotRates: Map<String, Double>,
        activeSetup: ActiveStopWatchTarget? = null,
        approachAtrThreshold: Double = 0.50,
        approachRiskFractionThreshold: Double = 0.25
    ): List<RiskAlertEvent> {
        val nowUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            .substringAfter('T').take(5) + " UTC"
        val alerts = mutableListOf<RiskAlertEvent>()

        val openSignals = signals.filter { SignalStatus.fromWire(it.status).isOpen }
        for (sig in openSignals) {
            val dir = Direction.fromWire(sig.direction)
            val entry = (sig.entryLow + sig.entryHigh) / 2.0
            val candles = candlesBySymbol[sig.instrument].orEmpty().filter { it.isClosed }
            val currentPrice = candles.lastOrNull()?.close ?: spotRates[sig.instrument] ?: continue
            val atr14 = if (candles.size >= 15) Indicators.atr(candles, 14).lastOrNull { it != null } else null

            evaluateSingleTarget(
                idPrefix = "sig_${sig.id.ifBlank { sig.signalUuid }}",
                symbol = sig.instrument,
                direction = dir,
                entryPrice = entry,
                stopPrice = sig.stopLoss,
                currentPrice = currentPrice,
                atr14 = atr14,
                approachAtrThreshold = approachAtrThreshold,
                approachRiskFractionThreshold = approachRiskFractionThreshold,
                nowUtc = nowUtc
            )?.let { alerts.add(it) }
        }

        if (activeSetup != null && activeSetup.entryPrice > 0.0 && activeSetup.stopPrice > 0.0 && activeSetup.currentPrice > 0.0) {
            evaluateSingleTarget(
                idPrefix = "dash_${activeSetup.symbol}",
                symbol = activeSetup.symbol,
                direction = activeSetup.direction,
                entryPrice = activeSetup.entryPrice,
                stopPrice = activeSetup.stopPrice,
                currentPrice = activeSetup.currentPrice,
                atr14 = activeSetup.atr14,
                approachAtrThreshold = approachAtrThreshold,
                approachRiskFractionThreshold = approachRiskFractionThreshold,
                nowUtc = nowUtc
            )?.let { dashAlert ->
                if (alerts.none { it.symbol == dashAlert.symbol && it.severity == dashAlert.severity }) {
                    alerts.add(dashAlert)
                }
            }
        }

        return alerts.sortedByDescending { it.severity == AlertSeverity.CRITICAL }
    }

    private fun evaluateSingleTarget(
        idPrefix: String,
        symbol: String,
        direction: Direction,
        entryPrice: Double,
        stopPrice: Double,
        currentPrice: Double,
        atr14: Double?,
        approachAtrThreshold: Double,
        approachRiskFractionThreshold: Double,
        nowUtc: String
    ): RiskAlertEvent? {
        val totalRiskDist = abs(entryPrice - stopPrice)
        if (totalRiskDist <= 0.0) return null

        val signedBufferToStop = if (direction == Direction.LONG) {
            currentPrice - stopPrice
        } else {
            stopPrice - currentPrice
        }

        val fracRemaining = signedBufferToStop / totalRiskDist
        val distAtr = if (atr14 != null && atr14 > 0.0) signedBufferToStop / atr14 else null

        if (signedBufferToStop <= 0.0) {
            return RiskAlertEvent(
                id = "${idPrefix}_BREACH",
                symbol = symbol,
                direction = direction,
                severity = AlertSeverity.CRITICAL,
                title = "STOP BREACHED: $symbol ${direction.name}",
                message = "$symbol (${formatPrice(currentPrice)}) breached vol-adjusted SL ${formatPrice(stopPrice)}.",
                currentPrice = currentPrice,
                stopPrice = stopPrice,
                distanceAtr = distAtr,
                distancePctOfRisk = (fracRemaining * 100.0).coerceAtLeast(0.0),
                timestampUtc = nowUtc
            )
        }

        val closeByAtr = distAtr != null && distAtr <= approachAtrThreshold
        val closeByPct = fracRemaining <= approachRiskFractionThreshold
        if (closeByAtr || closeByPct) {
            val atrStr = distAtr?.let { "${"%.2f".format(it)}x ATR" } ?: "${"%.0f".format(fracRemaining * 100)}%"
            return RiskAlertEvent(
                id = "${idPrefix}_APPROACH",
                symbol = symbol,
                direction = direction,
                severity = AlertSeverity.WARNING,
                title = "SL APPROACHING: $symbol ${direction.name}",
                message = "$symbol (${formatPrice(currentPrice)}) is $atrStr from SL ${formatPrice(stopPrice)}.",
                currentPrice = currentPrice,
                stopPrice = stopPrice,
                distanceAtr = distAtr,
                distancePctOfRisk = (fracRemaining * 100.0).coerceIn(0.0, 100.0),
                timestampUtc = nowUtc
            )
        }

        return null
    }

    private fun formatPrice(price: Double): String = when {
        price >= 1000.0 -> "%.2f".format(price)
        price >= 20.0 -> "%.3f".format(price)
        else -> "%.5f".format(price)
    }
}

data class ActiveStopWatchTarget(
    val symbol: String,
    val direction: Direction,
    val entryPrice: Double,
    val stopPrice: Double,
    val currentPrice: Double,
    val atr14: Double?
)
