package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firestore.MeridianLedgerRepository
import com.example.data.firestore.SignalEventRecord
import com.example.data.firestore.SignalRecord
import com.example.data.firestore.ValidationReportRecord
import com.example.data.market.MarketDataClient
import com.example.domain.model.AssetClass
import com.example.domain.model.CandidateSignal
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Direction
import com.example.domain.model.ExecutionMode
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.LedgerStats
import com.example.domain.model.PositionSizingResult
import com.example.domain.model.RiskConfig
import com.example.domain.model.RiskStatus
import com.example.domain.model.SignalStatus
import com.example.domain.model.Timeframe
import com.example.quant.ActiveStopWatchTarget
import com.example.quant.BacktestValidationResult
import com.example.quant.BacktestEngine
import com.example.quant.DataQualityChecker
import com.example.quant.HashChainUtils
import com.example.quant.MarketContext
import com.example.quant.OpenExposureItem
import com.example.quant.PortfolioRiskState
import com.example.quant.RiskAlertEvent
import com.example.quant.RiskEngine
import com.example.quant.SignalCombiner
import com.example.quant.StopLossProximityMonitor
import com.example.quant.StrategyRegistry
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class ScanStatus(
    val isScanning: Boolean = false,
    val lastScanUtc: String? = null,
    val providerSummary: String = "Ready to scan closed bars",
    val candidatesEvaluated: Int = 0,
    val approvedOrReducedCount: Int = 0,
    val rejectedCount: Int = 0,
    val diagnostics: List<String> = emptyList()
)

data class ChainVerificationResult(
    val valid: Boolean,
    val eventCount: Int,
    val headHash: String,
    val brokenAtSequence: Long? = null,
    val message: String
)

data class LocalAlertNotification(
    val id: String,
    val isRiskAlert: Boolean,
    val title: String,
    val body: String
)

