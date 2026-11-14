package com.example.ui.viewmodel

import android.util.Log
import com.example.data.ai.AiChatMessage
import com.example.data.ai.AiHubSettings
import com.example.data.ai.AiProviderType
import com.example.data.ai.AiSettingsStore
import com.example.data.ai.AiSignalVerificationResult
import com.example.data.ai.AiStreamChunk
import com.example.data.ai.AiVerificationState
import com.example.data.ai.ChatRole
import com.example.data.ai.MarketSentimentState
import com.example.data.ai.MultiAiEngine
import com.example.data.ai.PairAiAnalysisState
import com.example.data.firestore.SignalRecord
import com.example.data.firestore.ValidationReportRecord
import com.example.data.market.MarketDataClient
import com.example.data.market.MarketNewsClient
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.ExecutionMode
import com.example.domain.model.LedgerStats
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import com.example.domain.model.Timeframe
import com.example.quant.MarketAnalyticsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val TAG = "AiHubViewModel"

class AiHubViewModel(
    private val settingsStore: AiSettingsStore,
    private val aiEngine: MultiAiEngine = MultiAiEngine(),
    private val newsClient: MarketNewsClient = MarketNewsClient()
) : ViewModel() {

    val settings: StateFlow<AiHubSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AiHubSettings())

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _verificationsBySignalKey = MutableStateFlow<Map<String, AiVerificationState>>(emptyMap())
    val verificationsBySignalKey: StateFlow<Map<String, AiVerificationState>> = _verificationsBySignalKey.asStateFlow()

    private val _sentimentState = MutableStateFlow<MarketSentimentState>(MarketSentimentState.Idle)
    val sentimentState: StateFlow<MarketSentimentState> = _sentimentState.asStateFlow()

    private val _pairAnalysisState = MutableStateFlow<PairAiAnalysisState>(PairAiAnalysisState.Idle)
    val pairAnalysisState: StateFlow<PairAiAnalysisState> = _pairAnalysisState.asStateFlow()

    private var activeJob: Job? = null
    private var sentimentJob: Job? = null
    private var pairAnalysisJob: Job? = null

    fun selectProvider(provider: AiProviderType) {
        viewModelScope.launch {
            _errorBanner.value = null
            settingsStore.setSelectedProvider(provider)
        }
    }

    fun saveProviderConfig(
        provider: AiProviderType,
        model: String,
        apiKey: String,
        customUrl: String? = null
    ) {
        viewModelScope.launch {
            _errorBanner.value = null
            settingsStore.setProviderConfig(provider, model, apiKey, customUrl)
            settingsStore.setSelectedProvider(provider)
        }
    }

    fun setIncludeDeskContext(include: Boolean) {
        viewModelScope.launch {
            settingsStore.setIncludeDeskContext(include)
        }
    }

    fun clearError() {
        _errorBanner.value = null
    }

    fun clearChat() {
        activeJob?.cancel()
        _isGenerating.value = false
        _errorBanner.value = null
        _messages.value = emptyList()
    }

    fun sendMessage(prompt: String, deskContext: String?) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() || _isGenerating.value) return

        val currentSettings = settings.value
        val provider = currentSettings.selectedProvider
        val model = currentSettings.activeModel(provider)
        val apiKey = currentSettings.apiKey(provider)
        val customUrl = currentSettings.customBaseUrl

        val priorHistory = _messages.value
        val nowUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
            .substringAfter('T').take(5) + " UTC"

        val userMsg = AiChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = cleanPrompt,
            providerLabel = provider.displayName,
            modelLabel = model,
            timestampUtc = nowUtc
        )
        val assistantId = UUID.randomUUID().toString()
        val placeholderAssistant = AiChatMessage(
            id = assistantId,
            role = ChatRole.ASSISTANT,
            content = "",
            providerLabel = provider.displayName,
            modelLabel = model,
            timestampUtc = nowUtc,
            isStreaming = true
        )

        _errorBanner.value = null
        _isGenerating.value = true
        _messages.value = priorHistory + userMsg + placeholderAssistant

        activeJob = viewModelScope.launch {
            try {
                val contextPayload = if (currentSettings.includeDeskContext) deskContext else null
                aiEngine.streamResponse(
                    provider = provider,
                    modelName = model,
                    apiKey = apiKey,
                    customEndpoint = customUrl,
                    userPrompt = cleanPrompt,
                    deskContext = contextPayload,
                    history = priorHistory
                ).collect { chunk ->
                    when (chunk) {
                        is AiStreamChunk.Delta -> {
                            updateAssistantMessage(assistantId, chunk.accumulatedText, streaming = true, error = false)
                        }
                        is AiStreamChunk.Complete -> {
                            updateAssistantMessage(assistantId, chunk.fullText, streaming = false, error = false)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI generation failed", e)
                val msg = e.localizedMessage ?: "AI request failed."
                _errorBanner.value = msg
                updateAssistantMessage(assistantId, msg, streaming = false, error = true)
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun verifySignalAsTrader(
        signal: SignalRecord,
        analytics: MarketAnalyticsSnapshot,
        deskContext: String?
    ) {
        val key = signalKeyFor(signal)
        val currentState = _verificationsBySignalKey.value[key]
        if (currentState is AiVerificationState.Verifying) return

        val currentSettings = settings.value
        val provider = currentSettings.selectedProvider
        val model = currentSettings.activeModel(provider)
        val apiKey = currentSettings.apiKey(provider)
        val customUrl = currentSettings.customBaseUrl

        _verificationsBySignalKey.value = _verificationsBySignalKey.value + (key to AiVerificationState.Verifying(key))

        viewModelScope.launch {
            try {
                val result = aiEngine.verifySignalWithAi(
                    provider = provider,
                    modelName = model,
                    apiKey = apiKey,
                    customEndpoint = customUrl,
                    signal = signal,
                    analytics = analytics,
                    deskContext = if (currentSettings.includeDeskContext) deskContext else null,
                    signalKey = key
                )
                _verificationsBySignalKey.value = _verificationsBySignalKey.value + (key to AiVerificationState.Success(result))
            } catch (e: CancellationException) {
                _verificationsBySignalKey.value = _verificationsBySignalKey.value - key
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Signal verification failed for $key", e)
                val msg = e.localizedMessage ?: "Signal verification failed."
                _verificationsBySignalKey.value = _verificationsBySignalKey.value + (key to AiVerificationState.Error(key, msg))
            }
        }
    }

    fun refreshMarketSentiment(riskConfig: RiskConfig, openRiskPct: Double) {
        if (_sentimentState.value is MarketSentimentState.Analyzing) return
        val currentSettings = settings.value
        val provider = currentSettings.selectedProvider
        val model = currentSettings.activeModel(provider)
        val apiKey = currentSettings.apiKey(provider)
        val customUrl = currentSettings.customBaseUrl

        _sentimentState.value = MarketSentimentState.Analyzing
        sentimentJob?.cancel()
        sentimentJob = viewModelScope.launch {
            try {
                val headlines = newsClient.fetchLatestCryptoAndForexNews()
                val upcomingEvents = MarketDataClient().upcomingAndRecentMacroEvents(System.currentTimeMillis())
                val report = aiEngine.analyzeMarketSentimentWithAi(
                    provider = provider,
                    modelName = model,
                    apiKey = apiKey,
                    customEndpoint = customUrl,
                    headlines = headlines,
                    upcomingEvents = upcomingEvents,
                    riskConfig = riskConfig,
                    openRiskPct = openRiskPct
                )
                _sentimentState.value = MarketSentimentState.Success(report)
            } catch (e: CancellationException) {
                if (_sentimentState.value is MarketSentimentState.Analyzing) {
                    _sentimentState.value = MarketSentimentState.Idle
                }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Market sentiment refresh failed", e)
                _sentimentState.value = MarketSentimentState.Error(
                    e.localizedMessage ?: "Unable to analyze live news sentiment."
                )
            }
        }
    }

    fun analyzeSearchedPairWithAi(
        rawQuery: String,
        timeframe: Timeframe,
        riskConfig: RiskConfig,
        spotRates: Map<String, Double>,
        deskContext: String?
    ) {
        val clean = rawQuery.trim().uppercase()
        if (clean.isEmpty()) return

        val currentSettings = settings.value
        val provider = currentSettings.selectedProvider
        val model = currentSettings.activeModel(provider)
        val apiKey = currentSettings.apiKey(provider)
        val customUrl = currentSettings.customBaseUrl

        _pairAnalysisState.value = PairAiAnalysisState.Analyzing(clean, timeframe)
        pairAnalysisJob?.cancel()
        pairAnalysisJob = viewModelScope.launch {
            try {
                val marketClient = MarketDataClient()
                val bundle = marketClient.fetchAnyPairBundle(clean, timeframe, limit = 240)
                if (bundle.candles.isEmpty()) {
                    _pairAnalysisState.value = PairAiAnalysisState.Error(
                        symbol = clean,
                        message = bundle.errorMessage ?: "No closed-bar market feed available for $clean."
                    )
                    return@launch
                }
                val result = aiEngine.analyzeAnyPairWithAi(
                    provider = provider,
                    modelName = model,
                    apiKey = apiKey,
                    customEndpoint = customUrl,
                    bundle = bundle,
                    riskConfig = riskConfig,
                    spotRates = spotRates,
                    deskContext = if (currentSettings.includeDeskContext) deskContext else null
                )
                _pairAnalysisState.value = PairAiAnalysisState.Success(result, bundle.candles.filter { it.isClosed })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI pair analysis failed for $clean", e)
                _pairAnalysisState.value = PairAiAnalysisState.Error(
                    symbol = clean,
                    message = e.localizedMessage ?: "Unable to complete AI analysis for $clean."
                )
            }
        }
    }

    fun clearPairAnalysis() {
        pairAnalysisJob?.cancel()
        _pairAnalysisState.value = PairAiAnalysisState.Idle
    }

    private fun updateAssistantMessage(id: String, content: String, streaming: Boolean, error: Boolean) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == id) {
                msg.copy(content = content, isStreaming = streaming, isError = error)
            } else {
                msg
            }
        }
    }

    companion object {
        fun signalKeyFor(signal: SignalRecord): String =
            signal.id.ifBlank { signal.signalUuid }.ifBlank { signal.idempotencyKey }
        fun buildDeskContextSnapshot(
            signals: List<SignalRecord>,
            riskConfig: RiskConfig,
            paperStats: LedgerStats,
            reports: List<ValidationReportRecord>,
            qualityIssues: List<DataQualityIssue>
        ): String = buildString {
            appendLine("Platform: Shehzad Algo | KillSwitch: ${riskConfig.manualKillSwitch}")
            appendLine("Risk Config: Balance=$${"%.0f".format(riskConfig.accountBalanceUsd)}, Risk/Trade=${riskConfig.riskPerTradePct}%, MaxOpenHeat=${riskConfig.maxOpenRiskPct}%, MaxCluster=${riskConfig.maxClusterRiskPct}%, MinLiqStops=${riskConfig.minLiquidationStops}x")
            appendLine("Paper Ledger: Closed=${paperStats.closedTrades}, Open=${paperStats.openSignals}, WinRate=${"%.1f".format(paperStats.winRatePct)}%, NetR=${"%.2f".format(paperStats.netR)}R, Expectancy=${"%.2f".format(paperStats.expectancyR)}R, PF=${"%.2f".format(paperStats.profitFactor)}, MaxDD=${"%.2f".format(paperStats.maxDrawdownR)}R")
            val openSignals = signals.filter { SignalStatus.fromWire(it.status).isOpen }.take(5)
            if (openSignals.isEmpty()) {
                appendLine("Open Signals: None currently open (${signals.size} total in ledger).")
            } else {
                appendLine("Active/Recent Signals (${openSignals.size}):")
                openSignals.forEach { s ->
                    appendLine("- ${s.instrument} ${s.direction.uppercase()} (${s.timeframe}, ${s.strategy}, ${s.regime}): Entry ${s.entryLow}-${s.entryHigh}, SL ${s.stopLoss}, TP1 ${s.tp1Price} (${s.tp1R}R), Risk ${s.suggestedRiskPct}%, Cost ${"%.2f".format(s.estCostR)}R, Status=${s.status}")
                }
            }
            if (reports.isNotEmpty()) {
                appendLine("Latest Strategy Validation Gates:")
                reports.take(3).forEach { r ->
                    appendLine("- ${r.strategyId}: Status=${r.lifecycleStatus}, OOS Sharpe=${"%.2f".format(r.sharpe)}, PF=${"%.2f".format(r.profitFactor)}, MC95DD=${"%.1f".format(r.mc95DrawdownPct)}%, ExpR=${"%.2f".format(r.netExpectancyR)}, PBO=${"%.2f".format(r.pbo)}, Causality=${r.causalityPassed}")
                }
            }
            val blockers = qualityIssues.filter { it.blocksSignals }
            if (blockers.isNotEmpty()) {
                appendLine("Data Quality Blockers: ${blockers.joinToString { "${it.instrument}(${it.timeframe.wire}):${it.description}" }}")
            }
        }.trim()
    }
}
