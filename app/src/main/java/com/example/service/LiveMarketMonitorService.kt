package com.example.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.data.firestore.MeridianLedgerRepository
import com.example.data.firestore.SignalRecord
import com.example.data.market.MarketDataClient
import com.example.domain.model.CandidateSignal
import com.example.domain.model.Direction
import com.example.domain.model.ExecutionMode
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.RiskConfig
import com.example.domain.model.RiskStatus
import com.example.domain.model.SignalStatus
import com.example.domain.model.Timeframe
import com.example.quant.DataQualityChecker
import com.example.quant.MarketContext
import com.example.quant.OpenExposureItem
import com.example.quant.PortfolioRiskState
import com.example.quant.RiskEngine
import com.example.quant.SignalCombiner
import com.example.quant.StopLossProximityMonitor
import com.example.quant.StrategyRegistry
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LiveMarketMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val marketClient = MarketDataClient()
    private val notifiedSignalKeys = ConcurrentHashMap.newKeySet<String>()
    private val lastAlertTimestampByKey = ConcurrentHashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForegroundMonitoring()
            }
        }
        return START_STICKY
    }

    private fun startForegroundMonitoring() {
        NotificationHelper.ensureChannels(this)
        val notification = NotificationHelper.buildLiveMonitorNotification(
            context = this,
            statusText = "Scanning FX & Crypto closed bars 24/7 for signals & SL alerts..."
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            notification,
            serviceType
        )
        _isRunningFlow.value = true

        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                runCatching {
                    runSingleScanCycle()
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                    Log.w(TAG, "24H live monitor cycle encountered an error", err)
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private suspend fun runSingleScanCycle() {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val repository = MeridianLedgerRepository(applicationContext)
        val nowMs = System.currentTimeMillis()
        val config = runCatching {
            repository.observeRiskConfig(uid).firstOrNull()?.toDomain()
        }.getOrNull() ?: RiskConfig()

        val existingSignals = repository.getSignalsOnce().getOrDefault(emptyList())
        val existingKeys = existingSignals.map { it.idempotencyKey }.toSet()
        notifiedSignalKeys.addAll(existingKeys)

        val liveRates = marketClient.fetchSpotFxRatesUsd()
        val events = marketClient.upcomingAndRecentMacroEvents(nowMs)

        val scanTargets: List<Pair<Instrument, Timeframe>> = buildList {
            InstrumentUniverse.cryptoPerps.forEach { ins ->
                add(ins to Timeframe.H1)
                add(ins to Timeframe.H4)
            }
            InstrumentUniverse.fxPairs.forEach { ins ->
                add(ins to Timeframe.H1)
                add(ins to Timeframe.H4)
            }
        }

        val bundles = coroutineScope {
            scanTargets.map { (ins, tf) ->
                async { marketClient.fetchInstrumentBundle(ins, tf, limit = 240) }
            }.awaitAll()
        }

        val candleCache = mutableMapOf<String, List<com.example.domain.model.Candle>>()
        val blockingIssues = mutableListOf<com.example.domain.model.DataQualityIssue>()
        val rawCandidates = mutableListOf<CandidateSignal>()

        for (bundle in bundles) {
            if (bundle.errorMessage != null || bundle.candles.isEmpty()) continue
            val quality = DataQualityChecker.validateAndFilterClosed(
                rawCandles = bundle.candles,
                instrument = bundle.instrument,
                timeframe = bundle.timeframe,
                nowEpochMs = nowMs,
                feedMaxLagSeconds = config.feedMaxLagSeconds,
                checkStaleness = true
            )
            blockingIssues += quality.issues.filter { it.blocksSignals }
            if (quality.closedCandles.isNotEmpty() && (bundle.timeframe == Timeframe.H1 || !candleCache.containsKey(bundle.instrument.symbol))) {
                candleCache[bundle.instrument.symbol] = quality.closedCandles
            }
            if (!quality.isHealthy) continue

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

        val (combined, _) = SignalCombiner.combine(rawCandidates, existingKeys)
        val openItems = existingSignals
            .filter { SignalStatus.fromWire(it.status).isOpen }
            .mapNotNull { rec ->
                val ins = InstrumentUniverse.find(rec.instrument) ?: return@mapNotNull null
                OpenExposureItem(ins, Direction.fromWire(rec.direction), rec.suggestedRiskPct)
            }
            .toMutableList()

        val portfolio = PortfolioRiskState(
            openSignals = openItems,
            blockingQualityIssues = blockingIssues,
            activeEvents = events
        )

        for (item in combined) {
            val decision = RiskEngine.evaluate(
                candidate = item.candidate,
                config = config,
                portfolio = portfolio.copy(openSignals = openItems),
                spotRates = liveRates,
                nowEpochMs = nowMs
            )
            if (decision.status == RiskStatus.REJECTED) continue

            val c = item.candidate
            val record = SignalRecord(
                userId = uid,
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

            val publishRes = repository.publishSignalWithEvent(record)
            if (publishRes.isSuccess && notifiedSignalKeys.add(item.idempotencyKey)) {
                openItems.add(OpenExposureItem(c.instrument, c.direction, decision.approvedRiskPct))
                val entryMid = (c.entryLow + c.entryHigh) / 2.0
                NotificationHelper.postSignalNotification(
                    context = applicationContext,
                    notificationId = (item.idempotencyKey.hashCode() and 0x7FFF) + 5000,
                    title = "NEW LIVE SIGNAL: ${c.instrument.symbol} ${c.direction.name}",
                    body = "${c.timeframe.wire} ${c.strategyId} • Entry ${"%.4f".format(entryMid)} | SL ${"%.4f".format(c.stopLoss)} | TP1 ${"%.4f".format(c.tp1Price)} (${"%.2f".format(decision.approvedRiskPct)}% risk)"
                )
            }
        }

        // Check Stop-Loss proximity across open positions
        val stopAlerts = StopLossProximityMonitor.evaluateOpenPositions(
            signals = existingSignals,
            candlesBySymbol = candleCache,
            spotRates = liveRates
        )
        for (alert in stopAlerts) {
            val prevAlertMs = lastAlertTimestampByKey[alert.id] ?: 0L
            if (nowMs - prevAlertMs >= ALERT_COOLDOWN_MS) {
                lastAlertTimestampByKey[alert.id] = nowMs
                NotificationHelper.postRiskAlertNotification(
                    context = applicationContext,
                    notificationId = (alert.id.hashCode() and 0x7FFF) + 8000,
                    title = alert.title,
                    body = alert.message
                )
            }
        }

        // Refresh persistent foreground notification timestamp
        val utcClock = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            .substringAfter('T').take(5) + " UTC"
        val updatedNotif = NotificationHelper.buildLiveMonitorNotification(
            context = this,
            statusText = "24H Live Active ($utcClock) • ${openItems.size} open pos • ${stopAlerts.size} SL alert(s)"
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.notify(NotificationHelper.FOREGROUND_NOTIFICATION_ID, updatedNotif)
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _isRunningFlow.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _isRunningFlow.value = false
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveMarketMonitorSvc"
        private const val SCAN_INTERVAL_MS = 60_000L
        private const val ALERT_COOLDOWN_MS = 5 * 60_000L

        const val ACTION_START = "com.example.service.ACTION_START_LIVE_MONITOR"
        const val ACTION_STOP = "com.example.service.ACTION_STOP_LIVE_MONITOR"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()
    }
}