class MeridianViewModel(
    private val repository: MeridianLedgerRepository,
    private val marketClient: MarketDataClient,
    private val currentUserId: String
) : ViewModel() {

    val signalsState: StateFlow<UiState<List<SignalRecord>>> = repository.observeSignals(currentUserId)
        .map<List<SignalRecord>, UiState<List<SignalRecord>>> { UiState.Success(it) }
        .catch { error ->
            val root = unwrapFirestoreError(error) ?: error
            if (root is CancellationException && unwrapFirestoreError(error) == null) throw error
            Log.w(TAG, "Error observing signals", root)
            emit(UiState.Error(root.message ?: "Failed to observe signals"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val eventsState: StateFlow<UiState<List<SignalEventRecord>>> = repository.observeSignalEvents(currentUserId)
        .map<List<SignalEventRecord>, UiState<List<SignalEventRecord>>> { UiState.Success(it) }
        .catch { error ->
            val root = unwrapFirestoreError(error) ?: error
            if (root is CancellationException && unwrapFirestoreError(error) == null) throw error
            Log.w(TAG, "Error observing events", root)
            emit(UiState.Error(root.message ?: "Failed to observe ledger events"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val riskConfigState: StateFlow<RiskConfig> = repository.observeRiskConfig(currentUserId)
        .map { it?.toDomain() ?: RiskConfig() }
        .catch { error ->
            Log.w(TAG, "Error observing risk config", error)
            emit(RiskConfig())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RiskConfig())

    val reportsState: StateFlow<List<ValidationReportRecord>> = repository.observeValidationReports(currentUserId)
        .catch { error ->
            Log.w(TAG, "Error observing validation reports", error)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _candlesBySymbol = MutableStateFlow<Map<String, List<Candle>>>(emptyMap())
    val candlesBySymbol: StateFlow<Map<String, List<Candle>>> = _candlesBySymbol.asStateFlow()

    private val _qualityIssues = MutableStateFlow<List<DataQualityIssue>>(emptyList())
    val qualityIssues: StateFlow<List<DataQualityIssue>> = _qualityIssues.asStateFlow()

    private val _spotRates = MutableStateFlow(
        mapOf(
            "EURUSD" to 1.085,
            "GBPUSD" to 1.270,
            "USDJPY" to 153.5,
            "AUDUSD" to 0.655,
            "USDCAD" to 1.395,
            "USDCHF" to 0.880,
            "NZDUSD" to 0.595
        )
    )
    val spotRates: StateFlow<Map<String, Double>> = _spotRates.asStateFlow()

    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val _validatingStrategyId = MutableStateFlow<String?>(null)
    val validatingStrategyId: StateFlow<String?> = _validatingStrategyId.asStateFlow()

    private val _liveValidationDetails = MutableStateFlow<Map<String, BacktestValidationResult>>(emptyMap())
    val liveValidationDetails: StateFlow<Map<String, BacktestValidationResult>> = _liveValidationDetails.asStateFlow()

    private val _chainVerification = MutableStateFlow<ChainVerificationResult?>(null)
    val chainVerification: StateFlow<ChainVerificationResult?> = _chainVerification.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _live24hEnabled = MutableStateFlow(false)
    val live24hEnabled: StateFlow<Boolean> = _live24hEnabled.asStateFlow()

    private val _activeRiskAlerts = MutableStateFlow<List<RiskAlertEvent>>(emptyList())
    val activeRiskAlerts: StateFlow<List<RiskAlertEvent>> = _activeRiskAlerts.asStateFlow()

    private val _dismissedAlertIds = MutableStateFlow<Set<String>>(emptySet())
    private val _latestActiveSetup = MutableStateFlow<ActiveStopWatchTarget?>(null)

    private val _localNotifications = MutableSharedFlow<LocalAlertNotification>(extraBufferCapacity = 24)
    val localNotifications: SharedFlow<LocalAlertNotification> = _localNotifications.asSharedFlow()

    private val alertedIdsThisSession = mutableSetOf<String>()
    private var liveLoopJob: Job? = null

    init {
        viewModelScope.launch {
            _spotRates.value = marketClient.fetchSpotFxRatesUsd()
        }
        viewModelScope.launch {
            signalsState.collect { st ->
                if (st is UiState.Success) {
                    evaluateRiskProximity(_latestActiveSetup.value)
                }
            }
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun setLive24hEnabled(enabled: Boolean) {
        if (_live24hEnabled.value == enabled) return
        _live24hEnabled.value = enabled
        liveLoopJob?.cancel()
        if (enabled) {
            _snackbarMessage.value = "24H Live Monitor enabled — auto-scanning pairs for signals & SL alerts."
            liveLoopJob = viewModelScope.launch {
                while (isActive && _live24hEnabled.value) {
                    scanClosedBarsUniverseAsync().join()
                    delay(45_000L)
                }
            }
        } else {
            _snackbarMessage.value = "24H Live Monitor paused."
        }
    }

    fun evaluateRiskProximity(activeTarget: ActiveStopWatchTarget? = _latestActiveSetup.value) {
        _latestActiveSetup.value = activeTarget
        val signals = (signalsState.value as? UiState.Success)?.data.orEmpty()
        val alerts = StopLossProximityMonitor.evaluateOpenPositions(
            signals = signals,
            candlesBySymbol = _candlesBySymbol.value,
            spotRates = _spotRates.value,
            activeSetup = activeTarget
        )
        val dismissed = _dismissedAlertIds.value
        val visible = alerts.filterNot { it.id in dismissed }
        _activeRiskAlerts.value = visible

        visible.firstOrNull { it.id !in alertedIdsThisSession }?.let { freshAlert ->
            alertedIdsThisSession.add(freshAlert.id)
            _snackbarMessage.value = "${freshAlert.title} — ${freshAlert.message}"
            _localNotifications.tryEmit(
                LocalAlertNotification(
                    id = freshAlert.id,
                    isRiskAlert = true,
                    title = freshAlert.title,
                    body = freshAlert.message
                )
            )
        }
    }

    fun dismissRiskAlert(alertId: String) {
        _dismissedAlertIds.value = _dismissedAlertIds.value + alertId
        _activeRiskAlerts.value = _activeRiskAlerts.value.filterNot { it.id == alertId }
    }

    fun scanClosedBarsUniverse() {
        scanClosedBarsUniverseAsync()
    }

    private fun scanClosedBarsUniverseAsync(): Job {
        if (_scanStatus.value.isScanning) return viewModelScope.launch { }
        return viewModelScope.launch {
            _scanStatus.value = _scanStatus.value.copy(
                isScanning = true,
                providerSummary = "Fetching closed bars from public FX & Crypto feeds...",
                diagnostics = emptyList()
            )
            try {
                val nowMs = System.currentTimeMillis()
                val config = riskConfigState.value
                val liveRates = marketClient.fetchSpotFxRatesUsd()
                _spotRates.value = liveRates
                val events = marketClient.upcomingAndRecentMacroEvents(nowMs)

                val scanTargets: List<Pair<Instrument, Timeframe>> = buildList {
                    // Crypto 1H + 4H for S1 & S3
                    InstrumentUniverse.cryptoPerps.forEach { ins ->
                        add(ins to Timeframe.H1)
                        add(ins to Timeframe.H4)
                    }
                    // FX 1H + 4H for S2 & S1
                    InstrumentUniverse.fxPairs.forEach { ins ->
                        add(ins to Timeframe.H1)
                        add(ins to Timeframe.H4)
                    }
                }

                val bundles = coroutineScope {
                    scanTargets.map { (ins, tf) ->
                        async { marketClient.fetchInstrumentBundle(ins, tf, limit = 260) }
                    }.awaitAll()
                }

                val newIssues = mutableListOf<DataQualityIssue>()
                val candleCache = _candlesBySymbol.value.toMutableMap()
                val rawCandidates = mutableListOf<CandidateSignal>()
                val diagnostics = mutableListOf<String>()
                val providersUsed = linkedSetOf<String>()

                for (bundle in bundles) {
                    if (bundle.errorMessage != null) {
                        diagnostics.add("${bundle.instrument.symbol} (${bundle.timeframe.wire}): ${bundle.errorMessage}")
                        continue
                    }
                    providersUsed.add(bundle.providerName)
                    val quality = DataQualityChecker.validateAndFilterClosed(
                        rawCandles = bundle.candles,
                        instrument = bundle.instrument,
                        timeframe = bundle.timeframe,
                        nowEpochMs = nowMs,
                        feedMaxLagSeconds = config.feedMaxLagSeconds,
                        checkStaleness = true
                    )
                    newIssues += quality.issues
                    if (quality.closedCandles.isNotEmpty()) {
                        // Prefer 1H candles in chart preview cache
                        if (bundle.timeframe == Timeframe.H1 || !candleCache.containsKey(bundle.instrument.symbol)) {
                            candleCache[bundle.instrument.symbol] = quality.closedCandles
                        }
                    }
                    if (!quality.isHealthy) {
                        diagnostics.add("${bundle.instrument.symbol} ${bundle.timeframe.wire} blocked by quality gate.")
                        continue
                    }

                    val ctx = MarketContext(
                        instrument = bundle.instrument,
                        timeframe = bundle.timeframe,
                        nowEpochMs = nowMs,
                        candles = quality.closedCandles,
                        dailyCandles = bundle.dailyCandles,
                        fundingHistory = bundle.fundingHistory,
                        openInterestHistory = bundle.openInterestHistory,
                        economicEvents = events,
                        riskConfig = config
                    )

                    for (strategy in StrategyRegistry.mvpStrategies) {
                        rawCandidates += strategy.onBar(ctx)
                    }
                }

                _candlesBySymbol.value = candleCache
                _qualityIssues.value = newIssues

                val currentSignals = (signalsState.value as? UiState.Success)?.data.orEmpty()
                val existingKeys = currentSignals.map { it.idempotencyKey }.toSet()
                val (combined, combinerLogs) = SignalCombiner.combine(rawCandidates, existingKeys)
                diagnostics += combinerLogs

                val openItems = currentSignals
                    .filter { SignalStatus.fromWire(it.status).isOpen }
                    .mapNotNull { rec ->
                        val ins = InstrumentUniverse.find(rec.instrument) ?: return@mapNotNull null
                        OpenExposureItem(ins, Direction.fromWire(rec.direction), rec.suggestedRiskPct)
                    }
                    .toMutableList()

                val portfolio = buildPortfolioRiskState(currentSignals, openItems, newIssues, events, nowMs)

                var approvedCount = 0
                var rejectedCount = 0

                for (item in combined) {
                    val decision = RiskEngine.evaluate(
                        candidate = item.candidate,
                        config = config,
                        portfolio = portfolio.copy(openSignals = openItems),
                        spotRates = liveRates,
                        nowEpochMs = nowMs
                    )
                    if (decision.status == RiskStatus.REJECTED) {
                        rejectedCount++
                        diagnostics.add("REJECTED ${item.candidate.instrument.symbol} (${item.candidate.strategyId}): ${decision.reasons.joinToString()}")
                        continue
                    }
                    approvedCount++
                    val c = item.candidate
                    val record = SignalRecord(
                        userId = currentUserId,
                        signalUuid = UUID.randomUUID().toString(),
                        createdAtIso = Instant.ofEpochMilli(c.barCloseEpochMs).toString(),
                        instrument = c.instrument.symbol,
                        assetClass = c.instrument.assetClass.wire,
                        direction = c.direction.wire,
                        strategy = c.strategyId,
                        strategyVersion = c.strategyVersion,
                        configHash = c.configHash,
                        timeframe = c.timeframe.wire,
                        regime = c.regime.name,
                        conviction = c.conviction,
                        entryType = "zone",
                        entryLow = c.entryLow,
                        entryHigh = c.entryHigh,
                        stopLoss = c.stopLoss,
                        tp1Price = c.tp1Price,
                        tp1R = c.tp1R,
                        tp1ClosePct = c.tp1ClosePct.toLong(),
                        tp2Price = c.tp2Price,
                        tp2R = c.tp2R,
                        tp2ClosePct = c.tp2ClosePct.toLong(),
                        suggestedRiskPct = decision.approvedRiskPct,
                        estCostR = c.estCostR,
                        expiresAtIso = Instant.ofEpochMilli(c.expiresAtEpochMs).toString(),
                        invalidation = c.invalidation,
                        rationale = c.rationale,
                        riskStatus = decision.status.wire,
                        riskAdjustments = decision.adjustments,
                        riskReasons = decision.reasons,
                        idempotencyKey = item.idempotencyKey,
                        executionMode = ExecutionMode.PAPER.wire,
                        status = SignalStatus.PENDING.wire
                    )
                    val res = repository.publishSignalWithEvent(record)
                    if (res.isSuccess) {
                        openItems.add(OpenExposureItem(c.instrument, c.direction, decision.approvedRiskPct))
                        val entryMid = (c.entryLow + c.entryHigh) / 2.0
                        val alertTitle = "NEW SIGNAL: ${c.instrument.symbol} ${c.direction.name}"
                        val alertBody = "${c.timeframe.wire} ${c.strategyId} • Entry ${"%.4f".format(entryMid)} | SL ${"%.4f".format(c.stopLoss)} | TP1 ${"%.4f".format(c.tp1Price)}"
                        _snackbarMessage.value = "$alertTitle — $alertBody"
                        _localNotifications.tryEmit(
                            LocalAlertNotification(
                                id = item.idempotencyKey,
                                isRiskAlert = false,
                                title = alertTitle,
                                body = alertBody
                            )
                        )
                    } else {
                        diagnostics.add("Ledger write failed for ${c.instrument.symbol}: ${res.exceptionOrNull()?.localizedMessage}")
                    }
                }

                evaluateRiskProximity(_latestActiveSetup.value)

                val summary = if (providersUsed.isEmpty()) {
                    "Scan completed with no live data feeds reachable"
                } else {
                    "Feeds: ${providersUsed.joinToString(", ")}"
                }

                _scanStatus.value = ScanStatus(
                    isScanning = false,
                    lastScanUtc = Instant.ofEpochMilli(nowMs).toString().take(19) + "Z",
                    providerSummary = summary,
                    candidatesEvaluated = rawCandidates.size,
                    approvedOrReducedCount = approvedCount,
                    rejectedCount = rejectedCount,
                    diagnostics = diagnostics.ifEmpty {
                        listOf("Scanned ${bundles.size} feeds. Closed-bar strategies evaluated cleanly; $approvedCount signal(s) published.")
                    }
                )
            } catch (e: Exception) {
                _scanStatus.value = _scanStatus.value.copy(
                    isScanning = false,
                    providerSummary = "Scan error: ${e.localizedMessage}",
                    diagnostics = listOf(e.localizedMessage ?: "Unknown scan failure")
                )
            }
        }
    }

    fun updateSignalStatus(
        signal: SignalRecord,
        newStatus: SignalStatus,
        realizedR: Double
    ) {
        viewModelScope.launch {
            val mae = if (realizedR < 0) realizedR else -0.25
            val mfe = max(realizedR, signal.mfeR)
            val createdMs = runCatching { Instant.parse(signal.createdAtIso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            val durationMin = ((System.currentTimeMillis() - createdMs) / 60_000L).coerceAtLeast(1L)
            val res = repository.updateSignalLifecycle(
                signalId = signal.id,
                newStatus = newStatus,
                realizedR = realizedR,
                maeR = mae,
                mfeR = mfe,
                durationMinutes = durationMin
            )
            _snackbarMessage.value = if (res.isSuccess) {
                "Ledger event appended: ${signal.instrument} -> ${newStatus.wire} (${"%.2f".format(realizedR)}R)"
            } else {
                "Ledger update rejected: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun saveRiskConfig(newConfig: RiskConfig, killSwitchToggled: Boolean = false) {
        viewModelScope.launch {
            val res = repository.saveRiskConfig(newConfig)
            if (res.isSuccess && killSwitchToggled) {
                repository.appendAuditEvent(
                    signalId = "SYSTEM_RISK",
                    eventType = "KILL_SWITCH_TOGGLED",
                    payloadSummary = "manualKillSwitch=${newConfig.manualKillSwitch}"
                )
            }
            _snackbarMessage.value = if (res.isSuccess) {
                if (killSwitchToggled) {
                    if (newConfig.manualKillSwitch) "Kill switch ENGAGED — new signals blocked." else "Kill switch RELEASED."
                } else {
                    "Risk parameters saved to Firestore."
                }
            } else {
                "Failed to save risk config: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun runStrategyValidation(strategyId: String) {
        if (_validatingStrategyId.value != null) return
        val strategy = StrategyRegistry.byId(strategyId) ?: return
        viewModelScope.launch {
            _validatingStrategyId.value = strategyId
            try {
                val config = riskConfigState.value
                val datasets = mutableListOf<Triple<Instrument, Timeframe, List<Candle>>>()
                val fundingMap = mutableMapOf<String, List<com.example.domain.model.FundingPoint>>()
                val oiMap = mutableMapOf<String, List<com.example.domain.model.OpenInterestPoint>>()

                val targetPairs: List<Pair<Instrument, Timeframe>> = when (strategyId) {
                    "S1_TREND_DONCHIAN" -> listOf(
                        InstrumentUniverse.find("BTCUSDT")!! to Timeframe.H4,
                        InstrumentUniverse.find("ETHUSDT")!! to Timeframe.H4,
                        InstrumentUniverse.find("EURUSD")!! to Timeframe.D1,
                        InstrumentUniverse.find("USDJPY")!! to Timeframe.D1
                    )
                    "S2_SESSION_BREAKOUT" -> listOf(
                        InstrumentUniverse.find("EURUSD")!! to Timeframe.H1,
                        InstrumentUniverse.find("GBPUSD")!! to Timeframe.H1,
                        InstrumentUniverse.find("USDJPY")!! to Timeframe.H1
                    )
                    else -> listOf(
                        InstrumentUniverse.find("BTCUSDT")!! to Timeframe.H1,
                        InstrumentUniverse.find("ETHUSDT")!! to Timeframe.H1,
                        InstrumentUniverse.find("SOLUSDT")!! to Timeframe.H1
                    )
                }

                val bundles = coroutineScope {
                    targetPairs.map { (ins, tf) ->
                        async { marketClient.fetchInstrumentBundle(ins, tf, limit = 900) }
                    }.awaitAll()
                }

                for (b in bundles) {
                    if (b.candles.isNotEmpty()) {
                        datasets.add(Triple(b.instrument, b.timeframe, b.candles))
                        if (b.fundingHistory.isNotEmpty()) fundingMap[b.instrument.symbol] = b.fundingHistory
                        if (b.openInterestHistory.isNotEmpty()) oiMap[b.instrument.symbol] = b.openInterestHistory
                    }
                }

                if (datasets.isEmpty()) {
                    _snackbarMessage.value = "No historical bars available for $strategyId. Import CSV or check network."
                    return@launch
                }

                val result = BacktestEngine.runValidationPipeline(
                    strategy = strategy,
                    datasets = datasets,
                    fundingBySymbol = fundingMap,
                    oiBySymbol = oiMap,
                    riskConfig = config,
                    seed = 42
                )

                _liveValidationDetails.value = _liveValidationDetails.value + (strategyId to result)

                val record = ValidationReportRecord(
                    userId = currentUserId,
                    strategyId = result.strategyId,
                    strategyVersion = result.strategyVersion,
                    configHash = result.configHash,
                    dataSnapshotId = result.dataSnapshotId,
                    randomSeed = result.randomSeed.toLong(),
                    lifecycleStatus = result.lifecycleStatus.wire,
                    trialCount = result.trialCount.toLong(),
                    oosTrades = result.oosTrades.toLong(),
                    netExpectancyR = result.netExpectancyR,
                    profitFactor = result.profitFactor,
                    sharpe = result.sharpe,
                    deflatedSharpe = result.deflatedSharpe,
                    pbo = result.pbo,
                    mc95DrawdownPct = result.mc95DrawdownPct,
                    ruinProbPct = result.ruinProbPct,
                    positiveYearsPct = result.positiveYearsPct,
                    causalityPassed = result.causalityPassed,
                    beatRandom95th = result.beatRandom95th,
                    failureReasons = result.failureReasons,
                    equityCurveR = result.equityCurveR
                )
                repository.saveValidationReport(record)
                _snackbarMessage.value = "$strategyId validation complete: ${result.lifecycleStatus.wire} (${result.oosTrades} OOS trades)"
            } catch (e: Exception) {
                _snackbarMessage.value = "Validation error: ${e.localizedMessage}"
            } finally {
                _validatingStrategyId.value = null
            }
        }
    }

    fun importCsvCandles(symbol: String, timeframe: Timeframe, inputStream: InputStream) {
        viewModelScope.launch {
            val ins = InstrumentUniverse.find(symbol) ?: return@launch
            val parsed = marketClient.parseCsvCandles(inputStream, timeframe)
            if (parsed.isEmpty()) {
                _snackbarMessage.value = "CSV contained no valid OHLCV rows."
                return@launch
            }
            marketClient.registerCsvCandles(symbol, timeframe, parsed)
            val quality = DataQualityChecker.validateAndFilterClosed(
                rawCandles = parsed,
                instrument = ins,
                timeframe = timeframe,
                nowEpochMs = System.currentTimeMillis(),
                feedMaxLagSeconds = riskConfigState.value.feedMaxLagSeconds,
                checkStaleness = false
            )
            _candlesBySymbol.value = _candlesBySymbol.value + (symbol to quality.closedCandles)
            _qualityIssues.value = (_qualityIssues.value.filterNot { it.instrument == symbol && it.timeframe == timeframe }) + quality.issues
            _snackbarMessage.value = "Imported ${quality.closedCandles.size} closed bars for $symbol (${timeframe.wire})."
        }
    }

    fun verifyHashChain() {
        val events = (eventsState.value as? UiState.Success)?.data.orEmpty()
            .sortedBy { it.sequenceIndex }
        if (events.isEmpty()) {
            _chainVerification.value = ChainVerificationResult(
                valid = true,
                eventCount = 0,
                headHash = HashChainUtils.GENESIS_HASH,
                message = "Ledger is at genesis state (0 events)."
            )
            return
        }
        var expectedPrev = HashChainUtils.GENESIS_HASH
        for (ev in events) {
            if (ev.prevHash != expectedPrev) {
                _chainVerification.value = ChainVerificationResult(
                    valid = false,
                    eventCount = events.size,
                    headHash = events.last().eventHash,
                    brokenAtSequence = ev.sequenceIndex,
                    message = "PrevHash mismatch at sequence #${ev.sequenceIndex}"
                )
                return
            }
            val recomputed = HashChainUtils.computeEventHash(
                prevHash = ev.prevHash,
                sequenceIndex = ev.sequenceIndex,
                signalId = ev.signalId,
                eventType = ev.eventType,
                timestampIso = ev.timestampIso,
                payloadSummary = ev.payloadSummary
            )
            if (recomputed != ev.eventHash) {
                _chainVerification.value = ChainVerificationResult(
                    valid = false,
                    eventCount = events.size,
                    headHash = events.last().eventHash,
                    brokenAtSequence = ev.sequenceIndex,
                    message = "SHA-256 tamper mismatch at sequence #${ev.sequenceIndex}"
                )
                return
            }
            expectedPrev = ev.eventHash
        }
        _chainVerification.value = ChainVerificationResult(
            valid = true,
            eventCount = events.size,
            headHash = events.last().eventHash,
            message = "All ${events.size} ledger events verified intact via SHA-256."
        )
    }

    fun computeSizingPreview(
        instrument: Instrument,
        entryPrice: Double,
        stopLoss: Double,
        customBalanceUsd: Double,
        customRiskPct: Double
    ): PositionSizingResult {
        return RiskEngine.calculatePositionSize(
            instrument = instrument,
            accountBalanceUsd = customBalanceUsd,
            riskPct = customRiskPct,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            spotRates = _spotRates.value,
            minLiquidationStops = riskConfigState.value.minLiquidationStops
        )
    }

    fun computeLedgerStats(signals: List<SignalRecord>, mode: ExecutionMode): LedgerStats {
        val scoped = signals.filter { it.executionMode.equals(mode.wire, ignoreCase = true) }
            .sortedBy { it.createdAtIso }
        val openCount = scoped.count { SignalStatus.fromWire(it.status).isOpen }
        val closed = scoped.filter {
            val st = SignalStatus.fromWire(it.status)
            st == SignalStatus.WIN || st == SignalStatus.LOSS || st == SignalStatus.BREAKEVEN
        }
        if (closed.isEmpty()) {
            return LedgerStats(
                mode = mode,
                totalTrades = scoped.size,
                closedTrades = 0,
                openSignals = openCount,
                winCount = 0,
                lossCount = 0,
                winRatePct = 0.0,
                netR = 0.0,
                avgR = 0.0,
                expectancyR = 0.0,
                profitFactor = 0.0,
                maxDrawdownR = 0.0,
                sharpeApprox = 0.0,
                equityCurveR = listOf(0.0),
                drawdownCurveR = listOf(0.0)
            )
        }
        val wins = closed.filter { it.realizedR > 0.0 }
        val losses = closed.filter { it.realizedR < 0.0 }
        val grossWin = wins.sumOf { it.realizedR }
        val grossLoss = abs(losses.sumOf { it.realizedR })
        val netR = closed.sumOf { it.realizedR }
        val avgR = netR / closed.size.toDouble()
        val pf = if (grossLoss > 0.0) grossWin / grossLoss else if (grossWin > 0.0) 3.0 else 0.0
        val stdR = if (closed.size > 1) {
            val v = closed.sumOf { (it.realizedR - avgR) * (it.realizedR - avgR) }
            sqrt(v / (closed.size - 1))
        } else 1.0
        val sharpe = if (stdR > 1e-9) (avgR / stdR) * sqrt(36.0) else 0.0

        val eq = mutableListOf(0.0)
        val dd = mutableListOf(0.0)
        var cum = 0.0
        var peak = 0.0
        var maxDd = 0.0
        for (s in closed) {
            cum += s.realizedR
            if (cum > peak) peak = cum
            val curDd = peak - cum
            if (curDd > maxDd) maxDd = curDd
            eq.add(cum)
            dd.add(curDd)
        }

        return LedgerStats(
            mode = mode,
            totalTrades = scoped.size,
            closedTrades = closed.size,
            openSignals = openCount,
            winCount = wins.size,
            lossCount = losses.size,
            winRatePct = (wins.size.toDouble() / closed.size.toDouble()) * 100.0,
            netR = netR,
            avgR = avgR,
            expectancyR = avgR,
            profitFactor = pf,
            maxDrawdownR = maxDd,
            sharpeApprox = sharpe,
            equityCurveR = eq,
            drawdownCurveR = dd
        )
    }

    private fun buildPortfolioRiskState(
        signals: List<SignalRecord>,
        openItems: List<OpenExposureItem>,
        issues: List<DataQualityIssue>,
        events: List<com.example.domain.model.EconomicEvent>,
        nowMs: Long
    ): PortfolioRiskState {
        val dayCutoff = Instant.ofEpochMilli(nowMs).minus(1, ChronoUnit.DAYS)
        val weekCutoff = Instant.ofEpochMilli(nowMs).minus(7, ChronoUnit.DAYS)
        val closed = signals.filter {
            val st = SignalStatus.fromWire(it.status)
            st == SignalStatus.WIN || st == SignalStatus.LOSS || st == SignalStatus.BREAKEVEN
        }
        val dailyR = closed.filter {
            runCatching { Instant.parse(it.createdAtIso).isAfter(dayCutoff) }.getOrDefault(false)
        }.sumOf { it.realizedR }
        val weeklyR = closed.filter {
            runCatching { Instant.parse(it.createdAtIso).isAfter(weekCutoff) }.getOrDefault(false)
        }.sumOf { it.realizedR }

        val stats = computeLedgerStats(signals, ExecutionMode.PAPER)
        val ddPct = stats.maxDrawdownR * riskConfigState.value.riskPerTradePct

        return PortfolioRiskState(
            openSignals = openItems,
            realizedDailyR = dailyR,
            realizedWeeklyR = weeklyR,
            currentDrawdownPct = ddPct,
            feedLagSeconds = 0L,
            blockingQualityIssues = issues.filter { it.blocksSignals },
            activeEvents = events
        )
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    private companion object {
        const val TAG = "MeridianVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
