package com.example.ui.viewmodel

import android.util.Log
import com.example.data.ai.AiChatMessage
import com.example.data.ai.AiHubSettings
import com.example.data.ai.AiProviderType
import com.example.data.ai.AiSettingsStore
import com.example.data.ai.AiStreamChunk
import com.example.data.ai.ChatRole
import com.example.data.ai.MultiAiEngine
import com.example.data.firestore.SignalRecord
import com.example.data.firestore.ValidationReportRecord
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.ExecutionMode
import com.example.domain.model.LedgerStats
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
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
    private val aiEngine: MultiAiEngine = MultiAiEngine()
) : ViewModel() {

    val settings: StateFlow<AiHubSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AiHubSettings())

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private var activeJob: Job? = null

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
