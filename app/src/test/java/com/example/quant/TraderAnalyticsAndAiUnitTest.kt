package com.example.quant

import com.example.data.ai.AiVerificationParser
import com.example.data.ai.MarketSentimentParser
import com.example.data.ai.PairAnalysisParser
import com.example.data.ai.PairBias
import com.example.data.ai.RiskPostureRecommendation
import com.example.data.ai.SentimentRegime
import com.example.data.ai.VerificationVerdict
import com.example.data.firestore.SignalRecord
import com.example.data.market.MarketDataClient
import com.example.data.market.MarketNewsItem
import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Direction
import com.example.domain.model.EconomicEvent
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.QualityIssueType
import com.example.domain.model.RiskConfig
import com.example.domain.model.RiskStatus
import com.example.domain.model.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraderAnalyticsAndAiUnitTest {

    private fun buildTrendCandles(count: Int, basePrice: Double = 1.0800, stepMs: Long = 3600_000L): List<Candle> {
        val start = 1760000000000L
        var price = basePrice
        return (0 until count).map { i ->
            val drift = 0.0004
            val wave = if (i % 3 == 0) -0.0001 else 0.0002
            val open = price
            val close = open + drift + wave
            val high = maxOf(open, close) + 0.0005
            val low = minOf(open, close) - 0.0004
            price = close
            Candle(
                openTimeEpochMs = start + i * stepMs,
                closeTimeEpochMs = start + (i + 1) * stepMs,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = 1000.0 + i * 5.0,
                isClosed = true
            )
        }
    }

    private fun sampleSignal(
        instrument: String = "EURUSD",
        assetClass: String = "fx",
        direction: String = "long",
        entry: Double = 1.1500,
        stop: Double = 1.1450,
        tp1: Double = 1.1600,
        tp2: Double = 1.1700,
        riskPct: Double = 0.5
    ): SignalRecord = SignalRecord(
        id = "sig_test_1",
        userId = "user_1",
        signalUuid = "uuid-test-1",
        createdAtIso = "2026-11-14T10:00:00Z",
        instrument = instrument,
        assetClass = assetClass,
        direction = direction,
        strategy = "S1_TREND_DONCHIAN",
        strategyVersion = "1.0.0",
        configHash = "cfg123",
        timeframe = "1h",
        regime = "TREND",
        conviction = 0.78,
        entryType = "zone",
        entryLow = entry - 0.0002,
        entryHigh = entry + 0.0002,
        stopLoss = stop,
        tp1Price = tp1,
        tp1R = 2.0,
        tp1ClosePct = 50L,
        tp2Price = tp2,
        tp2R = 4.0,
        tp2ClosePct = 50L,
        suggestedRiskPct = riskPct,
        estCostR = 0.08,
        expiresAtIso = "2026-11-14T22:00:00Z",
        invalidation = "Close below 20-bar channel",
        rationale = "Strong breakout with ADX > 25",
        riskStatus = "APPROVED",
        riskAdjustments = emptyList(),
        riskReasons = listOf("ALL_RISK_GATES_PASSED"),
        idempotencyKey = "S1|$instrument|1h|1760000000000",
        executionMode = "PAPER",
        status = "PENDING"
    )

    @Test
    fun rsiIndicator_handlesUptrendAndDowntrendBounds() {
        val up = (1..30).map { 100.0 + it * 1.5 }
        val rsiUp = Indicators.rsi(up, 14).lastOrNull()
        assertNotNull(rsiUp)
        assertEquals(100.0, rsiUp!!, 0.01)

        val down = (1..30).map { 200.0 - it * 1.5 }
        val rsiDown = Indicators.rsi(down, 14).lastOrNull()
        assertNotNull(rsiDown)
        assertEquals(0.0, rsiDown!!, 0.01)
    }

    @Test
    fun marketAnalyticsEngine_computesIndicatorsSizingAndPortfolioHeat() {
        val candles = buildTrendCandles(240)
        val lastClose = candles.last().close
        val signal = sampleSignal(
            entry = lastClose,
            stop = lastClose - 0.0030,
            tp1 = lastClose + 0.0060,
            tp2 = lastClose + 0.0120,
            riskPct = 0.5
        )
        val existingOpen = sampleSignal(instrument = "GBPUSD", direction = "long", riskPct = 0.5)
            .copy(id = "sig_open_2", signalUuid = "uuid-open-2", status = "TRIGGERED")

        val snapshot = MarketAnalyticsEngine.compute(
            signal = signal,
            candles = candles,
            riskConfig = RiskConfig(accountBalanceUsd = 20_000.0, riskPerTradePct = 0.5),
            allSignals = listOf(existingOpen, signal),
            spotRates = mapOf("EURUSD" to lastClose, "GBPUSD" to 1.27),
            qualityIssues = emptyList()
        )

        assertEquals(240, snapshot.barsAnalyzed)
        assertTrue(snapshot.trendBias.contains("BULLISH"))
        assertNotNull(snapshot.ema20)
        assertNotNull(snapshot.ema50)
        assertNotNull(snapshot.ema200)
        assertNotNull(snapshot.rsi14)
        assertNotNull(snapshot.adx14)
        assertNotNull(snapshot.atr14)
        assertNotNull(snapshot.stopDistanceAtrMultiple)
        assertTrue(snapshot.stopDistanceAtrMultiple!! > 0.0)
        assertEquals(25.0, snapshot.rewardToCostRatio, 0.01)
        assertTrue(snapshot.sizingResult.valid)
        assertEquals(100.0, snapshot.sizingResult.riskAmountUsd, 0.01)
        assertEquals(1.0, snapshot.openPortfolioRiskPct, 0.01)
        assertEquals(1.0, snapshot.projectedOpenRiskPct, 0.01)
        assertFalse(snapshot.hasDataQualityBlocker)

        val promptCtx = snapshot.toTraderPromptContext(signal)
        assertTrue(promptCtx.contains("EURUSD"))
        assertTrue(promptCtx.contains("CLOSED-BAR MARKET ANALYTICS"))
        assertTrue(promptCtx.contains("TRADER PORTFOLIO & SIZING RISK"))
    }

    @Test
    fun aiVerificationParser_parsesStructuredJsonAndClampsConfidence() {
        val raw = """
            ```json
            {
              "verdict": "HIGH_CONVICTION",
              "confidenceScore": 115,
              "marketStructureAssessment": "Price stacked above EMA20 > EMA50 > EMA200 with ADX 28.",
              "entryAndStopAudit": "Stop sits at 1.9 ATR, TP1/Cost = 25x.",
              "traderRiskAssessment": "0.5% risk ($100) within 3% max open heat.",
              "executionChecklist": ["Wait for 1H bar close", "Move SL to BE at TP1"],
              "keyRiskFlags": ["Watch US CPI in 4 hours"],
              "traderSummary": "Clean trend breakout aligned with risk caps. Informational only."
            }
            ```
        """.trimIndent()

        val parsed = AiVerificationParser.parseJson(
            rawJson = raw,
            signalKey = "sig_test_1",
            instrument = "EURUSD",
            timeframe = "1h",
            direction = "long",
            providerName = "Gemini Built-in",
            modelName = "gemini-3.8-flash",
            verifiedAtUtc = "08:15 UTC"
        )

        assertEquals(VerificationVerdict.HIGH_CONVICTION, parsed.verdict)
        assertEquals(100, parsed.confidenceScore)
        assertEquals(2, parsed.executionChecklist.size)
        assertEquals(1, parsed.keyRiskFlags.size)
        assertTrue(parsed.marketStructureAssessment.contains("EMA20"))
    }

    @Test(expected = IllegalStateException::class)
    fun aiVerificationParser_rejectsNonJsonOutput() {
        AiVerificationParser.parseJson(
            rawJson = "This is plain text without json",
            signalKey = "k",
            instrument = "BTCUSDT",
            timeframe = "4h",
            direction = "long",
            providerName = "Test",
            modelName = "test",
            verifiedAtUtc = "00:00 UTC"
        )
    }

    @Test
    fun riskEngine_enforcesDrawdownNewsAndCryptoClusterCaps() {
        val btc = InstrumentUniverse.find("BTCUSDT")!!
        val eth = InstrumentUniverse.find("ETHUSDT")!!
        val candles = buildTrendCandles(230, basePrice = 90_000.0, stepMs = 4 * 3600_000L).map {
            it.copy(open = it.open, high = it.open + 400.0, low = it.open - 350.0, close = it.open + 120.0)
        }
        val candidate = com.example.domain.model.CandidateSignal(
            instrument = eth,
            timeframe = Timeframe.H4,
            barCloseEpochMs = candles.last().closeTimeEpochMs,
            direction = Direction.LONG,
            strategyId = "S1_TREND_DONCHIAN",
            strategyVersion = "1.0.0",
            configHash = "cfg",
            regime = com.example.domain.model.Regime.TREND,
            allRegimes = setOf(com.example.domain.model.Regime.TREND),
            conviction = 0.72,
            entryLow = 3100.0,
            entryHigh = 3110.0,
            stopLoss = 2950.0,
            tp1Price = 3400.0,
            tp1R = 2.0,
            tp1ClosePct = 50,
            tp2Price = 3700.0,
            tp2R = 4.0,
            tp2ClosePct = 50,
            estCostR = 0.05,
            expiresAtEpochMs = candles.last().closeTimeEpochMs + 14_400_000L,
            invalidation = "Close below channel",
            rationale = "Breakout",
            featureSnapshot = emptyMap(),
            atrAtSignal = 90.0
        )

        // Drawdown halve ladder
        val halved = RiskEngine.evaluate(
            candidate = candidate,
            config = RiskConfig(riskPerTradePct = 1.0, drawdownHalvePct = 5.0, drawdownHaltPct = 8.0),
            portfolio = PortfolioRiskState(currentDrawdownPct = 5.5),
            spotRates = emptyMap(),
            nowEpochMs = candles.last().closeTimeEpochMs
        )
        assertEquals(RiskStatus.REDUCED, halved.status)
        assertEquals(0.5, halved.approvedRiskPct, 0.001)

        // News blackout gate
        val eventNow = candles.last().closeTimeEpochMs
        val blockedNews = RiskEngine.evaluate(
            candidate = candidate,
            config = RiskConfig(),
            portfolio = PortfolioRiskState(
                activeEvents = listOf(
                    EconomicEvent("cpi", "US CPI", "USD", "HIGH", "2026-10-01", eventNow + 10 * 60_000L)
                )
            ),
            spotRates = emptyMap(),
            nowEpochMs = eventNow
        )
        assertEquals(RiskStatus.REJECTED, blockedNews.status)
        assertTrue(blockedNews.reasons.any { it.contains("NEWS_BLACKOUT") })

        // Crypto cluster/BTC-beta cap
        val blockedCluster = RiskEngine.evaluate(
            candidate = candidate,
            config = RiskConfig(maxClusterRiskPct = 1.2, riskPerTradePct = 0.8),
            portfolio = PortfolioRiskState(
                openSignals = listOf(OpenExposureItem(btc, Direction.LONG, 1.2))
            ),
            spotRates = emptyMap(),
            nowEpochMs = eventNow
        )
        assertEquals(RiskStatus.REJECTED, blockedCluster.status)
        assertTrue(blockedCluster.reasons.any { it.contains("CRYPTO_CLUSTER_CAP") })

        // Quality blocker
        val blockedQuality = RiskEngine.evaluate(
            candidate = candidate,
            config = RiskConfig(),
            portfolio = PortfolioRiskState(
                blockingQualityIssues = listOf(
                    DataQualityIssue("ETHUSDT", Timeframe.H4, QualityIssueType.STALE_FEED, "Stale", detectedAtEpochMs = eventNow, blocksSignals = true)
                )
            ),
            spotRates = emptyMap(),
            nowEpochMs = eventNow
        )
        assertEquals(RiskStatus.REJECTED, blockedQuality.status)
        assertTrue(blockedQuality.reasons.any { it.contains("DATA_QUALITY_BLOCK") })
    }

    @Test
    fun hashChainUtils_detectsTampering() {
        val h0 = HashChainUtils.computeEventHash(
            prevHash = HashChainUtils.GENESIS_HASH,
            sequenceIndex = 0,
            signalId = "sig_1",
            eventType = "SIGNAL_CREATED",
            timestampIso = "2026-11-14T10:00:00Z",
            payloadSummary = "EURUSD LONG"
        )
        val tampered = HashChainUtils.computeEventHash(
            prevHash = HashChainUtils.GENESIS_HASH,
            sequenceIndex = 0,
            signalId = "sig_1",
            eventType = "SIGNAL_CREATED",
            timestampIso = "2026-11-14T10:00:00Z",
            payloadSummary = "EURUSD SHORT"
        )
        assertEquals(64, h0.length)
        assertFalse(h0 == tampered)
    }

    @Test
    fun riskDashboardAnalytics_computesVolatilityStopsSizingCurveAndExposure() {
        val eurusd = InstrumentUniverse.find("EURUSD")!!
        val btc = InstrumentUniverse.find("BTCUSDT")!!
        val candles = buildTrendCandles(80)
        val entry = candles.last().close

        val longProfile = RiskDashboardAnalytics.computeVolatilityStopProfile(
            instrument = eurusd,
            candles = candles,
            entryPrice = entry,
            fallbackStopPrice = entry - 0.0030,
            direction = Direction.LONG,
            atrMultiplier = 2.0
        )
        assertTrue(longProfile.hasLiveCandles)
        assertTrue(longProfile.atr14 > 0.0)
        assertTrue(longProfile.recommendedStopPrice < entry)
        assertTrue(longProfile.target2RPrice > entry)
        assertEquals(36, longProfile.trailStopSeries.size)

        val shortProfile = RiskDashboardAnalytics.computeVolatilityStopProfile(
            instrument = eurusd,
            candles = candles,
            entryPrice = entry,
            fallbackStopPrice = entry + 0.0030,
            direction = Direction.SHORT,
            atrMultiplier = 2.5
        )
        assertTrue(shortProfile.recommendedStopPrice > entry)
        assertTrue(shortProfile.target2RPrice < entry)

        val curve = RiskDashboardAnalytics.computeSizingSensitivityCurve(
            instrument = btc,
            accountBalanceUsd = 20_000.0,
            riskPct = 0.5,
            entryPrice = 90_000.0,
            atr14 = 1_200.0,
            direction = Direction.LONG,
            spotRates = emptyMap()
        )
        assertEquals(8, curve.size)
        assertTrue(curve.first().roundedSize > curve.last().roundedSize)
        assertNotNull(curve.first().maxSafeLeverage)

        val openFx = sampleSignal(instrument = "EURUSD", assetClass = "fx", direction = "long", riskPct = 0.6)
            .copy(id = "s_open_fx", signalUuid = "u_fx", status = "TRIGGERED")
        val openCrypto = sampleSignal(instrument = "BTCUSDT", assetClass = "crypto", direction = "long", riskPct = 0.5)
            .copy(id = "s_open_btc", signalUuid = "u_btc", status = "PENDING")
        val closedLoss = sampleSignal(instrument = "GBPUSD", assetClass = "fx", direction = "short", riskPct = 0.5)
            .copy(id = "s_closed_loss", signalUuid = "u_loss", status = "LOSS", realizedR = -1.0)

        val exposure = RiskDashboardAnalytics.computeExposureBreakdown(
            signals = listOf(openFx, openCrypto, closedLoss),
            riskConfig = RiskConfig(riskPerTradePct = 0.5, maxOpenRiskPct = 3.0),
            proposedRiskPct = 0.5
        )
        assertEquals(2, exposure.openCount)
        assertEquals(1.1, exposure.openRiskPct, 0.001)
        assertEquals(1.6, exposure.projectedRiskPct, 0.001)
        assertEquals(0.6, exposure.fxNetExposurePct["EUR"] ?: 0.0, 0.001)
        assertEquals(-0.6, exposure.fxNetExposurePct["USD"] ?: 0.0, 0.001)
        assertEquals(0.5, exposure.cryptoNetBtcBetaPct, 0.001)
        assertEquals(0.5, exposure.currentDrawdownPct, 0.001)
    }

    @Test
    fun marketSentimentParser_clampsScoresAndNeverIncreasesRiskAboveCap() {
        val raw = """
            ```json
            {
              "overallScore": -64,
              "cryptoScore": -72,
              "forexScore": -50,
              "regime": "EXTREME_FEAR",
              "riskPosture": "DEFENSIVE_HALVE",
              "recommendedRiskMultiplier": 1.65,
              "atrMultiplierBias": 2.5,
              "keyDrivers": ["BTC liquidations surge", "Hot US CPI pressures EURUSD"],
              "riskWarning": "Elevated macro/crypto volatility warrants halved trade sizing and wider ATR stops.",
              "headlineSummary": "Risk-off pressure across crypto perps and G10 FX."
            }
            ```
        """.trimIndent()
        val headlines = listOf(
            MarketNewsItem("n1", "Bitcoin slides below key support", "CoinDesk", "CRYPTO", "2026-11-14T15:00:00Z")
        )

        val report = MarketSentimentParser.parseJson(
            rawJson = raw,
            headlines = headlines,
            providerName = "Gemini Built-in",
            modelName = "gemini-3.8-flash",
            updatedAtUtc = "15:10 UTC"
        )

        assertEquals(-64, report.overallScore)
        assertEquals(18, report.gaugeIndex0To100)
        assertEquals(-72, report.cryptoScore)
        assertEquals(-50, report.forexScore)
        assertEquals(SentimentRegime.EXTREME_FEAR, report.regime)
        assertEquals(RiskPostureRecommendation.DEFENSIVE_HALVE, report.riskPosture)
        // Must clamp > 1.0 down to 1.0 safety invariant
        assertEquals(1.0, report.recommendedRiskMultiplier, 0.001)
        assertEquals(2.5, report.atrMultiplierBias, 0.001)
        assertEquals(2, report.keyDrivers.size)
        assertEquals(1, report.analyzedHeadlines.size)
    }

    @Test
    fun stopLossProximityMonitor_triggersApproachingAndBreachAlerts() {
        val openLong = sampleSignal(
            instrument = "EURUSD",
            direction = "long",
            entry = 1.1000,
            stop = 1.0900
        ).copy(id = "sig_prox_1", signalUuid = "u_prox_1", status = "TRIGGERED")

        // 1. Safe buffer (price 1.1020 -> 0.0120 above stop = 120% of risk distance, ATR 0.0020 -> 6x ATR)
        val safeAlerts = StopLossProximityMonitor.evaluateOpenPositions(
            signals = listOf(openLong),
            candlesBySymbol = emptyMap(),
            spotRates = mapOf("EURUSD" to 1.1020)
        )
        assertTrue(safeAlerts.isEmpty())

        // 2. Approaching within <= 25% of risk distance (price 1.0920 -> 0.0020 above SL 1.0900 = 20% of 0.0100 risk)
        val approachAlerts = StopLossProximityMonitor.evaluateOpenPositions(
            signals = listOf(openLong),
            candlesBySymbol = emptyMap(),
            spotRates = mapOf("EURUSD" to 1.0920)
        )
        assertEquals(1, approachAlerts.size)
        assertEquals(AlertSeverity.WARNING, approachAlerts.first().severity)
        assertTrue(approachAlerts.first().title.contains("SL APPROACHING"))

        // 3. Breached active setup on Risk Dashboard
        val breachAlerts = StopLossProximityMonitor.evaluateOpenPositions(
            signals = emptyList(),
            candlesBySymbol = emptyMap(),
            spotRates = emptyMap(),
            activeSetup = ActiveStopWatchTarget(
                symbol = "BTCUSDT",
                direction = Direction.LONG,
                entryPrice = 90_000.0,
                stopPrice = 88_000.0,
                currentPrice = 87_900.0,
                atr14 = 900.0
            )
        )
        assertEquals(1, breachAlerts.size)
        assertEquals(AlertSeverity.CRITICAL, breachAlerts.first().severity)
        assertTrue(breachAlerts.first().title.contains("STOP BREACHED"))
    }

    @Test
    fun pairAnalysisParser_parsesJsonClampsAndResolvesCustomPairs() {
        val raw = """
            ```json
            {
              "bias": "BULLISH",
              "confidenceScore": 140,
              "regime": "TREND_EXPANSION",
              "suggestedEntryLow": 0.3450,
              "suggestedEntryHigh": 0.3500,
              "volatilityAdjustedStopLoss": 0.3275,
              "takeProfit1": 0.3875,
              "takeProfit2": 0.4275,
              "riskRewardRatio": 2.0,
              "trendSummary": "Price breaking above EMA20 and EMA50 on rising volume.",
              "riskEngineSizingSummary": "0.50% risk ($50) -> 2500 contracts",
              "technicalThesis": "Closed-bar higher-low sequence aligned with ADX 28.",
              "actionChecklist": ["Confirm 1H bar close", "Trail stop under 20 EMA"],
              "keyRisks": ["BTC volatility spillover"],
              "traderSummary": "Structured bullish continuation setup with 2.0x ATR stop."
            }
            ```
        """.trimIndent()

        val parsed = PairAnalysisParser.parseJson(
            rawJson = raw,
            symbol = "DOGEUSDT",
            displayName = "DOGE/USDT",
            assetClassLabel = "CRYPTO",
            timeframe = Timeframe.H1,
            feedProvider = "Bybit v5 Public",
            currentPrice = 0.3480,
            atr14 = 0.0100,
            rsi14 = 61.4,
            adx14 = 28.2,
            fallbackEntryLow = 0.3468,
            fallbackEntryHigh = 0.3492,
            fallbackStopLoss = 0.3280,
            fallbackTp1 = 0.3880,
            fallbackTp2 = 0.4180,
            fallbackSizingSummary = "fallback",
            candlesUsedCount = 240,
            providerName = "Gemini Built-in",
            modelName = "gemini-3.8-flash",
            analyzedAtUtc = "17:00 UTC"
        )

        assertEquals(PairBias.BULLISH, parsed.bias)
        assertEquals(100, parsed.confidenceScore)
        assertEquals("DOGEUSDT", parsed.symbol)
        assertEquals(0.3275, parsed.volatilityAdjustedStopLoss, 0.0001)
        assertEquals(2, parsed.actionChecklist.size)

        val client = MarketDataClient()
        val fxCross = client.resolveAnyInstrument("eur/gbp")
        assertNotNull(fxCross)
        assertEquals("EURGBP", fxCross!!.symbol)
        assertEquals("EURGBP=X", fxCross.yahooTicker)
        assertEquals(AssetClass.FX, fxCross.assetClass)

        val customCrypto = client.resolveAnyInstrument("doge")
        assertNotNull(customCrypto)
        assertEquals("DOGEUSDT", customCrypto!!.symbol)
        assertEquals(AssetClass.CRYPTO, customCrypto.assetClass)

        val silver = client.resolveAnyInstrument("XAG/USD")
        assertNotNull(silver)
        assertEquals("XAGUSD", silver!!.symbol)
        assertEquals("SI=F", silver.yahooTicker)
    }
}
