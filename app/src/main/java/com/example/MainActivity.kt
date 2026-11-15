package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TabletMac
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.AiSettingsStore
import com.example.data.ai.MarketSentimentState
import com.example.data.firestore.MeridianLedgerRepository
import com.example.data.market.MarketDataClient
import com.example.domain.model.ExecutionMode
import com.example.domain.model.SignalStatus
import com.example.domain.model.Timeframe
import com.example.quant.TelegramFormatter
import com.example.service.LiveMarketMonitorService
import com.example.service.NotificationHelper
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.configureAppCheck
import com.example.ui.auth.signOut
import com.example.ui.screens.AiHubScreen
import com.example.ui.screens.LabScreen
import com.example.ui.screens.LedgerScreen
import com.example.ui.screens.RiskScreen
import com.example.ui.screens.SignalsScreen
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
import com.example.ui.theme.MeridianTheme
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.WarnAmber
import com.example.ui.theme.WarnAmberTint
import com.example.ui.viewmodel.AiHubViewModel
import com.example.ui.viewmodel.MeridianViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

enum class MeridianTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    SIGNALS(R.string.tab_signals, Icons.Filled.CandlestickChart, Icons.Outlined.CandlestickChart),
    RISK(R.string.tab_risk, Icons.Filled.Shield, Icons.Outlined.Shield),
    LEDGER(R.string.tab_ledger, Icons.Filled.Book, Icons.Outlined.Book),
    LAB(R.string.tab_lab, Icons.Filled.Science, Icons.Outlined.Science),
    AI_HUB(R.string.tab_ai, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure Firebase App Check before any UI, ViewModel, or Firebase operations
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            MeridianTheme {
                MeridianAppRoot()
            }
        }
    }
}

