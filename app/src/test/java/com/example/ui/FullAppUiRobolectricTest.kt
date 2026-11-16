package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiPairAnalysisResult
import com.example.data.ai.AiSettingsStore
import com.example.data.ai.AiSignalVerificationResult
import com.example.data.ai.AiVerificationState
import com.example.data.ai.MarketSentimentReport
import com.example.data.ai.MarketSentimentState
import com.example.data.ai.PairAiAnalysisState
import com.example.data.ai.PairBias
import com.example.data.ai.RiskPostureRecommendation
import com.example.data.ai.SentimentRegime
import com.example.data.ai.VerificationVerdict
import com.example.data.firestore.SignalEventRecord
import com.example.data.firestore.SignalRecord
import com.example.data.market.MarketDataClient
import com.example.data.market.MarketNewsClient
import com.example.data.market.MarketNewsItem
import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.RiskConfig
import com.example.domain.model.Timeframe
import com.example.quant.AlertSeverity
import com.example.quant.HashChainUtils
import com.example.quant.MarketAnalyticsEngine
import com.example.quant.RiskAlertEvent
import com.example.ui.auth.AuthScreen
import com.example.ui.components.AiVerificationScorecard
import com.example.ui.components.MarketAnalyticsCard
import com.example.ui.components.PairSearchAndAiPanel
import com.example.ui.components.RiskAlertBanner
import com.example.ui.screens.AiHubScreen
import com.example.ui.screens.LabScreen
import com.example.ui.screens.RiskDashboardContent
import com.example.ui.screens.SignalsScreen
import com.example.ui.theme.MeridianTheme
import com.example.ui.viewmodel.AiHubViewModel
import com.example.ui.viewmodel.ScanStatus
import com.example.ui.viewmodel.UiState
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullAppUiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun initFirebaseIfNeeded() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
    }

    private fun sampleCandles(count: Int = 220): List<Candle> {
        val start = 1760000000000L
        var p = 1.0800
        return (0 until count).map { i ->
            val o = p
            val c = o + 0.0003
            p = c
            Candle(
                openTimeEpochMs = start + i * 3600_000L,
                closeTimeEpochMs = start + (i + 1) * 3600_000L,
                open = o,
                high = c + 0.0005,
                low = o - 0.0004,
                close = c,
                volume = 1200.0,
                isClosed = true
            )
        }
    }

    private fun sampleSignal(): SignalRecord = SignalRecord(
        id = "sig_ui_1",
        userId = "uid_1",
        signalUuid = "uuid-ui-1",
        createdAtIso = "2026-11-14T10:00:00Z",
        instrument = "EURUSD",
        assetClass = "fx",
        direction = "long",
        strategy = "S1_TREND_DONCHIAN",
        strategyVersion = "1.0.0",
        configHash = "cfg_ui",
        timeframe = "1h",
        regime = "TREND",
        conviction = 0.74,
        entryType = "zone",
        entryLow = 1.1458,
        entryHigh = 1.1462,
        stopLoss = 1.1410,
        tp1Price = 1.1560,
        tp1R = 2.0,
        tp1ClosePct = 50L,
        tp2Price = 1.1660,
        tp2R = 4.0,
        tp2ClosePct = 50L,
        suggestedRiskPct = 0.5,
        estCostR = 0.07,
        expiresAtIso = "2026-11-14T22:00:00Z",
        invalidation = "Close below 20-bar channel",
        rationale = "Closed-bar breakout above 55-bar high.",
        riskStatus = "APPROVED",
        riskAdjustments = emptyList(),
        riskReasons = listOf("ALL_RISK_GATES_PASSED"),
        idempotencyKey = "S1|EURUSD|1h|1760000000000",
        executionMode = "PAPER",
        status = "PENDING"
    )

    @Test
    fun authScreen_displaysGoogleSignInButton() {
        composeRule.setContent {
            MeridianTheme {
                AuthScreen(onAuthSuccess = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SHEHZAD ALGO").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signalsScreen_emptyAndPopulatedStates_triggerScanAndAiVerification() {
        val signal = sampleSignal()
        val candles = sampleCandles()
        var scanClicked = false
        var verifyTriggered = false

        composeRule.setContent {
            MeridianTheme {
                SignalsScreen(
                    signalsState = UiState.Success(listOf(signal)),
                    scanStatus = ScanStatus(providerSummary = "Feeds: Yahoo Finance"),
                    riskConfig = RiskConfig(),
                    qualityIssues = emptyList(),
                    candlesBySymbol = mapOf("EURUSD" to candles),
                    spotRates = mapOf("EURUSD" to 1.1460),
                    verificationsBySignalKey = mapOf(
                        "sig_ui_1" to AiVerificationState.Success(
                            AiSignalVerificationResult(
                                signalKey = "sig_ui_1",
                                instrument = "EURUSD",
                                timeframe = "1h",
                                direction = "long",
                                providerName = "Gemini Built-in",
                                modelName = "gemini-3.8-flash",
                                verifiedAtUtc = "08:05 UTC",
                                verdict = VerificationVerdict.HIGH_CONVICTION,
                                confidenceScore = 84,
                                marketStructureAssessment = "Bullish EMA20/50/200 stack with ADX > 25.",
                                entryAndStopAudit = "Stop is 2.1 ATR with 28.6x TP1/cost ratio.",
                                traderRiskAssessment = "0.5% ($50) risk fits 3% open cap.",
                                executionChecklist = listOf("Enter on close", "Scale 50% at TP1"),
                                keyRiskFlags = emptyList(),
                                traderSummary = "High quality trend setup."
                            )
                        )
                    ),
                    onScanClosedBars = { scanClicked = true },
                    onUpdateStatus = { _, _, _ -> },
                    onImportCsv = { _, _, _ -> },
                    onVerifySignalWithAi = { _, _ -> verifyTriggered = true },
                    onAnalyzeWithAi = {},
                    onShowMessage = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("scan_bars_button").performClick()
        assertTrue(scanClicked)

        // Scroll down past PairSearchAndAiPanel so the signal card enters the viewport
        composeRule.onNodeWithTag("signals_lazy_column")
            .performScrollToNode(hasTestTag("signal_card_EURUSD_sig_ui_1"))
        composeRule.waitForIdle()

        // Signal card should show AI verified badge
        composeRule.onNodeWithText("AI VERIFIED: HIGH CONVICTION (84%)").assertIsDisplayed()

        // Tap signal card to open bottom sheet with Market Analytics & AI Verification Scorecard
        composeRule.onNodeWithTag("signal_card_EURUSD_sig_ui_1").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("market_analytics_card").assertExists()
        composeRule.onNodeWithTag("ai_verification_scorecard").assertExists()
        composeRule.onNodeWithTag("verify_signal_ai_button").performScrollTo().performClick()
        assertTrue(verifyTriggered)
    }

    @Test
    fun traderAnalyticsComponents_renderAllVerificationStates() {
        val signal = sampleSignal()
        val candles = sampleCandles()
        val analytics = MarketAnalyticsEngine.compute(
            signal = signal,
            candles = candles,
            riskConfig = RiskConfig(),
            allSignals = listOf(signal),
            spotRates = mapOf("EURUSD" to 1.1460),
            qualityIssues = emptyList()
        )
        var verifyClicks = 0

        composeRule.setContent {
            MeridianTheme {
                MarketAnalyticsCard(analytics = analytics, decimals = 5)
                AiVerificationScorecard(
                    verificationState = AiVerificationState.Error("sig_ui_1", "Timeout error"),
                    onVerifyClick = { verifyClicks++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("market_analytics_card").assertIsDisplayed()
        composeRule.onNodeWithText("Timeout error").assertIsDisplayed()
        composeRule.onNodeWithTag("verify_signal_ai_button").performClick()
        assertEquals(1, verifyClicks)
    }

    @Test
    fun labScreen_displaysAllThreeStrategiesAndTriggersValidation() {
        var validatedId: String? = null
        composeRule.setContent {
            MeridianTheme {
                LabScreen(
                    storedReports = emptyList(),
                    liveDetails = emptyMap(),
                    validatingStrategyId = null,
                    onValidateStrategy = { validatedId = it },
                    onImportCsv = { _, _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("validate_S1_TREND_DONCHIAN").performScrollTo().performClick()
        assertEquals("S1_TREND_DONCHIAN", validatedId)
    }

    @Test
    fun aiHubScreen_displaysSignalVerifierAndProviderControls() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testPrefs = context.getSharedPreferences("test_ai_prefs", android.content.Context.MODE_PRIVATE)
        val vm = AiHubViewModel(settingsStore = AiSettingsStore(context, testPrefs))
        val signal = sampleSignal()
        val candles = sampleCandles()

        composeRule.setContent {
            MeridianTheme {
                AiHubScreen(
                    aiViewModel = vm,
                    deskContext = "Platform: Shehzad Algo",
                    signals = listOf(signal),
                    candlesBySymbol = mapOf("EURUSD" to candles),
                    riskConfig = RiskConfig(),
                    spotRates = mapOf("EURUSD" to 1.1460),
                    qualityIssues = emptyList(),
                    initialPrompt = null,
                    onConsumeInitialPrompt = {},
                    onShowMessage = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SELECT SIGNAL TO VERIFY & AUDIT RISK").assertIsDisplayed()
        composeRule.onNodeWithTag("verify_signal_ai_button").assertExists()
        composeRule.onNodeWithTag("ai_prompt_input").assertExists()
        composeRule.onNodeWithTag("open_ai_config_button").assertExists()
    }

    @Test
    fun riskDashboard_visualizesVolatilityStopSizingCurveAndExposure() {
        val openFx = sampleSignal().copy(id = "open_fx", signalUuid = "u_fx", status = "TRIGGERED")
        val openBtc = sampleSignal().copy(
            id = "open_btc",
            signalUuid = "u_btc",
            instrument = "BTCUSDT",
            assetClass = "crypto",
            status = "PENDING",
            suggestedRiskPct = 0.5
        )
        val candles = sampleCandles(120)
        var savedConfig: RiskConfig? = null
        var killToggled = false

        composeRule.setContent {
            MeridianTheme {
                RiskDashboardContent(
                    riskConfig = RiskConfig(),
                    signals = listOf(openFx, openBtc),
                    spotRates = mapOf("EURUSD" to 1.1460, "BTCUSDT" to 90000.0),
                    qualityIssues = emptyList(),
                    candlesBySymbol = mapOf("EURUSD" to candles),
                    onSaveRiskConfig = { cfg, isKill ->
                        savedConfig = cfg
                        killToggled = isKill
                    }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("risk_pulse_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("risk_dashboard_list").performScrollToNode(hasTestTag("volatility_stop_chart"))
        composeRule.onNodeWithTag("volatility_stop_chart").assertIsDisplayed()

        composeRule.onNodeWithTag("atr_mult_2.5").performScrollTo().performClick()
        composeRule.onNodeWithTag("apply_atr_stop_button").performScrollTo().performClick()

        composeRule.onNodeWithTag("risk_dashboard_list").performScrollToNode(hasTestTag("position_sizing_chart"))
        composeRule.onNodeWithTag("position_sizing_chart").assertIsDisplayed()

        composeRule.onNodeWithTag("risk_dashboard_list").performScrollToNode(hasTestTag("real_time_exposure_chart"))
        composeRule.onNodeWithTag("real_time_exposure_chart").assertIsDisplayed()

        composeRule.onNodeWithTag("risk_dashboard_list").performScrollToNode(hasTestTag("save_risk_config_button"))
        composeRule.onNodeWithTag("save_risk_config_button").performClick()
        assertEquals(10_000.0, savedConfig?.accountBalanceUsd ?: 0.0, 0.01)
        assertEquals(false, killToggled)
    }

    @Test
    fun riskDashboard_geminiSentimentGaugeUpdatesRiskScalingAndParsesNews() {
        val rssXml = """
            <rss><channel>
                <item>
                    <title>Dollar climbs ahead of FOMC rate decision - Reuters</title>
                    <link>https://example.com/fx</link>
                    <pubDate>Sat, 14 Nov 2026 14:00:00 GMT</pubDate>
                    <source>Reuters</source>
                </item>
            </channel></rss>
        """.trimIndent()
        val parsedRss = MarketNewsClient.parseRssXml(rssXml, defaultCategory = "FOREX", limit = 5)
        assertEquals(1, parsedRss.size)
        assertEquals("Dollar climbs ahead of FOMC rate decision", parsedRss.first().title)
        assertEquals("FOREX", parsedRss.first().category)

        val cryptoJson = """
            {"Data":[{"id":"101","title":"Bitcoin ETF inflows surge","published_on":1763130000,"source_info":{"name":"CoinDesk"},"url":"https://example.com/btc"}]}
        """.trimIndent()
        val parsedCrypto = MarketNewsClient.parseCryptoCompareJson(cryptoJson, limit = 5)
        assertEquals(1, parsedCrypto.size)
        assertEquals("CRYPTO", parsedCrypto.first().category)
        assertEquals("CoinDesk", parsedCrypto.first().source)

        val report = MarketSentimentReport(
            overallScore = -56,
            cryptoScore = -62,
            forexScore = -48,
            regime = SentimentRegime.RISK_OFF_BEARISH,
            riskPosture = RiskPostureRecommendation.DEFENSIVE_HALVE,
            recommendedRiskMultiplier = 0.50,
            atrMultiplierBias = 2.5,
            keyDrivers = listOf("Dollar strength pressures EURUSD", "Crypto perp liquidations rise"),
            riskWarning = "Halve per-trade risk to 0.25% and widen ATR stops to 2.5x.",
            headlineSummary = "Macro and crypto headlines reflect defensive risk-off sentiment.",
            analyzedHeadlines = parsedCrypto + parsedRss,
            providerName = "Gemini Built-in",
            modelName = "gemini-3.8-flash",
            updatedAtUtc = "16:30 UTC"
        )

        var refreshed = false
        var persistedConfig: RiskConfig? = null

        composeRule.setContent {
            MeridianTheme {
                RiskDashboardContent(
                    riskConfig = RiskConfig(riskPerTradePct = 0.50),
                    signals = listOf(sampleSignal().copy(status = "TRIGGERED")),
                    spotRates = mapOf("EURUSD" to 1.1460),
                    qualityIssues = emptyList(),
                    candlesBySymbol = mapOf("EURUSD" to sampleCandles(100)),
                    sentimentState = MarketSentimentState.Success(report),
                    onRefreshSentiment = { refreshed = true },
                    onSaveRiskConfig = { cfg, _ -> persistedConfig = cfg }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("risk_dashboard_list").performScrollToNode(hasTestTag("market_sentiment_gauge_card"))
        composeRule.onNodeWithTag("sentiment_arc_canvas").assertIsDisplayed()
        composeRule.onNodeWithText("RISK-OFF BEARISH").assertIsDisplayed()
        composeRule.onNodeWithTag("refresh_sentiment_button").performClick()
        assertTrue(refreshed)

        composeRule.onNodeWithTag("apply_sentiment_config_button").performScrollTo().performClick()
        assertEquals(0.25, persistedConfig?.riskPerTradePct ?: 0.0, 0.001)
    }

    @Test
    fun riskAlertBanner_rendersUrgentStopAlertsAndDismissesCleanly() {
        var dismissedId: String? = null
        val alert = RiskAlertEvent(
            id = "sig_prox_1_APPROACH",
            symbol = "EURUSD",
            direction = Direction.LONG,
            severity = AlertSeverity.WARNING,
            title = "SL APPROACHING: EURUSD LONG",
            message = "EURUSD (1.09200) is 0.40x ATR from SL 1.09000.",
            currentPrice = 1.09200,
            stopPrice = 1.09000,
            distanceAtr = 0.40,
            distancePctOfRisk = 20.0,
            timestampUtc = "17:05 UTC"
        )

        composeRule.setContent {
            MeridianTheme {
                RiskAlertBanner(
                    alerts = listOf(alert),
                    live24hEnabled = true,
                    openMonitoredCount = 1,
                    onDismissAlert = { dismissedId = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("stop_alert_banner").assertIsDisplayed()
        composeRule.onNodeWithText("SL APPROACHING: EURUSD LONG").assertIsDisplayed()
        composeRule.onNodeWithTag("dismiss_alert_button").performClick()
        assertEquals("sig_prox_1_APPROACH", dismissedId)
    }

    @Test
    fun pairSearchAndAiPanel_triggersAnalysisAndDisplaysFullAiReport() {
        var requestedPair: String? = null
        var requestedTf: Timeframe? = null
        val candles = sampleCandles(120)
        val result = AiPairAnalysisResult(
            symbol = "DOGEUSDT",
            displayName = "DOGE/USDT",
            assetClassLabel = "CRYPTO",
            timeframe = Timeframe.H1,
            feedProvider = "Bybit v5 Public",
            currentPrice = 0.3480,
            bias = PairBias.BULLISH,
            confidenceScore = 82,
            regime = "TREND_EXPANSION",
            suggestedEntryLow = 0.3460,
            suggestedEntryHigh = 0.3490,
            volatilityAdjustedStopLoss = 0.3280,
            takeProfit1 = 0.3880,
            takeProfit2 = 0.4180,
            riskRewardRatio = 2.0,
            atr14 = 0.0100,
            rsi14 = 61.5,
            adx14 = 27.4,
            trendSummary = "Bullish EMA20/50 stack",
            riskEngineSizingSummary = "0.50% risk ($50) -> 2500 contracts",
            technicalThesis = "Sustained higher-low structure on 1H bars.",
            actionChecklist = listOf("Enter on close above EMA20"),
            keyRisks = listOf("BTC correlation spike"),
            traderSummary = "Informational setup overview only.",
            candlesUsedCount = 120,
            providerName = "Gemini Built-in",
            modelName = "gemini-3.8-flash",
            analyzedAtUtc = "17:10 UTC"
        )

        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            MeridianTheme {
                PairSearchAndAiPanel(
                    searchQuery = query,
                    onSearchQueryChange = { query = it },
                    pairAnalysisState = PairAiAnalysisState.Success(result, candles),
                    onAnalyzePairWithAi = { s, tf ->
                        requestedPair = s
                        requestedTf = tf
                    },
                    onClearPairAnalysis = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pair_search_bar_card").assertExists()
        composeRule.onNodeWithTag("pair_ai_analysis_card").assertExists()
        composeRule.onNodeWithText("DOGEUSDT • 1H AI ANALYSIS").assertExists()
        composeRule.onNodeWithText("BULLISH SETUP (82%)").assertExists()

        composeRule.onNodeWithTag("pair_search_input").performTextInput("DOGEUSDT")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("analyze_searched_pair_ai_button").performClick()
        assertEquals("DOGEUSDT", requestedPair)
        assertEquals(Timeframe.H1, requestedTf)
    }

    @Test
    fun signalsScreen_live24hToggleUpdatesAndFiltersBySearch() {
        var toggledState: Boolean? = null
        composeRule.setContent {
            MeridianTheme {
                SignalsScreen(
                    signalsState = UiState.Success(listOf(sampleSignal())),
                    scanStatus = ScanStatus(),
                    riskConfig = RiskConfig(),
                    qualityIssues = emptyList(),
                    candlesBySymbol = mapOf("EURUSD" to sampleCandles()),
                    live24hEnabled = false,
                    onToggleLive24h = { toggledState = it },
                    onScanClosedBars = {},
                    onUpdateStatus = { _, _, _ -> },
                    onImportCsv = { _, _, _ -> },
                    onAnalyzeWithAi = {},
                    onShowMessage = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("live_24h_toggle").performClick()
        assertEquals(true, toggledState)
        composeRule.onNodeWithTag("pair_search_input").assertExists()
    }

    @Test
    fun signalsScreen_tabletMode_rendersDualPaneAndInspectorSimultaneously() {
        val signal = sampleSignal()
        val candles = sampleCandles()
        composeRule.setContent {
            MeridianTheme {
                SignalsScreen(
                    signalsState = UiState.Success(listOf(signal)),
                    scanStatus = ScanStatus(providerSummary = "Feeds: Yahoo Finance"),
                    riskConfig = RiskConfig(),
                    qualityIssues = emptyList(),
                    candlesBySymbol = mapOf("EURUSD" to candles),
                    spotRates = mapOf("EURUSD" to 1.1460),
                    isTabletMode = true,
                    onScanClosedBars = {},
                    onUpdateStatus = { _, _, _ -> },
                    onImportCsv = { _, _, _ -> },
                    onVerifySignalWithAi = { _, _ -> },
                    onAnalyzeWithAi = {},
                    onShowMessage = {}
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tablet_dual_pane_signals").assertExists()
        composeRule.onNodeWithTag("tablet_signal_detail_pane").assertExists()
        composeRule.onNodeWithTag("market_analytics_card").assertExists()
        composeRule.onNodeWithTag("ai_verification_scorecard").assertExists()
    }

    @Test
    @Config(sdk = [36], qualifiers = "w1024dp-h768dp")
    fun wideTabletScreens_renderDualPaneRiskAndLabAndSwitchStrategy() {
        var validatedId: String? = null
        composeRule.setContent {
            MeridianTheme {
                LabScreen(
                    storedReports = emptyList(),
                    liveDetails = emptyMap(),
                    validatingStrategyId = null,
                    isTabletMode = true,
                    onValidateStrategy = { validatedId = it },
                    onImportCsv = { _, _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tablet_dual_pane_lab").assertIsDisplayed()
        composeRule.onNodeWithTag("tablet_lab_detail_pane").assertIsDisplayed()
        composeRule.onNodeWithTag("lab_strategy_row_S2_SESSION_BREAKOUT").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("validate_S2_SESSION_BREAKOUT").assertExists().performClick()
        assertEquals("S2_SESSION_BREAKOUT", validatedId)
    }

    @Test
    fun riskDashboard_tabletMode_rendersDualPaneWorkspace() {
        composeRule.setContent {
            MeridianTheme {
                RiskDashboardContent(
                    riskConfig = RiskConfig(),
                    signals = listOf(sampleSignal()),
                    spotRates = mapOf("EURUSD" to 1.1460),
                    qualityIssues = emptyList(),
                    candlesBySymbol = mapOf("EURUSD" to sampleCandles(100)),
                    isTabletMode = true,
                    onSaveRiskConfig = { _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("tablet_dual_pane_risk").assertExists()
        composeRule.onNodeWithTag("risk_pulse_strip").assertIsDisplayed()
    }

    @Test
    fun marketDataClient_bypassesHttp451ViaOkxAndBinanceVision() = runBlocking {
        val fakeClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                val path = req.url.encodedPath
                val (code, body) = when {
                    host == "fapi.binance.com" || host == "api.binance.com" -> {
                        451 to """{"code":0,"msg":"Service unavailable from a restricted location."}"""
                    }
                    host == "api.bybit.com" || host == "api.bytick.com" -> {
                        403 to """{"retCode":403,"retMsg":"Forbidden"}"""
                    }
                    host == "www.okx.com" && path.contains("/market/candles") -> {
                        if (req.url.queryParameter("instId") == "BNB-USDT") {
                            503 to """{"code":"50011","msg":"Busy"}"""
                        } else {
                            200 to """{"code":"0","data":[["1794744000000","65359.2","65596.4","65188.1","65512.0","192.5","0","0","1"]]}"""
                        }
                    }
                    host == "www.okx.com" && path.contains("funding-rate-history") -> {
                        200 to """{"code":"0","data":[{"fundingTime":"1794729600000","fundingRate":"0.0001"}]}"""
                    }
                    host == "data-api.binance.vision" && path.contains("/klines") -> {
                        200 to """[[1794744000000,"590.0","598.5","589.1","595.98","1200.5",1794747599999,"715000",100,"600","357000","0"]]"""
                    }
                    host == "data-api.binance.vision" && path.contains("/ticker/price") -> {
                        200 to """[{"symbol":"BTCUSDT","price":"65512.0"},{"symbol":"ETHUSDT","price":"1831.5"}]"""
                    }
                    host == "api.frankfurter.dev" -> {
                        200 to """{"rates":{"EUR":0.92,"GBP":0.79,"JPY":154.0}}"""
                    }
                    else -> 404 to "{}"
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Blocked")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = MarketDataClient(httpClient = fakeClient)

        val btc = InstrumentUniverse.find("BTCUSDT")!!
        val btcBundle = client.fetchInstrumentBundle(btc, Timeframe.H1, limit = 50)
        assertNull(btcBundle.errorMessage)
        assertEquals("OKX v5 Public", btcBundle.providerName)
        assertEquals(65512.0, btcBundle.candles.single().close, 0.01)
        assertEquals(1, btcBundle.fundingHistory.size)

        val bnb = InstrumentUniverse.find("BNBUSDT")!!
        val bnbBundle = client.fetchInstrumentBundle(bnb, Timeframe.H1, limit = 50)
        assertNull(bnbBundle.errorMessage)
        assertEquals("Binance Vision Public", bnbBundle.providerName)
        assertEquals(595.98, bnbBundle.candles.single().close, 0.01)

        val rates = client.fetchSpotFxRatesUsd()
        assertEquals(65512.0, rates["BTCUSDT"] ?: 0.0, 0.01)
        assertEquals(1831.5, rates["ETHUSDT"] ?: 0.0, 0.01)
        assertEquals(154.0, rates["USDJPY"] ?: 0.0, 0.01)
    }
}
