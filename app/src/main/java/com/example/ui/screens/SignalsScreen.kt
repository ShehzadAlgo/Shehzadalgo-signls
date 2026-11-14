package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ai.AiPairAnalysisResult
import com.example.data.ai.AiVerificationState
import com.example.data.ai.PairAiAnalysisState
import com.example.data.ai.VerificationVerdict
import com.example.data.firestore.SignalRecord
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import com.example.domain.model.Timeframe
import com.example.quant.MarketAnalyticsEngine
import com.example.quant.MarketAnalyticsSnapshot
import com.example.quant.TelegramFormatter
import com.example.ui.components.AiVerificationScorecard
import com.example.ui.components.MarketAnalyticsCard
import com.example.ui.components.PairSearchAndAiPanel
import com.example.ui.components.SignalCandleChart
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BearCrimsonTint
import com.example.ui.theme.BullEmerald
import com.example.ui.theme.BullEmeraldTint
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.InfoBlueTint
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.MeridianTeal
import com.example.ui.theme.MeridianTealTint
import com.example.ui.theme.MonoMetricMedium
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.WarnAmber
import com.example.ui.theme.WarnAmberTint
import com.example.ui.viewmodel.AiHubViewModel
import com.example.ui.viewmodel.ScanStatus
import com.example.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalsScreen(
    signalsState: UiState<List<SignalRecord>>,
    scanStatus: ScanStatus,
    riskConfig: RiskConfig,
    qualityIssues: List<DataQualityIssue>,
    candlesBySymbol: Map<String, List<Candle>>,
    spotRates: Map<String, Double> = emptyMap(),
    verificationsBySignalKey: Map<String, AiVerificationState> = emptyMap(),
    pairAnalysisState: PairAiAnalysisState = PairAiAnalysisState.Idle,
    live24hEnabled: Boolean = false,
    onToggleLive24h: (Boolean) -> Unit = {},
    onScanClosedBars: () -> Unit,
    onUpdateStatus: (SignalRecord, SignalStatus, Double) -> Unit,
    onImportCsv: (String, Timeframe, Uri) -> Unit,
    onVerifySignalWithAi: (SignalRecord, MarketAnalyticsSnapshot) -> Unit = { _, _ -> },
    onAnalyzeWithAi: (SignalRecord) -> Unit,
    onAnalyzePairWithAi: (String, Timeframe) -> Unit = { _, _ -> },
    onClearPairAnalysis: () -> Unit = {},
    onOpenPairInAiChat: (AiPairAnalysisResult) -> Unit = {},
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSignal by remember { mutableStateOf<SignalRecord?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportCsv("EURUSD", Timeframe.H1, uri)
        }
    }

    val allSignals = (signalsState as? UiState.Success)?.data.orEmpty()
    val filteredSignals = remember(allSignals, selectedFilter, searchQuery) {
        val base = when (selectedFilter) {
            "OPEN" -> allSignals.filter { SignalStatus.fromWire(it.status).isOpen }
            "FX" -> allSignals.filter { it.assetClass == "fx" }
            "CRYPTO" -> allSignals.filter { it.assetClass == "crypto" }
            "CLOSED" -> allSignals.filter { SignalStatus.fromWire(it.status).isTerminal }
            else -> allSignals
        }
        val cleanSearch = searchQuery.trim()
        if (cleanSearch.isEmpty()) {
            base
        } else {
            base.filter {
                it.instrument.contains(cleanSearch, ignoreCase = true) ||
                    it.strategy.contains(cleanSearch, ignoreCase = true) ||
                    it.direction.contains(cleanSearch, ignoreCase = true)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("signals_lazy_column"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    TerminalScanHeader(
                        scanStatus = scanStatus,
                        riskConfig = riskConfig,
                        live24hEnabled = live24hEnabled,
                        blockingIssuesCount = qualityIssues.count { it.blocksSignals },
                        onToggleLive24h = onToggleLive24h,
                        onScanClick = onScanClosedBars,
                        onImportCsvClick = { csvLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) }
                    )
                }

                item {
                    PairSearchAndAiPanel(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        pairAnalysisState = pairAnalysisState,
                        onAnalyzePairWithAi = onAnalyzePairWithAi,
                        onClearPairAnalysis = onClearPairAnalysis,
                        onOpenFullAiChat = onOpenPairInAiChat
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("ALL", "OPEN", "FX", "CRYPTO", "CLOSED").forEach { chip ->
                            FilterChip(
                                selected = selectedFilter == chip,
                                onClick = { selectedFilter = chip },
                                label = { Text(chip, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MeridianTealTint,
                                    selectedLabelColor = MeridianTeal
                                )
                            )
                        }
                    }
                }

                if (scanStatus.diagnostics.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                            border = BorderStroke(1.dp, HairlineBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "SCANNER DIAGNOSTICS (${scanStatus.candidatesEvaluated} CANDIDATES | ${scanStatus.approvedOrReducedCount} PUBLISHED | ${scanStatus.rejectedCount} REJECTED)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MeridianTeal
                                )
                                scanStatus.diagnostics.take(5).forEach { line ->
                                    Text(
                                        text = "• $line",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = InkSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                when (signalsState) {
                    is UiState.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MeridianTeal)
                            }
                        }
                    }

                    is UiState.Error -> {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = BearCrimsonTint),
                                border = BorderStroke(1.dp, BearCrimson)
                            ) {
                                Text(
                                    text = signalsState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkPrimary,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }

                    is UiState.Success -> {
                        if (filteredSignals.isEmpty()) {
                            item {
                                EmptySignalsCard(onScanClick = onScanClosedBars)
                            }
                        } else {
                            items(filteredSignals, key = { it.id.ifBlank { it.signalUuid } }) { sig ->
                                val vState = verificationsBySignalKey[AiHubViewModel.signalKeyFor(sig)]
                                SignalCard(
                                    signal = sig,
                                    isSelected = selectedSignal?.id == sig.id,
                                    verificationState = vState,
                                    onClick = { selectedSignal = sig }
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.disclaimer_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            if (isWide && selectedSignal != null) {
                val active = allSignals.firstOrNull { it.id == selectedSignal?.id } ?: selectedSignal!!
                val activeCandles = candlesBySymbol[active.instrument].orEmpty()
                val activeAnalytics = remember(active, activeCandles, riskConfig, allSignals, spotRates, qualityIssues) {
                    MarketAnalyticsEngine.compute(active, activeCandles, riskConfig, allSignals, spotRates, qualityIssues)
                }
                val activeVerification = verificationsBySignalKey[AiHubViewModel.signalKeyFor(active)] ?: AiVerificationState.Idle
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(GraphiteSurface)
                        .border(1.dp, HairlineBorder)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    SignalDetailContent(
                        signal = active,
                        candles = activeCandles,
                        analytics = activeAnalytics,
                        verificationState = activeVerification,
                        onUpdateStatus = { st, r -> onUpdateStatus(active, st, r) },
                        onVerifyWithAi = { onVerifySignalWithAi(active, activeAnalytics) },
                        onAnalyzeWithAi = { onAnalyzeWithAi(active) },
                        onCopyTelegram = {
                            copyToClipboard(context, TelegramFormatter.formatSignal(active))
                            onShowMessage("Copied Telegram signal format to clipboard.")
                        }
                    )
                }
            }
        }

        if (!isWide && selectedSignal != null) {
            val active = allSignals.firstOrNull { it.id == selectedSignal?.id } ?: selectedSignal!!
            val activeCandles = candlesBySymbol[active.instrument].orEmpty()
            val activeAnalytics = remember(active, activeCandles, riskConfig, allSignals, spotRates, qualityIssues) {
                MarketAnalyticsEngine.compute(active, activeCandles, riskConfig, allSignals, spotRates, qualityIssues)
            }
            val activeVerification = verificationsBySignalKey[AiHubViewModel.signalKeyFor(active)] ?: AiVerificationState.Idle
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { selectedSignal = null },
                sheetState = sheetState,
                containerColor = GraphiteSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    SignalDetailContent(
                        signal = active,
                        candles = activeCandles,
                        analytics = activeAnalytics,
                        verificationState = activeVerification,
                        onUpdateStatus = { st, r ->
                            onUpdateStatus(active, st, r)
                            selectedSignal = null
                        },
                        onVerifyWithAi = { onVerifySignalWithAi(active, activeAnalytics) },
                        onAnalyzeWithAi = {
                            selectedSignal = null
                            onAnalyzeWithAi(active)
                        },
                        onCopyTelegram = {
                            copyToClipboard(context, TelegramFormatter.formatSignal(active))
                            onShowMessage("Copied Telegram signal format to clipboard.")
                        }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun TerminalScanHeader(
    scanStatus: ScanStatus,
    riskConfig: RiskConfig,
    live24hEnabled: Boolean,
    blockingIssuesCount: Int,
    onToggleLive24h: (Boolean) -> Unit,
    onScanClick: () -> Unit,
    onImportCsvClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, if (riskConfig.manualKillSwitch) BearCrimson else HairlineBorder),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (riskConfig.manualKillSwitch) "KILL SWITCH ENGAGED" else "CLOSED-BAR SIGNAL ENGINE",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (riskConfig.manualKillSwitch) BearCrimson else MeridianTeal
                    )
                    Text(
                        text = scanStatus.providerSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
                if (blockingIssuesCount > 0) {
                    StatusPill(
                        text = "$blockingIssuesCount QUALITY BLOCK",
                        bg = WarnAmberTint,
                        fg = WarnAmber
                    )
                } else {
                    StatusPill(
                        text = scanStatus.lastScanUtc?.takeLast(9) ?: "STANDBY",
                        bg = MeridianTealTint,
                        fg = MeridianTeal
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GraphiteSurface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (live24hEnabled) "24H LIVE ALERTS: ACTIVE" else "24H LIVE AUTO-ALERTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (live24hEnabled) BullEmerald else InkPrimary
                    )
                    Text(
                        text = "Instant alerts when any pair fires a signal or approaches SL",
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
                    modifier = Modifier.testTag("live_24h_toggle")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onScanClick,
                    enabled = !scanStatus.isScanning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MeridianTeal,
                        contentColor = ObsidianBg
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("scan_bars_button")
                ) {
                    if (scanStatus.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = ObsidianBg,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning...", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_scan_bars), style = MaterialTheme.typography.titleMedium)
                    }
                }

                OutlinedButton(
                    onClick = onImportCsvClick,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("import_csv_signals_button")
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = InkPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CSV", color = InkPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SignalCard(
    signal: SignalRecord,
    isSelected: Boolean,
    verificationState: AiVerificationState? = null,
    onClick: () -> Unit
) {
    val ins = InstrumentUniverse.find(signal.instrument)
    val decimals = if (ins?.quoteCurrency == "JPY") 3 else if (signal.assetClass == "crypto") 2 else 5
    val fmt: (Double) -> String = { "%.${decimals}f".format(it) }
    val isLong = signal.direction.equals("long", ignoreCase = true)
    val dirColor = if (isLong) BullEmerald else BearCrimson
    val dirBg = if (isLong) BullEmeraldTint else BearCrimsonTint
    val statusEnum = SignalStatus.fromWire(signal.status)

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, if (isSelected) MeridianTeal else HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("signal_card_${signal.instrument}_${signal.id}")
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = signal.instrument,
                        style = MaterialTheme.typography.titleLarge,
                        color = InkPrimary
                    )
                    StatusPill(
                        text = signal.direction.uppercase(),
                        bg = dirBg,
                        fg = dirColor
                    )
                    StatusPill(
                        text = signal.timeframe,
                        bg = GraphiteSurface,
                        fg = InkSecondary
                    )
                }
                StatusPill(
                    text = "${signal.executionMode} • ${statusEnum.wire}",
                    bg = when (statusEnum) {
                        SignalStatus.WIN, SignalStatus.TP1_HIT -> BullEmeraldTint
                        SignalStatus.LOSS -> BearCrimsonTint
                        SignalStatus.PENDING, SignalStatus.TRIGGERED -> InfoBlueTint
                        else -> GraphiteSurface
                    },
                    fg = when (statusEnum) {
                        SignalStatus.WIN, SignalStatus.TP1_HIT -> BullEmerald
                        SignalStatus.LOSS -> BearCrimson
                        SignalStatus.PENDING, SignalStatus.TRIGGERED -> InfoBlue
                        else -> InkSecondary
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricCol("ENTRY ZONE", "${fmt(signal.entryLow)} - ${fmt(signal.entryHigh)}", InkPrimary)
                MetricCol("STOP", fmt(signal.stopLoss), BearCrimson)
                MetricCol("TP1 (${signal.tp1R}R)", fmt(signal.tp1Price), BullEmerald)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${signal.strategy} • ${signal.regime}",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSecondary
                )
                Text(
                    text = "Risk ${"%.2f".format(signal.suggestedRiskPct)}% | Cost ${"%.2f".format(signal.estCostR)}R | Conv ${(signal.conviction * 100).toInt()}%",
                    style = MonoMetricSmall,
                    color = MeridianTeal
                )
            }

            Text(
                text = signal.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )

            if (verificationState is AiVerificationState.Success) {
                val res = verificationState.result
                val (vBg, vFg) = when (res.verdict) {
                    VerificationVerdict.HIGH_CONVICTION -> BullEmeraldTint to BullEmerald
                    VerificationVerdict.PROCEED_WITH_CAUTION -> WarnAmberTint to WarnAmber
                    VerificationVerdict.WAIT_OR_SKIP -> BearCrimsonTint to BearCrimson
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = "AI VERIFIED: ${res.verdict.displayLabel} (${res.confidenceScore}%)",
                        bg = vBg,
                        fg = vFg
                    )
                    Text(
                        text = res.providerName,
                        style = MonoMetricSmall,
                        color = InkSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalDetailContent(
    signal: SignalRecord,
    candles: List<Candle>,
    analytics: MarketAnalyticsSnapshot,
    verificationState: AiVerificationState,
    onUpdateStatus: (SignalStatus, Double) -> Unit,
    onVerifyWithAi: () -> Unit,
    onAnalyzeWithAi: () -> Unit,
    onCopyTelegram: () -> Unit
) {
    val ins = InstrumentUniverse.find(signal.instrument)
    val decimals = if (ins?.quoteCurrency == "JPY") 3 else if (signal.assetClass == "crypto") 2 else 5
    val statusEnum = SignalStatus.fromWire(signal.status)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${signal.instrument} ${signal.direction.uppercase()} (${signal.timeframe})",
                    style = MaterialTheme.typography.headlineMedium,
                    color = InkPrimary
                )
                Text(
                    text = "${signal.strategy} v${signal.strategyVersion} • cfg ${signal.configHash}",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }
            StatusPill(
                text = signal.riskStatus,
                bg = if (signal.riskStatus == "APPROVED") BullEmeraldTint else WarnAmberTint,
                fg = if (signal.riskStatus == "APPROVED") BullEmerald else WarnAmber
            )
        }

        SignalCandleChart(
            candles = candles,
            entryLow = signal.entryLow,
            entryHigh = signal.entryHigh,
            stopLoss = signal.stopLoss,
            tp1Price = signal.tp1Price,
            tp2Price = signal.tp2Price,
            decimals = decimals
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("RATIONALE & INVALIDATION", style = MaterialTheme.typography.labelMedium, color = MeridianTeal)
                Text("Why: ${signal.rationale}", style = MaterialTheme.typography.bodySmall, color = InkPrimary)
                Text("Invalidation: ${signal.invalidation}", style = MaterialTheme.typography.bodySmall, color = WarnAmber)
                Text("Expires: ${signal.expiresAtIso}", style = MonoMetricSmall, color = InkSecondary)
                Text("Risk Reasons: ${signal.riskReasons.joinToString()}", style = MonoMetricSmall, color = InkSecondary)
            }
        }

        MarketAnalyticsCard(
            analytics = analytics,
            decimals = decimals
        )

        AiVerificationScorecard(
            verificationState = verificationState,
            onVerifyClick = onVerifyWithAi,
            onOpenInAiHub = onAnalyzeWithAi
        )

        OutlinedButton(
            onClick = onCopyTelegram,
            border = BorderStroke(1.dp, MeridianTeal),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("copy_telegram_button")
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = MeridianTeal, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copy Telegram Signal Template", color = MeridianTeal, style = MaterialTheme.typography.titleMedium)
        }

        if (!statusEnum.isTerminal) {
            HorizontalDivider(color = HairlineBorder)
            Text(
                text = "RECORD PAPER OUTCOME (APPEND-ONLY LEDGER EVENT)",
                style = MaterialTheme.typography.labelMedium,
                color = InkSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (statusEnum == SignalStatus.PENDING) {
                    OutcomeBtn("Trigger", InfoBlue, Modifier.weight(1f)) {
                        onUpdateStatus(SignalStatus.TRIGGERED, 0.0)
                    }
                }
                OutcomeBtn("TP1 Hit", MeridianTeal, Modifier.weight(1f)) {
                    onUpdateStatus(SignalStatus.TP1_HIT, signal.tp1R * 0.5 - signal.estCostR)
                }
                OutcomeBtn("Win", BullEmerald, Modifier.weight(1f)) {
                    val blended = (signal.tp1R * 0.5 + signal.tp2R * 0.5) - signal.estCostR
                    onUpdateStatus(SignalStatus.WIN, blended)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutcomeBtn("Loss (-1R)", BearCrimson, Modifier.weight(1f)) {
                    onUpdateStatus(SignalStatus.LOSS, -1.0 - signal.estCostR)
                }
                OutcomeBtn("Breakeven", InkSecondary, Modifier.weight(1f)) {
                    onUpdateStatus(SignalStatus.BREAKEVEN, -signal.estCostR)
                }
                OutcomeBtn("Expire", WarnAmber, Modifier.weight(1f)) {
                    onUpdateStatus(SignalStatus.EXPIRED, 0.0)
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MeridianTeal)
                    Text(
                        text = "Terminal outcome locked: ${statusEnum.wire} (${"%.2f".format(signal.realizedR)}R net)",
                        style = MonoMetricMedium,
                        color = InkPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeBtn(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, color),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.height(48.dp)
    ) {
        Text(text = label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptySignalsCard(onScanClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MeridianTeal,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "No Published Signals Yet",
                style = MaterialTheme.typography.titleMedium,
                color = InkPrimary
            )
            Text(
                text = "Tap Scan Closed Bars to fetch live public Crypto perpetual & FX bars, classify regimes, evaluate S1-S3, and pass candidates through the risk engine.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
            Button(
                onClick = onScanClick,
                colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                modifier = Modifier.height(48.dp)
            ) {
                Text("Run Initial Market Scan")
            }
        }
    }
}

@Composable
fun StatusPill(text: String, bg: Color, fg: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun MetricCol(label: String, value: String, color: Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Text(text = value, style = MonoMetricMedium, color = color)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Shehzad Algo Signal", text))
}