@Composable
fun MeridianAppRoot() {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = { })
    } else {
        key(user.uid) {
            MeridianTerminalShell(
                currentUserId = user.uid,
                userEmail = user.email ?: "Authenticated"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeridianTerminalShell(
    currentUserId: String,
    userEmail: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: MeridianViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                MeridianViewModel(
                    repository = MeridianLedgerRepository(db),
                    marketClient = MarketDataClient(),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val aiViewModel: AiHubViewModel = viewModel(
        key = "ai_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                AiHubViewModel(settingsStore = AiSettingsStore(app))
            }
        }
    )

    var activeTab by rememberSaveable { mutableStateOf(MeridianTab.SIGNALS) }
    var tabletViewEnabled by rememberSaveable { mutableStateOf(true) }
    var pendingAiPrompt by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = activeTab != MeridianTab.SIGNALS) {
        activeTab = MeridianTab.SIGNALS
    }

    val signalsState by viewModel.signalsState.collectAsStateWithLifecycle()
    val eventsState by viewModel.eventsState.collectAsStateWithLifecycle()
    val riskConfig by viewModel.riskConfigState.collectAsStateWithLifecycle()
    val reports by viewModel.reportsState.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val qualityIssues by viewModel.qualityIssues.collectAsStateWithLifecycle()
    val candlesBySymbol by viewModel.candlesBySymbol.collectAsStateWithLifecycle()
    val spotRates by viewModel.spotRates.collectAsStateWithLifecycle()
    val validatingStrategyId by viewModel.validatingStrategyId.collectAsStateWithLifecycle()
    val liveDetails by viewModel.liveValidationDetails.collectAsStateWithLifecycle()
    val chainVerification by viewModel.chainVerification.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    val live24hVmEnabled by viewModel.live24hEnabled.collectAsStateWithLifecycle()
    val serviceRunning by LiveMarketMonitorService.isRunningFlow.collectAsStateWithLifecycle()
    val live24hEnabled = live24hVmEnabled || serviceRunning
    val activeRiskAlerts by viewModel.activeRiskAlerts.collectAsStateWithLifecycle()

    val verificationsByKey by aiViewModel.verificationsBySignalKey.collectAsStateWithLifecycle()
    val sentimentState by aiViewModel.sentimentState.collectAsStateWithLifecycle()
    val pairAnalysisState by aiViewModel.pairAnalysisState.collectAsStateWithLifecycle()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            scope.launch {
                // In-app Snackbars still operate even if OS tray permission is declined
            }
        }
    }

    val toggleLiveMonitor: (Boolean) -> Unit = { enable ->
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationHelper.hasNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.setLive24hEnabled(enable)
        val svcIntent = Intent(context, LiveMarketMonitorService::class.java).apply {
            action = if (enable) LiveMarketMonitorService.ACTION_START else LiveMarketMonitorService.ACTION_STOP
        }
        runCatching {
            if (enable) {
                ContextCompat.startForegroundService(context, svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    LaunchedEffect(viewModel) {
        NotificationHelper.ensureChannels(context)
        viewModel.localNotifications.collect { notif ->
            val notifId = (notif.id.hashCode() and 0x7FFF) + if (notif.isRiskAlert) 8000 else 5000
            if (notif.isRiskAlert) {
                NotificationHelper.postRiskAlertNotification(context, notifId, notif.title, notif.body)
            } else {
                NotificationHelper.postSignalNotification(context, notifId, notif.title, notif.body)
            }
        }
    }

    val handleCsvUri: (String, Timeframe, Uri) -> Unit = { symbol, tf, uri ->
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.importCsvCandles(symbol, tf, stream)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(ObsidianBg)) {
        val useTabletShell = tabletViewEnabled
        val isCompactWidth = maxWidth < 600.dp

        Scaffold(
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = GraphiteSurface,
                        titleContentColor = InkPrimary
                    ),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("SHEHZAD ALGO", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = if (riskConfig.manualKillSwitch) "HALTED" else if (live24hEnabled) "24H LIVE" else "PAPER DESK",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    riskConfig.manualKillSwitch -> BearCrimson
                                    live24hEnabled -> BullEmerald
                                    else -> MeridianTeal
                                },
                                modifier = Modifier
                                    .background(
                                        when {
                                            riskConfig.manualKillSwitch -> BearCrimsonTint
                                            live24hEnabled -> BullEmeraldTint
                                            else -> MeridianTealTint
                                        },
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable { toggleLiveMonitor(!live24hEnabled) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("topbar_live_24h_button")
                            )
                        }
                    },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .background(
                                    if (useTabletShell) MeridianTealTint else SlateCard,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { tabletViewEnabled = !tabletViewEnabled }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                .testTag("topbar_view_mode_button")
                        ) {
                            Icon(
                                imageVector = if (useTabletShell) Icons.Outlined.TabletMac else Icons.Outlined.Smartphone,
                                contentDescription = if (useTabletShell) "Switch to Mobile View" else "Switch to Tablet View",
                                tint = if (useTabletShell) MeridianTeal else InkSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (useTabletShell) "TABLET" else "MOBILE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (useTabletShell) MeridianTeal else InkSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = userEmail.substringBefore('@'),
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSecondary
                        )
                        IconButton(
                            onClick = { signOut(credentialManager, scope) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Logout,
                                contentDescription = "Sign out",
                                tint = InkSecondary
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (!useTabletShell) {
                    NavigationBar(
                        containerColor = GraphiteSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        MeridianTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(tab.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MeridianTeal,
                                    selectedTextColor = MeridianTeal,
                                    indicatorColor = MeridianTealTint,
                                    unselectedIconColor = InkSecondary,
                                    unselectedTextColor = InkSecondary
                                ),
                                modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useTabletShell) {
                    NavigationRail(
                        containerColor = GraphiteSurface,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(if (isCompactWidth) 64.dp else 76.dp)
                            .testTag("tablet_navigation_rail")
                    ) {
                        MeridianTab.entries.forEach { tab ->
                            val selected = activeTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { activeTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes), style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MeridianTeal,
                                    selectedTextColor = MeridianTeal,
                                    indicatorColor = MeridianTealTint,
                                    unselectedIconColor = InkSecondary,
                                    unselectedTextColor = InkSecondary
                                ),
                                modifier = Modifier.testTag("rail_${tab.name.lowercase()}")
                            )
                        }
                    }
                    VerticalDivider(color = HairlineBorder)
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    val allSignals = (signalsState as? UiState.Success)?.data.orEmpty()
                    val paperStats = remember(allSignals) {
                        viewModel.computeLedgerStats(allSignals, ExecutionMode.PAPER)
                    }
                    val openSignalsCount = remember(allSignals) {
                        allSignals.count { SignalStatus.fromWire(it.status).isOpen }
                    }
                    val openRiskPct = remember(allSignals) {
                        allSignals.filter { SignalStatus.fromWire(it.status).isOpen }
                            .sumOf { it.suggestedRiskPct }
                    }
                    val deskContext = remember(allSignals, riskConfig, paperStats, reports, qualityIssues) {
                        AiHubViewModel.buildDeskContextSnapshot(
                            signals = allSignals,
                            riskConfig = riskConfig,
                            paperStats = paperStats,
                            reports = reports,
                            qualityIssues = qualityIssues
                        )
                    }

                    if (useTabletShell) {
                        TabletOverviewStrip(
                            netPaperR = paperStats.netR,
                            openSignalsCount = openSignalsCount,
                            openRiskPct = openRiskPct,
                            maxOpenRiskPct = riskConfig.maxOpenRiskPct,
                            sentimentState = sentimentState,
                            isHalted = riskConfig.manualKillSwitch,
                            onSelectTab = { activeTab = it }
                        )
                        HorizontalDivider(color = HairlineBorder)
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (activeTab) {
                            MeridianTab.SIGNALS -> SignalsScreen(
                                signalsState = signalsState,
                                scanStatus = scanStatus,
                                riskConfig = riskConfig,
                                qualityIssues = qualityIssues,
                                candlesBySymbol = candlesBySymbol,
                                spotRates = spotRates,
                                verificationsBySignalKey = verificationsByKey,
                                pairAnalysisState = pairAnalysisState,
                                live24hEnabled = live24hEnabled,
                                isTabletMode = useTabletShell,
                                onToggleLive24h = toggleLiveMonitor,
                                onScanClosedBars = { viewModel.scanClosedBarsUniverse() },
                                onUpdateStatus = { sig, st, r -> viewModel.updateSignalStatus(sig, st, r) },
                                onImportCsv = handleCsvUri,
                                onVerifySignalWithAi = { sig, analytics ->
                                    aiViewModel.verifySignalAsTrader(sig, analytics, deskContext)
                                },
                                onAnalyzeWithAi = { sig ->
                                    pendingAiPrompt = "Analyze this signal setup in detail (entry/stop/TP, R-multiple, estimated cost, regime fit, and invalidation):\n\n" +
                                        TelegramFormatter.formatSignal(sig)
                                    activeTab = MeridianTab.AI_HUB
                                },
                                onAnalyzePairWithAi = { rawSym, tf ->
                                    aiViewModel.analyzeSearchedPairWithAi(
                                        rawQuery = rawSym,
                                        timeframe = tf,
                                        riskConfig = riskConfig,
                                        spotRates = spotRates,
                                        deskContext = deskContext
                                    )
                                },
                                onClearPairAnalysis = { aiViewModel.clearPairAnalysis() },
                                onOpenPairInAiChat = { res ->
                                    pendingAiPrompt = "Deep-dive pro trader analysis for ${res.symbol} (${res.timeframe.wire}):\n" +
                                        "Bias=${res.bias.displayLabel} (${res.confidenceScore}%), Spot=${res.currentPrice}, Entry=${res.suggestedEntryLow}-${res.suggestedEntryHigh}, " +
                                        "Vol-SL=${res.volatilityAdjustedStopLoss}, TP1=${res.takeProfit1}, TP2=${res.takeProfit2}, ATR14=${res.atr14}, RSI=${res.rsi14}, ADX=${res.adx14}.\n" +
                                        "Review key invalidation triggers and multi-timeframe execution."
                                    activeTab = MeridianTab.AI_HUB
                                },
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )

                            MeridianTab.RISK -> {
                                LaunchedEffect(activeTab) {
                                    if (sentimentState is MarketSentimentState.Idle) {
                                        aiViewModel.refreshMarketSentiment(
                                            riskConfig = riskConfig,
                                            openRiskPct = openRiskPct
                                        )
                                    }
                                }
                                RiskScreen(
                                    riskConfig = riskConfig,
                                    signals = allSignals,
                                    spotRates = spotRates,
                                    qualityIssues = qualityIssues,
                                    viewModel = viewModel,
                                    candlesBySymbol = candlesBySymbol,
                                    sentimentState = sentimentState,
                                    activeRiskAlerts = activeRiskAlerts,
                                    live24hEnabled = live24hEnabled,
                                    isTabletMode = useTabletShell,
                                    onToggleLive24h = toggleLiveMonitor,
                                    onDismissRiskAlert = { viewModel.dismissRiskAlert(it) },
                                    onRefreshSentiment = {
                                        aiViewModel.refreshMarketSentiment(
                                            riskConfig = riskConfig,
                                            openRiskPct = openRiskPct
                                        )
                                    }
                                )
                            }

                            MeridianTab.LEDGER -> LedgerScreen(
                                signals = allSignals,
                                eventsState = eventsState,
                                chainVerification = chainVerification,
                                viewModel = viewModel,
                                isTabletMode = useTabletShell,
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )

                            MeridianTab.LAB -> LabScreen(
                                storedReports = reports,
                                liveDetails = liveDetails,
                                validatingStrategyId = validatingStrategyId,
                                isTabletMode = useTabletShell,
                                onValidateStrategy = { viewModel.runStrategyValidation(it) },
                                onImportCsv = handleCsvUri
                            )

                            MeridianTab.AI_HUB -> AiHubScreen(
                                aiViewModel = aiViewModel,
                                deskContext = deskContext,
                                signals = allSignals,
                                candlesBySymbol = candlesBySymbol,
                                riskConfig = riskConfig,
                                spotRates = spotRates,
                                qualityIssues = qualityIssues,
                                initialPrompt = pendingAiPrompt,
                                isTabletMode = useTabletShell,
                                onConsumeInitialPrompt = { pendingAiPrompt = null },
                                onShowMessage = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletOverviewStrip(
    netPaperR: Double,
    openSignalsCount: Int,
    openRiskPct: Double,
    maxOpenRiskPct: Double,
    sentimentState: MarketSentimentState,
    isHalted: Boolean,
    onSelectTab: (MeridianTab) -> Unit
) {
    val sentimentReport = (sentimentState as? MarketSentimentState.Success)?.report
    val sentimentLabel = sentimentReport?.regime?.displayLabel ?: "READY"
    val sentimentColor = when {
        sentimentReport == null -> MeridianTeal
        sentimentReport.overallScore >= 20 -> BullEmerald
        sentimentReport.overallScore <= -20 -> BearCrimson
        else -> WarnAmber
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GraphiteSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("tablet_overview_strip"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabletOverviewChip(
            label = "SIGNALS",
            value = "$openSignalsCount OPEN",
            accent = MeridianTeal,
            onClick = { onSelectTab(MeridianTab.SIGNALS) }
        )
        TabletOverviewChip(
            label = "HEAT",
            value = "${"%.2f".format(openRiskPct)}% / ${"%.1f".format(maxOpenRiskPct)}%",
            accent = if (openRiskPct >= maxOpenRiskPct) BearCrimson else BullEmerald,
            onClick = { onSelectTab(MeridianTab.RISK) }
        )
        TabletOverviewChip(
            label = "NET PAPER R",
            value = "${if (netPaperR >= 0) "+" else ""}${"%.2f".format(netPaperR)}R",
            accent = if (netPaperR >= 0) BullEmerald else BearCrimson,
            onClick = { onSelectTab(MeridianTab.LEDGER) }
        )
        TabletOverviewChip(
            label = "AI SENTIMENT",
            value = sentimentLabel,
            accent = sentimentColor,
            onClick = { onSelectTab(MeridianTab.AI_HUB) }
        )
        TabletOverviewChip(
            label = "GUARDIAN",
            value = if (isHalted) "HALTED" else "ARMED",
            accent = if (isHalted) BearCrimson else BullEmerald,
            onClick = { onSelectTab(MeridianTab.RISK) }
        )
    }
}

@Composable
private fun TabletOverviewChip(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            Text(text = value, style = MonoMetricSmall, color = accent)
        }
    }
}
