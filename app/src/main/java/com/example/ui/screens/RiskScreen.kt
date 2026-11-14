package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.ai.MarketSentimentState
import com.example.data.firestore.SignalRecord
import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Direction
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.RiskConfig
import com.example.quant.ActiveStopWatchTarget
import com.example.quant.RiskAlertEvent
import com.example.quant.RiskDashboardAnalytics
import com.example.quant.RiskEngine
import com.example.ui.components.MarketSentimentGaugeCard
import com.example.ui.components.PositionSizingCurveChart
import com.example.ui.components.RealTimeExposureCharts
import com.example.ui.components.RiskAlertBanner
import com.example.ui.components.VolatilityAdjustedStopChart
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BearCrimsonTint
import com.example.ui.theme.BullEmerald
import com.example.ui.theme.BullEmeraldTint
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.MeridianTeal
import com.example.ui.theme.MeridianTealTint
import com.example.ui.theme.MonoMetricLarge
import com.example.ui.theme.MonoMetricMedium
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.WarnAmber
import com.example.ui.theme.WarnAmberTint
import com.example.ui.viewmodel.MeridianViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun RiskScreen(
    riskConfig: RiskConfig,
    signals: List<SignalRecord>,
    spotRates: Map<String, Double>,
    qualityIssues: List<DataQualityIssue>,
    viewModel: MeridianViewModel,
    candlesBySymbol: Map<String, List<Candle>> = emptyMap(),
    sentimentState: MarketSentimentState = MarketSentimentState.Idle,
    activeRiskAlerts: List<RiskAlertEvent> = emptyList(),
    live24hEnabled: Boolean = false,
    onToggleLive24h: (Boolean) -> Unit = {},
    onDismissRiskAlert: (String) -> Unit = {},
    onRefreshSentiment: () -> Unit = {}
) {
    RiskDashboardContent(
        riskConfig = riskConfig,
        signals = signals,
        spotRates = spotRates,
        qualityIssues = qualityIssues,
        candlesBySymbol = candlesBySymbol,
        sentimentState = sentimentState,
        activeRiskAlerts = activeRiskAlerts,
        live24hEnabled = live24hEnabled,
        onToggleLive24h = onToggleLive24h,
        onDismissRiskAlert = onDismissRiskAlert,
        onActiveStopTargetChanged = { target -> viewModel.evaluateRiskProximity(target) },
        onRefreshSentiment = onRefreshSentiment,
        onSaveRiskConfig = { updated, killSwitchToggled ->
            viewModel.saveRiskConfig(updated, killSwitchToggled = killSwitchToggled)
        }
    )
}

