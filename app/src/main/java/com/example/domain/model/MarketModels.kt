package com.example.domain.model

import java.time.Instant

enum class AssetClass(val wire: String) {
    FX("fx"),
    CRYPTO("crypto");

    companion object {
        fun fromWire(value: String): AssetClass =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: FX
    }
}

enum class Timeframe(val wire: String, val durationMinutes: Long) {
    M15("15m", 15L),
    H1("1H", 60L),
    H4("4H", 240L),
    D1("1D", 1440L);

    companion object {
        fun fromWire(value: String): Timeframe =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: H1
    }
}

enum class Direction(val wire: String) {
    LONG("long"),
    SHORT("short");

    companion object {
        fun fromWire(value: String): Direction =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: LONG
    }
}

enum class Regime {
    TREND,
    RANGE,
    HIGH_VOL,
    LOW_VOL,
    EVENT
}

enum class RiskStatus(val wire: String) {
    APPROVED("APPROVED"),
    REDUCED("REDUCED"),
    REJECTED("REJECTED");

    companion object {
        fun fromWire(value: String): RiskStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: REJECTED
    }
}

enum class SignalStatus(val wire: String) {
    PENDING("PENDING"),
    TRIGGERED("TRIGGERED"),
    TP1_HIT("TP1_HIT"),
    WIN("WIN"),
    LOSS("LOSS"),
    BREAKEVEN("BREAKEVEN"),
    EXPIRED("EXPIRED"),
    CANCELLED("CANCELLED");

    val isTerminal: Boolean
        get() = this in setOf(WIN, LOSS, BREAKEVEN, EXPIRED, CANCELLED)

    val isOpen: Boolean
        get() = this in setOf(PENDING, TRIGGERED, TP1_HIT)

    companion object {
        fun fromWire(value: String): SignalStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: PENDING
    }
}

enum class ExecutionMode(val wire: String) {
    PAPER("PAPER"),
    LIVE("LIVE"),
    BACKTEST("BACKTEST");

    companion object {
        fun fromWire(value: String): ExecutionMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: PAPER
    }
}

enum class StrategyLifecycle(val wire: String) {
    DRAFT("DRAFT"),
    BACKTESTED("BACKTESTED"),
    PAPER("PAPER"),
    LIVE("LIVE"),
    SUSPENDED("SUSPENDED"),
    REJECTED("REJECTED");

    companion object {
        fun fromWire(value: String): StrategyLifecycle =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: DRAFT
    }
}

data class Instrument(
    val symbol: String,
    val displayName: String,
    val assetClass: AssetClass,
    val baseCurrency: String,
    val quoteCurrency: String,
    val tickSize: Double,
    val pipSize: Double,
    val contractSize: Double,
    val minLot: Double,
    val lotStep: Double,
    val baseSpreadPrice: Double,
    val commissionPerUnitPct: Double,
    val cryptoCluster: String = "",
    val yahooTicker: String = "",
    val binanceSymbol: String = "",
    val bybitSymbol: String = ""
)

