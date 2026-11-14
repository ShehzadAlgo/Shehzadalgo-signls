package com.example.quant

import com.example.data.firestore.SignalRecord
import com.example.domain.model.InstrumentUniverse
import kotlin.math.abs

object TelegramFormatter {

    fun formatSignal(signal: SignalRecord, indexLabel: String = signal.signalUuid.take(4).uppercase()): String {
        val ins = InstrumentUniverse.find(signal.instrument)
        val pipSize = ins?.pipSize ?: 0.0001
        val decimals = if (ins?.quoteCurrency == "JPY") 3 else if (signal.assetClass == "crypto") 2 else 5
        val fmt: (Double) -> String = { "%.${decimals}f".format(it) }

        val entryMid = (signal.entryLow + signal.entryHigh) / 2.0
        val stopDist = abs(entryMid - signal.stopLoss)
        val stopUnits = (stopDist / pipSize).toInt().coerceAtLeast(1)
        val unitName = if (signal.assetClass == "fx") "pips" else "pts"
        val expiryShort = signal.expiresAtIso.substringAfter('T').take(5) + " UTC"
        val regimeLabel = signal.regime.replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }

        return buildString {
            appendLine("SIGNAL #$indexLabel | ${signal.instrument} ${signal.direction.uppercase()} | ${signal.strategy} (${signal.executionMode})")
            appendLine("Entry zone: ${fmt(signal.entryLow)} - ${fmt(signal.entryHigh)}")
            appendLine("Stop: ${fmt(signal.stopLoss)} ($stopUnits $unitName)")
            appendLine("TP1: ${fmt(signal.tp1Price)} (${signal.tp1R}R, close ${signal.tp1ClosePct}%) | TP2: ${fmt(signal.tp2Price)} (${signal.tp2R}R)")
            appendLine("Suggested risk: ${"%.2f".format(signal.suggestedRiskPct)}% | Expires: $expiryShort")
            appendLine("Regime: $regimeLabel | Est. cost: ${"%.2f".format(signal.estCostR)}R")
            appendLine("Why: ${signal.rationale}")
            append("Not financial advice. Trading involves risk of loss.")
        }
    }

    fun formatDailyDigest(latestHash: String, eventCount: Int, dateUtc: String, netPaperR: Double): String =
        "SHEHZAD ALGO DAILY LEDGER DIGEST | $dateUtc UTC\n" +
            "Events: $eventCount | Paper Net R: ${"%.2f".format(netPaperR)}R\n" +
            "Chain Head SHA-256: $latestHash\n" +
            "Not financial advice. Past or paper performance does not guarantee future results."
}