@Composable
fun RiskDashboardContent(
    riskConfig: RiskConfig,
    signals: List<SignalRecord>,
    spotRates: Map<String, Double>,
    qualityIssues: List<DataQualityIssue>,
    candlesBySymbol: Map<String, List<Candle>> = emptyMap(),
    sentimentState: MarketSentimentState = MarketSentimentState.Idle,
    activeRiskAlerts: List<RiskAlertEvent> = emptyList(),
    live24hEnabled: Boolean = false,
    onToggleLive24h: (Boolean) -> Unit = {},
    onDismissRiskAlert: (String) -> Unit = {},
    onActiveStopTargetChanged: (ActiveStopWatchTarget) -> Unit = {},
    onRefreshSentiment: () -> Unit = {},
    onSaveRiskConfig: (RiskConfig, Boolean) -> Unit
) {
    var selectedSymbol by remember { mutableStateOf("EURUSD") }
    val selectedIns = InstrumentUniverse.find(selectedSymbol) ?: InstrumentUniverse.all.first()
    var tradeDirection by remember { mutableStateOf(Direction.LONG) }
    var atrMultiplier by remember { mutableDoubleStateOf(2.0) }
    var sentimentOverlayEnabled by remember { mutableStateOf(true) }

    val activeSentiment = (sentimentState as? MarketSentimentState.Success)?.report
    LaunchedEffect(activeSentiment?.updatedAtUtc, sentimentOverlayEnabled) {
        if (activeSentiment != null && sentimentOverlayEnabled) {
            val snapped = ((activeSentiment.atrMultiplierBias * 2.0).roundToInt() / 2.0).coerceIn(1.0, 3.0)
            atrMultiplier = snapped
        }
    }

    val selectedCandles = candlesBySymbol[selectedSymbol].orEmpty()
    val decimals = when {
        selectedIns.quoteCurrency == "JPY" -> 3
        selectedIns.symbol == "XAUUSD" || selectedIns.assetClass == AssetClass.CRYPTO -> 2
        else -> 5
    }

    val defaultEntry = remember(selectedSymbol, spotRates, selectedCandles) {
        val candleClose = selectedCandles.lastOrNull { it.isClosed }?.close
        val spot = candleClose ?: spotRates[selectedSymbol]
        val value = spot ?: when (selectedSymbol) {
            "BTCUSDT" -> 90000.0
            "ETHUSDT" -> 3100.0
            "SOLUSDT" -> 205.0
            "BNBUSDT" -> 620.0
            "XRPUSDT" -> 2.15
            "XAUUSD" -> 2650.0
            "USDJPY" -> 154.0
            "EURJPY" -> 164.0
            "GBPJPY" -> 195.0
            else -> 1.0850
        }
        "%.${decimals}f".format(value)
    }

    var balanceText by remember(riskConfig.accountBalanceUsd) {
        mutableStateOf(riskConfig.accountBalanceUsd.toInt().toString())
    }
    var riskPctText by remember(riskConfig.riskPerTradePct) {
        mutableStateOf(riskConfig.riskPerTradePct.toString())
    }
    var entryText by remember(selectedSymbol, defaultEntry) {
        mutableStateOf(defaultEntry)
    }
    var stopText by remember(selectedSymbol, tradeDirection, defaultEntry) {
        val e = defaultEntry.toDoubleOrNull() ?: 1.0850
        val offset = when {
            selectedIns.assetClass == AssetClass.CRYPTO -> e * 0.02
            selectedIns.quoteCurrency == "JPY" -> 0.45
            selectedIns.symbol == "XAUUSD" -> 18.0
            else -> selectedIns.pipSize * 30.0
        }
        val sl = if (tradeDirection == Direction.LONG) e - offset else e + offset
        mutableStateOf("%.${decimals}f".format(sl))
    }

    var editBalance by remember(riskConfig) { mutableStateOf(riskConfig.accountBalanceUsd.toString()) }
    var editRiskPct by remember(riskConfig) { mutableStateOf(riskConfig.riskPerTradePct.toString()) }
    var editMaxOpen by remember(riskConfig) { mutableStateOf(riskConfig.maxOpenRiskPct.toString()) }
    var editMaxCluster by remember(riskConfig) { mutableStateOf(riskConfig.maxClusterRiskPct.toString()) }

    val parsedBalance = balanceText.toDoubleOrNull() ?: riskConfig.accountBalanceUsd
    val parsedRiskPct = riskPctText.toDoubleOrNull() ?: riskConfig.riskPerTradePct
    val effectiveRiskPct = if (activeSentiment != null && sentimentOverlayEnabled) {
        (parsedRiskPct * activeSentiment.recommendedRiskMultiplier).coerceIn(0.1, parsedRiskPct)
    } else {
        parsedRiskPct
    }
    val parsedEntry = entryText.toDoubleOrNull() ?: 0.0
    val parsedStop = stopText.toDoubleOrNull() ?: 0.0

    val sizing = remember(selectedIns, parsedBalance, effectiveRiskPct, parsedEntry, parsedStop, spotRates, riskConfig.minLiquidationStops) {
        RiskEngine.calculatePositionSize(
            instrument = selectedIns,
            accountBalanceUsd = parsedBalance,
            riskPct = effectiveRiskPct,
            entryPrice = parsedEntry,
            stopLoss = parsedStop,
            spotRates = spotRates,
            minLiquidationStops = riskConfig.minLiquidationStops
        )
    }

    val volProfile = remember(selectedIns, selectedCandles, parsedEntry, parsedStop, tradeDirection, atrMultiplier) {
        RiskDashboardAnalytics.computeVolatilityStopProfile(
            instrument = selectedIns,
            candles = selectedCandles,
            entryPrice = parsedEntry,
            fallbackStopPrice = parsedStop,
            direction = tradeDirection,
            atrMultiplier = atrMultiplier
        )
    }

    val sensitivityCurve = remember(selectedIns, parsedBalance, effectiveRiskPct, volProfile, tradeDirection, spotRates, riskConfig.minLiquidationStops) {
        RiskDashboardAnalytics.computeSizingSensitivityCurve(
            instrument = selectedIns,
            accountBalanceUsd = parsedBalance,
            riskPct = effectiveRiskPct,
            entryPrice = volProfile.entryPrice,
            atr14 = volProfile.atr14,
            direction = tradeDirection,
            spotRates = spotRates,
            minLiquidationStops = riskConfig.minLiquidationStops
        )
    }

    val exposure = remember(signals, riskConfig, effectiveRiskPct) {
        RiskDashboardAnalytics.computeExposureBreakdown(
            signals = signals,
            riskConfig = riskConfig,
            proposedRiskPct = effectiveRiskPct
        )
    }

    val currentSpotForWatcher = remember(selectedSymbol, selectedCandles, spotRates, volProfile.entryPrice) {
        selectedCandles.lastOrNull { it.isClosed }?.close ?: spotRates[selectedSymbol] ?: volProfile.entryPrice
    }

    LaunchedEffect(selectedSymbol, tradeDirection, parsedEntry, parsedStop, currentSpotForWatcher, volProfile.atr14) {
        if (parsedEntry > 0.0 && parsedStop > 0.0 && currentSpotForWatcher > 0.0) {
            onActiveStopTargetChanged(
                ActiveStopWatchTarget(
                    symbol = selectedSymbol,
                    direction = tradeDirection,
                    entryPrice = parsedEntry,
                    stopPrice = parsedStop,
                    currentPrice = currentSpotForWatcher,
                    atr14 = volProfile.atr14
                )
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("risk_dashboard_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. Persistent Volatility Stop-Loss Proximity Alert & 24H Live Monitor Indicator
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RiskAlertBanner(
                    alerts = activeRiskAlerts,
                    live24hEnabled = live24hEnabled,
                    openMonitoredCount = exposure.openCount,
                    onDismissAlert = onDismissRiskAlert
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    border = BorderStroke(1.dp, if (live24hEnabled) BullEmerald else HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (live24hEnabled) "24H CONTINUOUS MONITOR: RUNNING" else "24H CONTINUOUS MONITOR",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (live24hEnabled) BullEmerald else MeridianTeal
                            )
                            Text(
                                text = "Background dataSync alerts for new pair signals & SL proximity",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                        Switch(
                            checked = live24hEnabled,
                            onCheckedChange = onToggleLive24h,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = ObsidianBg,
                                checkedTrackColor = BullEmerald
                            ),
                            modifier = Modifier.testTag("risk_live_24h_switch")
                        )
                    }
                }
            }
        }

        // 1. Kill Switch & Risk Gate Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (riskConfig.manualKillSwitch) BearCrimsonTint else SlateCard
                ),
                border = BorderStroke(1.dp, if (riskConfig.manualKillSwitch) BearCrimson else HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Outlined.PowerSettingsNew,
                                contentDescription = null,
                                tint = if (riskConfig.manualKillSwitch) BearCrimson else MeridianTeal
                            )
                            Text(
                                text = if (riskConfig.manualKillSwitch) "MANUAL KILL SWITCH: ACTIVE" else "RISK ENGINE GATE: ARMED",
                                style = MaterialTheme.typography.titleMedium,
                                color = InkPrimary
                            )
                        }
                        Text(
                            text = if (riskConfig.manualKillSwitch) {
                                "All new signal publications are halted until manual reset."
                            } else {
                                "Drawdown ladder halves size at ${riskConfig.drawdownHalvePct}%, halts at ${riskConfig.drawdownHaltPct}%."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                    }
                    Switch(
                        checked = riskConfig.manualKillSwitch,
                        onCheckedChange = { checked ->
                            onSaveRiskConfig(riskConfig.copy(manualKillSwitch = checked), true)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = InkPrimary,
                            checkedTrackColor = BearCrimson
                        ),
                        modifier = Modifier.testTag("kill_switch_toggle")
                    )
                }
            }
        }

        // 2. Real-Time Risk Telemetry Pulse Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("risk_pulse_strip"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RiskPulseTile(
                    label = "PORTFOLIO HEAT",
                    value = "${"%.2f".format(exposure.openRiskPct)}%",
                    sub = "Cap ${riskConfig.maxOpenRiskPct}%",
                    accent = if (exposure.openRiskPct >= riskConfig.maxOpenRiskPct) BearCrimson else MeridianTeal,
                    modifier = Modifier.weight(1f)
                )
                RiskPulseTile(
                    label = "WORST FX LEG",
                    value = "${"%.2f".format(exposure.worstFxLegAbsPct)}%",
                    sub = "Cap ${riskConfig.maxClusterRiskPct}%",
                    accent = if (exposure.worstFxLegAbsPct > riskConfig.maxClusterRiskPct) BearCrimson else BullEmerald,
                    modifier = Modifier.weight(1f)
                )
                RiskPulseTile(
                    label = "BTC-BETA NET",
                    value = "${"%.2f".format(exposure.cryptoNetBtcBetaPct)}%",
                    sub = "${exposure.openCount} Open",
                    accent = if (abs(exposure.cryptoNetBtcBetaPct) > riskConfig.maxClusterRiskPct * 1.5) BearCrimson else WarnAmber,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 2b. Gemini AI Real-Time Crypto/Forex Market Sentiment Gauge
        item {
            MarketSentimentGaugeCard(
                sentimentState = sentimentState,
                riskConfig = riskConfig,
                sentimentOverlayEnabled = sentimentOverlayEnabled,
                onToggleSentimentOverlay = { sentimentOverlayEnabled = it },
                onRefreshSentiment = onRefreshSentiment,
                onApplyToPersistedRiskConfig = { report ->
                    val scaled = ((riskConfig.riskPerTradePct * report.recommendedRiskMultiplier) * 100.0).roundToInt() / 100.0
                    val updated = riskConfig.copy(
                        riskPerTradePct = scaled.coerceIn(0.1, 2.0)
                    )
                    riskPctText = updated.riskPerTradePct.toString()
                    onSaveRiskConfig(updated, false)
                }
            )
        }

        // 3. Volatility-Adjusted Stop-Loss Chart & Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "VOLATILITY-ADJUSTED STOP ENGINE",
                                style = MaterialTheme.typography.labelMedium,
                                color = MeridianTeal
                            )
                            Text(
                                text = "${selectedIns.symbol} dynamic ATR corridor & Chandelier levels",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = tradeDirection == Direction.LONG,
                                onClick = { tradeDirection = Direction.LONG },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.TrendingUp,
                                        contentDescription = "Long direction",
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text("LONG", style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BullEmeraldTint,
                                    selectedLabelColor = BullEmerald,
                                    selectedLeadingIconColor = BullEmerald
                                ),
                                modifier = Modifier.testTag("direction_long_chip")
                            )
                            FilterChip(
                                selected = tradeDirection == Direction.SHORT,
                                onClick = { tradeDirection = Direction.SHORT },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.TrendingDown,
                                        contentDescription = "Short direction",
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = { Text("SHORT", style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BearCrimsonTint,
                                    selectedLabelColor = BearCrimson,
                                    selectedLeadingIconColor = BearCrimson
                                ),
                                modifier = Modifier.testTag("direction_short_chip")
                            )
                        }
                    }

                    // Instrument chips shared across Stop Engine & Sizing Calculator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InstrumentUniverse.all.forEach { ins ->
                            FilterChip(
                                selected = selectedSymbol == ins.symbol,
                                onClick = { selectedSymbol = ins.symbol },
                                label = { Text(ins.symbol, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MeridianTealTint,
                                    selectedLabelColor = MeridianTeal
                                ),
                                modifier = Modifier.testTag("instrument_chip_${ins.symbol}")
                            )
                        }
                    }

                    // ATR Multiplier selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ATR Mult:", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                        listOf(1.0, 1.5, 2.0, 2.5, 3.0).forEach { mult ->
                            FilterChip(
                                selected = atrMultiplier == mult,
                                onClick = { atrMultiplier = mult },
                                label = { Text("${mult}x", style = MonoMetricSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WarnAmberTint,
                                    selectedLabelColor = WarnAmber
                                ),
                                modifier = Modifier.testTag("atr_mult_${mult}")
                            )
                        }
                    }

                    VolatilityAdjustedStopChart(
                        candles = selectedCandles,
                        profile = volProfile,
                        activeStopPrice = parsedStop,
                        decimals = decimals,
                        unitLabel = if (selectedIns.assetClass == AssetClass.FX) "pips" else "pts"
                    )

                    OutlinedButton(
                        onClick = {
                            stopText = "%.${decimals}f".format(volProfile.recommendedStopPrice)
                        },
                        border = BorderStroke(1.dp, MeridianTeal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("apply_atr_stop_button")
                    ) {
                        Icon(
                            Icons.Outlined.AutoGraph,
                            contentDescription = null,
                            tint = MeridianTeal,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = "Sync ${volProfile.multiplier}x ATR Stop (${"%.${decimals}f".format(volProfile.recommendedStopPrice)})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MeridianTeal
                        )
                    }
                }
            }
        }

        // 4. Position Size Calculator & Sensitivity Curve Chart
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "POSITION SIZE & LEVERAGE CALCULATOR",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = balanceText,
                            onValueChange = { balanceText = it },
                            label = { Text("Balance (USD)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = riskPctText,
                            onValueChange = { riskPctText = it },
                            label = { Text("Risk %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = entryText,
                            onValueChange = { entryText = it },
                            label = { Text("Entry Price") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = stopText,
                            onValueChange = { stopText = it },
                            label = { Text("Stop Loss") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GraphiteSurface, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        if (sizing.valid) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("RECOMMENDED SIZE", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                                        Text(
                                            text = "${sizing.roundedSize} ${sizing.sizeUnitLabel}",
                                            style = MonoMetricLarge,
                                            color = MeridianTeal
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("RISK AMOUNT", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                                        Text(
                                            text = "$${"%.2f".format(sizing.riskAmountUsd)}",
                                            style = MonoMetricLarge,
                                            color = InkPrimary
                                        )
                                    }
                                }
                                val atrRatio = if (volProfile.atr14 > 0.0) sizing.stopDistancePrice / volProfile.atr14 else 0.0
                                Text(
                                    text = "Stop: ${"%.1f".format(sizing.stopPipsOrPoints)} ${if (selectedIns.assetClass == AssetClass.FX) "pips" else "pts"} (${"%.2f".format(atrRatio)}x ATR) | Notional: $${"%.0f".format(sizing.notionalUsd)}",
                                    style = MonoMetricSmall,
                                    color = InkSecondary
                                )
                                if (activeSentiment != null && sentimentOverlayEnabled && effectiveRiskPct < parsedRiskPct - 0.001) {
                                    Text(
                                        text = "AI Sentiment Scaling: ${"%.2f".format(parsedRiskPct)}% -> ${"%.2f".format(effectiveRiskPct)}% (${"%.2f".format(activeSentiment.recommendedRiskMultiplier)}x ${activeSentiment.regime.displayLabel})",
                                        style = MonoMetricSmall,
                                        color = WarnAmber
                                    )
                                }
                                sizing.suggestedMaxLeverage?.let { lev ->
                                    Text(
                                        text = "Max Safe Leverage: ${"%.1f".format(lev)}x (Liq >= ${"%.1f".format(sizing.estLiquidationDistanceStops ?: 3.0)}x stop)",
                                        style = MonoMetricSmall,
                                        color = BullEmerald
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = sizing.rejectionReason ?: "Enter valid entry & stop loss.",
                                style = MaterialTheme.typography.bodySmall,
                                color = BearCrimson
                            )
                        }
                    }

                    PositionSizingCurveChart(
                        points = sensitivityCurve,
                        activeSizing = sizing,
                        activeStopDistance = sizing.stopDistancePrice,
                        sizeUnitLabel = if (selectedIns.assetClass == AssetClass.FX) "lots" else "contracts"
                    )
                }
            }
        }

        // 5. Real-Time Portfolio & Currency/Cluster Exposure Monitor
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.Shield, contentDescription = null, tint = MeridianTeal, modifier = Modifier.size(18.dp))
                            Text(
                                text = "REAL-TIME EXPOSURE MONITOR (${exposure.openCount} OPEN)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MeridianTeal
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(BullEmerald, CircleShape)
                            )
                            Text("LIVE", style = MonoMetricSmall, color = BullEmerald)
                        }
                    }

                    RealTimeExposureCharts(
                        exposure = exposure,
                        riskConfig = riskConfig,
                        proposedRiskPct = effectiveRiskPct
                    )

                    if (exposure.openItems.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ACTIVE EXPOSURE BREAKDOWN", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                            exposure.openItems.take(6).forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(GraphiteSurface, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${item.instrument.symbol} • ${item.direction.name}",
                                        style = MonoMetricSmall,
                                        color = if (item.direction == Direction.LONG) BullEmerald else BearCrimson
                                    )
                                    Text(
                                        text = "${"%.2f".format(item.riskPct)}% risk",
                                        style = MonoMetricSmall,
                                        color = InkPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Persisted Risk Governance Config
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "RISK GOVERNANCE CONFIG (PERSISTED TO FIRESTORE)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editBalance,
                            onValueChange = { editBalance = it },
                            label = { Text("Account USD") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editRiskPct,
                            onValueChange = { editRiskPct = it },
                            label = { Text("Risk/Trade %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editMaxOpen,
                            onValueChange = { editMaxOpen = it },
                            label = { Text("Max Open %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = editMaxCluster,
                            onValueChange = { editMaxCluster = it },
                            label = { Text("Cluster Cap %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            val updated = riskConfig.copy(
                                accountBalanceUsd = (editBalance.toDoubleOrNull() ?: riskConfig.accountBalanceUsd).coerceIn(100.0, 10_000_000.0),
                                riskPerTradePct = (editRiskPct.toDoubleOrNull() ?: riskConfig.riskPerTradePct).coerceIn(0.1, 2.0),
                                maxOpenRiskPct = (editMaxOpen.toDoubleOrNull() ?: riskConfig.maxOpenRiskPct).coerceIn(0.5, 5.0),
                                maxClusterRiskPct = (editMaxCluster.toDoubleOrNull() ?: riskConfig.maxClusterRiskPct).coerceIn(0.5, 5.0)
                            )
                            onSaveRiskConfig(updated, false)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_risk_config_button")
                    ) {
                        Text("Save Risk Parameters", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // 7. Data Quality Guardrail Alerts
        if (qualityIssues.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarnAmberTint),
                    border = BorderStroke(1.dp, WarnAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = WarnAmber)
                            Text("DATA QUALITY ISSUES (${qualityIssues.size})", style = MaterialTheme.typography.labelMedium, color = WarnAmber)
                        }
                        qualityIssues.take(8).forEach { issue ->
                            Text(
                                text = "• ${issue.instrument} ${issue.timeframe.wire} [${issue.issueType.name}]: ${issue.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskPulseTile(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            Text(text = value, style = MonoMetricMedium, color = accent)
            Text(text = sub, style = MonoMetricSmall, color = InkSecondary)
        }
    }
}