object InstrumentUniverse {
    val fxPairs: List<Instrument> = listOf(
        Instrument("EURUSD", "EUR/USD", AssetClass.FX, "EUR", "USD", 0.00001, 0.0001, 100_000.0, 0.01, 0.01, 0.00012, 0.000035, yahooTicker = "EURUSD=X"),
        Instrument("GBPUSD", "GBP/USD", AssetClass.FX, "GBP", "USD", 0.00001, 0.0001, 100_000.0, 0.01, 0.01, 0.00015, 0.000035, yahooTicker = "GBPUSD=X"),
        Instrument("USDJPY", "USD/JPY", AssetClass.FX, "USD", "JPY", 0.001, 0.01, 100_000.0, 0.01, 0.01, 0.014, 0.000035, yahooTicker = "USDJPY=X"),
        Instrument("AUDUSD", "AUD/USD", AssetClass.FX, "AUD", "USD", 0.00001, 0.0001, 100_000.0, 0.01, 0.01, 0.00014, 0.000035, yahooTicker = "AUDUSD=X"),
        Instrument("USDCAD", "USD/CAD", AssetClass.FX, "USD", "CAD", 0.00001, 0.0001, 100_000.0, 0.01, 0.01, 0.00016, 0.000035, yahooTicker = "USDCAD=X"),
        Instrument("USDCHF", "USD/CHF", AssetClass.FX, "USD", "CHF", 0.00001, 0.0001, 100_000.0, 0.01, 0.01, 0.00016, 0.000035, yahooTicker = "USDCHF=X"),
        Instrument("NZDUSD", "NZD/USD", AssetClass.FX, "NZD", "USD", 0.00001, 0.0001, 100_000.0, 0.01, 0.01, 0.00018, 0.000035, yahooTicker = "NZDUSD=X"),
        Instrument("EURJPY", "EUR/JPY", AssetClass.FX, "EUR", "JPY", 0.001, 0.01, 100_000.0, 0.01, 0.01, 0.018, 0.000035, yahooTicker = "EURJPY=X"),
        Instrument("GBPJPY", "GBP/JPY", AssetClass.FX, "GBP", "JPY", 0.001, 0.01, 100_000.0, 0.01, 0.01, 0.024, 0.000035, yahooTicker = "GBPJPY=X"),
        Instrument("XAUUSD", "XAU/USD", AssetClass.FX, "XAU", "USD", 0.01, 0.1, 100.0, 0.01, 0.01, 0.30, 0.00004, yahooTicker = "GC=F")
    )

    val cryptoPerps: List<Instrument> = listOf(
        Instrument("BTCUSDT", "BTC/USDT Perp", AssetClass.CRYPTO, "BTC", "USDT", 0.1, 1.0, 1.0, 0.001, 0.001, 0.5, 0.0005, cryptoCluster = "BTC_CORE", binanceSymbol = "BTCUSDT", bybitSymbol = "BTCUSDT"),
        Instrument("ETHUSDT", "ETH/USDT Perp", AssetClass.CRYPTO, "ETH", "USDT", 0.01, 0.1, 1.0, 0.01, 0.01, 0.15, 0.0005, cryptoCluster = "SMART_CONTRACT", binanceSymbol = "ETHUSDT", bybitSymbol = "ETHUSDT"),
        Instrument("SOLUSDT", "SOL/USDT Perp", AssetClass.CRYPTO, "SOL", "USDT", 0.001, 0.01, 1.0, 0.1, 0.1, 0.02, 0.0005, cryptoCluster = "ALT_L1", binanceSymbol = "SOLUSDT", bybitSymbol = "SOLUSDT"),
        Instrument("BNBUSDT", "BNB/USDT Perp", AssetClass.CRYPTO, "BNB", "USDT", 0.01, 0.1, 1.0, 0.01, 0.01, 0.08, 0.0005, cryptoCluster = "EXCHANGE", binanceSymbol = "BNBUSDT", bybitSymbol = "BNBUSDT"),
        Instrument("XRPUSDT", "XRP/USDT Perp", AssetClass.CRYPTO, "XRP", "USDT", 0.0001, 0.001, 1.0, 1.0, 1.0, 0.0003, 0.0005, cryptoCluster = "PAYMENTS", binanceSymbol = "XRPUSDT", bybitSymbol = "XRPUSDT")
    )

    val all: List<Instrument> = fxPairs + cryptoPerps

    fun find(symbol: String): Instrument? =
        all.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
}

data class Candle(
    val openTimeEpochMs: Long,
    val closeTimeEpochMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val isClosed: Boolean = true
) {
    val openInstant: Instant get() = Instant.ofEpochMilli(openTimeEpochMs)
    val closeInstant: Instant get() = Instant.ofEpochMilli(closeTimeEpochMs)
}

data class FundingPoint(
    val timestampEpochMs: Long,
    val fundingRate: Double
)

data class OpenInterestPoint(
    val timestampEpochMs: Long,
    val openInterest: Double
)

data class EconomicEvent(
    val id: String,
    val title: String,
    val currency: String,
    val impact: String,
    val periodDateIso: String,
    val releaseEpochMs: Long
)

enum class QualityIssueType {
    GAP,
    DUPLICATE_TIMESTAMP,
    NON_MONOTONIC,
    PRICE_SPIKE_10ATR,
    ZERO_VOLUME_RUN,
    STALE_FEED,
    INSUFFICIENT_BARS
}

data class DataQualityIssue(
    val instrument: String,
    val timeframe: Timeframe,
    val issueType: QualityIssueType,
    val description: String,
    val detectedAtEpochMs: Long,
    val blocksSignals: Boolean = true
)

data class RiskConfig(
    val accountBalanceUsd: Double = 10_000.0,
    val riskPerTradePct: Double = 0.5,
    val maxOpenRiskPct: Double = 3.0,
    val maxClusterRiskPct: Double = 1.5,
    val maxOpenPerInstrument: Int = 1,
    val dailyLossLimitPct: Double = 2.0,
    val weeklyLossLimitPct: Double = 4.0,
    val drawdownHalvePct: Double = 5.0,
    val drawdownHaltPct: Double = 8.0,
    val maxCostToStopRatio: Double = 0.15,
    val minLiquidationStops: Int = 3,
    val newsBlackoutBeforeMin: Int = 30,
    val newsBlackoutAfterMin: Int = 15,
    val feedMaxLagSeconds: Int = 60,
    val manualKillSwitch: Boolean = false
)

data class CandidateSignal(
    val instrument: Instrument,
    val timeframe: Timeframe,
    val barCloseEpochMs: Long,
    val direction: Direction,
    val strategyId: String,
    val strategyVersion: String,
    val configHash: String,
    val regime: Regime,
    val allRegimes: Set<Regime>,
    val conviction: Double,
    val entryLow: Double,
    val entryHigh: Double,
    val stopLoss: Double,
    val tp1Price: Double,
    val tp1R: Double,
    val tp1ClosePct: Int,
    val tp2Price: Double,
    val tp2R: Double,
    val tp2ClosePct: Int,
    val estCostR: Double,
    val expiresAtEpochMs: Long,
    val invalidation: String,
    val rationale: String,
    val featureSnapshot: Map<String, Double> = emptyMap(),
    val atrAtSignal: Double
)

data class RiskDecision(
    val status: RiskStatus,
    val approvedRiskPct: Double,
    val adjustments: List<String>,
    val reasons: List<String>
)

data class PositionSizingResult(
    val instrument: String,
    val assetClass: AssetClass,
    val accountBalanceUsd: Double,
    val riskPct: Double,
    val riskAmountUsd: Double,
    val stopDistancePrice: Double,
    val stopPipsOrPoints: Double,
    val unitValuePerPriceInUsd: Double,
    val rawSize: Double,
    val roundedSize: Double,
    val sizeUnitLabel: String,
    val notionalUsd: Double,
    val suggestedMaxLeverage: Double?,
    val estLiquidationDistanceStops: Double?,
    val valid: Boolean,
    val rejectionReason: String? = null
)

data class LedgerStats(
    val mode: ExecutionMode,
    val totalTrades: Int,
    val closedTrades: Int,
    val openSignals: Int,
    val winCount: Int,
    val lossCount: Int,
    val winRatePct: Double,
    val netR: Double,
    val avgR: Double,
    val expectancyR: Double,
    val profitFactor: Double,
    val maxDrawdownR: Double,
    val sharpeApprox: Double,
    val equityCurveR: List<Double>,
    val drawdownCurveR: List<Double>
)
