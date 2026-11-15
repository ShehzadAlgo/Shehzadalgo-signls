package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ai.AiChatMessage
import com.example.data.ai.AiHubSettings
import com.example.data.ai.AiProviderType
import com.example.data.ai.AiVerificationState
import com.example.data.ai.ChatRole
import com.example.data.ai.WebAiPortal
import com.example.data.ai.WebAiPortals
import com.example.data.firestore.SignalRecord
import com.example.domain.model.Candle
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import com.example.quant.MarketAnalyticsEngine
import com.example.ui.components.AiVerificationScorecard
import com.example.ui.components.MarketAnalyticsCard
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
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.WarnAmber
import com.example.ui.theme.WarnAmberTint
import com.example.ui.viewmodel.AiHubViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiHubScreen(
    aiViewModel: AiHubViewModel,
    deskContext: String,
    signals: List<SignalRecord> = emptyList(),
    candlesBySymbol: Map<String, List<Candle>> = emptyMap(),
    riskConfig: RiskConfig = RiskConfig(),
    spotRates: Map<String, Double> = emptyMap(),
    qualityIssues: List<DataQualityIssue> = emptyList(),
    initialPrompt: String?,
    isTabletMode: Boolean = false,
    onConsumeInitialPrompt: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val settings by aiViewModel.settings.collectAsStateWithLifecycle()
    val messages by aiViewModel.messages.collectAsStateWithLifecycle()
    val isGenerating by aiViewModel.isGenerating.collectAsStateWithLifecycle()
    val errorBanner by aiViewModel.errorBanner.collectAsStateWithLifecycle()
    val verificationsByKey by aiViewModel.verificationsBySignalKey.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    var showConfigSheet by remember { mutableStateOf(false) }
    var showWebPortals by remember { mutableStateOf(false) }
    var selectedVerifySignalKey by remember(signals) {
        mutableStateOf(
            signals.firstOrNull { SignalStatus.fromWire(it.status).isOpen }?.let(AiHubViewModel::signalKeyFor)
                ?: signals.firstOrNull()?.let(AiHubViewModel::signalKeyFor)
        )
    }
    val listState = rememberLazyListState()

    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            aiViewModel.sendMessage(initialPrompt, deskContext)
            onConsumeInitialPrompt()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val activeProvider = settings.selectedProvider
    val activeModel = settings.activeModel(activeProvider)
    val needsKeyNow = activeProvider.requiresApiKey && settings.apiKey(activeProvider).isBlank()

    val headerBlock: @Composable () -> Unit = {
        AiEngineHeaderCard(
            settings = settings,
            activeProvider = activeProvider,
            activeModel = activeModel,
            needsKeyNow = needsKeyNow,
            showWebPortals = showWebPortals,
            onToggleWebPortals = { showWebPortals = !showWebPortals },
            onSelectProvider = { aiViewModel.selectProvider(it) },
            onOpenConfig = { showConfigSheet = true },
            onToggleContext = { aiViewModel.setIncludeDeskContext(it) },
            onClearChat = { aiViewModel.clearChat() }
        )
    }

    val signalVerifierBlock: @Composable () -> Unit = {
        val verifiableSignals = remember(signals) {
            (signals.filter { SignalStatus.fromWire(it.status).isOpen } +
                signals.filter { !SignalStatus.fromWire(it.status).isOpen }).take(8)
        }
        val targetSignal = verifiableSignals.firstOrNull {
            AiHubViewModel.signalKeyFor(it) == selectedVerifySignalKey
        } ?: verifiableSignals.first()
        val targetKey = AiHubViewModel.signalKeyFor(targetSignal)
        val targetCandles = candlesBySymbol[targetSignal.instrument].orEmpty()
        val targetAnalytics = remember(targetSignal, targetCandles, riskConfig, signals, spotRates, qualityIssues) {
            MarketAnalyticsEngine.compute(targetSignal, targetCandles, riskConfig, signals, spotRates, qualityIssues)
        }
        val targetDecimals = if (targetSignal.instrument.endsWith("JPY")) 3 else if (targetSignal.assetClass == "crypto") 2 else 5
        val targetVerification = verificationsByKey[targetKey] ?: AiVerificationState.Idle

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "SELECT SIGNAL TO VERIFY & AUDIT RISK",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        verifiableSignals.forEach { sig ->
                            val sKey = AiHubViewModel.signalKeyFor(sig)
                            FilterChip(
                                selected = sKey == targetKey,
                                onClick = { selectedVerifySignalKey = sKey },
                                label = {
                                    Text(
                                        text = "${sig.instrument} ${sig.direction.uppercase()} (${sig.timeframe})",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MeridianTealTint,
                                    selectedLabelColor = MeridianTeal
                                )
                            )
                        }
                    }
                }
            }

            MarketAnalyticsCard(
                analytics = targetAnalytics,
                decimals = targetDecimals
            )

            AiVerificationScorecard(
                verificationState = targetVerification,
                onVerifyClick = {
                    aiViewModel.verifySignalAsTrader(targetSignal, targetAnalytics, deskContext)
                }
            )
        }
    }

    val webPortalsBlock: @Composable () -> Unit = {
        WebAiPortalsStrip(
            onOpenPortal = { portal ->
                val promptToCopy = buildString {
                    appendLine("Analyze my Shehzad Algo quantitative trading desk snapshot (explain risks, R/R, costs, and regime — no financial advice):")
                    appendLine()
                    append(deskContext)
                }
                copyText(context, "Shehzad Algo Desk Context", promptToCopy)
                onShowMessage("Desk context copied — paste directly in ${portal.name}.")
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(portal.url))
                    context.startActivity(intent)
                }.onFailure {
                    onShowMessage("Could not open browser for ${portal.name}.")
                }
            }
        )
    }

    val errorBannerBlock: @Composable () -> Unit = {
        Card(
            colors = CardDefaults.cardColors(containerColor = BearCrimsonTint),
            border = BorderStroke(1.dp, BearCrimson),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = errorBanner ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { aiViewModel.selectProvider(AiProviderType.FIREBASE_GEMINI) },
                        border = BorderStroke(1.dp, MeridianTeal),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Use Free Gemini", color = MeridianTeal, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { showConfigSheet = true },
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Setup Key", color = InkPrimary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    val composerBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(GraphiteSurface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = if (needsKeyNow) {
                            "Add ${activeProvider.displayName} key or switch to Free AI..."
                        } else {
                            "Ask ${activeProvider.displayName} about signals, risk, or code..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                },
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeridianTeal,
                    unfocusedBorderColor = HairlineBorder,
                    focusedTextColor = InkPrimary,
                    unfocusedTextColor = InkPrimary
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ai_prompt_input")
            )

            IconButton(
                onClick = {
                    val prompt = inputText.trim()
                    if (prompt.isNotEmpty()) {
                        inputText = ""
                        aiViewModel.sendMessage(prompt, deskContext)
                    }
                },
                enabled = !isGenerating && inputText.isNotBlank(),
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (!isGenerating && inputText.isNotBlank()) MeridianTeal else SlateCard,
                        RoundedCornerShape(10.dp)
                    )
                    .testTag("ai_send_button")
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MeridianTeal,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send prompt",
                        tint = if (inputText.isNotBlank()) ObsidianBg else InkSecondary
                    )
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        val isWideTablet = maxWidth >= 720.dp
        val useDualPane = isTabletMode || isWideTablet

        when {
            isWideTablet -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("tablet_dual_pane_ai_hub")
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { headerBlock() }
                        if (signals.isNotEmpty()) {
                            item { signalVerifierBlock() }
                        }
                    }
                    VerticalDivider(color = HairlineBorder)
                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (showWebPortals) {
                                item { webPortalsBlock() }
                            }
                            item {
                                QuickPromptRow(
                                    enabled = !isGenerating,
                                    onPromptClick = { prompt ->
                                        aiViewModel.sendMessage(prompt, deskContext)
                                    }
                                )
                            }
                            if (errorBanner != null) {
                                item { errorBannerBlock() }
                            }
                            if (messages.isEmpty()) {
                                item {
                                    EmptyAiCard(
                                        activeProvider = activeProvider,
                                        needsKey = needsKeyNow,
                                        onOpenConfig = { showConfigSheet = true },
                                        onSamplePrompt = { aiViewModel.sendMessage(it, deskContext) }
                                    )
                                }
                            } else {
                                items(messages, key = { it.id }) { msg ->
                                    ChatMessageBubble(
                                        message = msg,
                                        onCopy = {
                                            copyText(context, "Shehzad Algo AI", msg.content)
                                            onShowMessage("AI response copied to clipboard.")
                                        }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = HairlineBorder)
                        composerBar()
                    }
                }
            }

            useDualPane -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("tablet_dual_pane_ai_hub")
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item { headerBlock() }
                        if (signals.isNotEmpty()) {
                            item { signalVerifierBlock() }
                        }
                    }
                    HorizontalDivider(color = HairlineBorder, thickness = 1.dp)
                    Column(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (showWebPortals) {
                                item { webPortalsBlock() }
                            }
                            item {
                                QuickPromptRow(
                                    enabled = !isGenerating,
                                    onPromptClick = { prompt ->
                                        aiViewModel.sendMessage(prompt, deskContext)
                                    }
                                )
                            }
                            if (errorBanner != null) {
                                item { errorBannerBlock() }
                            }
                            if (messages.isEmpty()) {
                                item {
                                    EmptyAiCard(
                                        activeProvider = activeProvider,
                                        needsKey = needsKeyNow,
                                        onOpenConfig = { showConfigSheet = true },
                                        onSamplePrompt = { aiViewModel.sendMessage(it, deskContext) }
                                    )
                                }
                            } else {
                                items(messages, key = { it.id }) { msg ->
                                    ChatMessageBubble(
                                        message = msg,
                                        onCopy = {
                                            copyText(context, "Shehzad Algo AI", msg.content)
                                            onShowMessage("AI response copied to clipboard.")
                                        }
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = HairlineBorder)
                        composerBar()
                    }
                }
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { headerBlock() }
                        if (signals.isNotEmpty()) {
                            item { signalVerifierBlock() }
                        }
                        if (showWebPortals) {
                            item { webPortalsBlock() }
                        }
                        item {
                            QuickPromptRow(
                                enabled = !isGenerating,
                                onPromptClick = { prompt ->
                                    aiViewModel.sendMessage(prompt, deskContext)
                                }
                            )
                        }
                        if (errorBanner != null) {
                            item { errorBannerBlock() }
                        }
                        if (messages.isEmpty()) {
                            item {
                                EmptyAiCard(
                                    activeProvider = activeProvider,
                                    needsKey = needsKeyNow,
                                    onOpenConfig = { showConfigSheet = true },
                                    onSamplePrompt = { aiViewModel.sendMessage(it, deskContext) }
                                )
                            }
                        } else {
                            items(messages, key = { it.id }) { msg ->
                                ChatMessageBubble(
                                    message = msg,
                                    onCopy = {
                                        copyText(context, "Shehzad Algo AI", msg.content)
                                        onShowMessage("AI response copied to clipboard.")
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = HairlineBorder)
                    composerBar()
                }
            }
        }
    }

    if (showConfigSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showConfigSheet = false },
            sheetState = sheetState,
            containerColor = GraphiteSurface
        ) {
            ProviderConfigSheet(
                settings = settings,
                onSave = { provider, model, apiKey, customUrl ->
                    aiViewModel.saveProviderConfig(provider, model, apiKey, customUrl)
                    showConfigSheet = false
                    onShowMessage("${provider.displayName} configuration saved.")
                }
            )
        }
    }
}

@Composable
private fun AiEngineHeaderCard(
    settings: AiHubSettings,
    activeProvider: AiProviderType,
    activeModel: String,
    needsKeyNow: Boolean,
    showWebPortals: Boolean,
    onToggleWebPortals: () -> Unit,
    onSelectProvider: (AiProviderType) -> Unit,
    onOpenConfig: () -> Unit,
    onToggleContext: (Boolean) -> Unit,
    onClearChat: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, if (needsKeyNow) WarnAmber else HairlineBorder),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MeridianTeal,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "SHEHZAD ALGO MULTI-AI HUB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MeridianTeal
                        )
                    }
                    Text(
                        text = "${activeProvider.displayName} • $activeModel",
                        style = MonoMetricSmall,
                        color = InkPrimary
                    )
                }

                StatusPill(
                    text = activeProvider.badge,
                    bg = if (activeProvider.isFreeNoKey) BullEmeraldTint else InfoBlueTint,
                    fg = if (activeProvider.isFreeNoKey) BullEmerald else InfoBlue
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiProviderType.entries.forEach { provider ->
                    val selected = provider == activeProvider
                    val hasSavedKey = !provider.requiresApiKey || settings.apiKey(provider).isNotBlank()
                    val label = if (provider.isFreeNoKey) {
                        "${provider.displayName} ✓"
                    } else if (hasSavedKey && provider.requiresApiKey) {
                        "${provider.displayName} • Keyed"
                    } else {
                        provider.displayName
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectProvider(provider) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MeridianTealTint,
                            selectedLabelColor = MeridianTeal
                        ),
                        modifier = Modifier.testTag("provider_chip_${provider.name.lowercase()}")
                    )
                }
            }

            if (needsKeyNow) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarnAmberTint),
                    border = BorderStroke(1.dp, WarnAmber),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${activeProvider.displayName} needs an API key. Add one via Keys & Models, or select Gemini Built-in / Web Free AI for free zero-key access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkPrimary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onOpenConfig,
                    border = BorderStroke(1.dp, MeridianTeal),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("open_ai_config_button")
                ) {
                    Icon(Icons.Outlined.Key, contentDescription = null, tint = MeridianTeal, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Keys & Models", color = MeridianTeal, style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onToggleWebPortals,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("toggle_web_portals_button")
                ) {
                    Icon(Icons.Outlined.Public, contentDescription = null, tint = InkPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (showWebPortals) "Hide Web AI" else "Web Free AI", color = InkPrimary, style = MaterialTheme.typography.labelMedium)
                }

                IconButton(
                    onClick = onClearChat,
                    modifier = Modifier.size(48.dp).testTag("clear_ai_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Clear AI chat",
                        tint = InkSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Attach Live Desk Context", style = MaterialTheme.typography.bodyMedium, color = InkPrimary)
                    Text("Shares active signals, risk limits & ledger stats (read-only)", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                }
                Switch(
                    checked = settings.includeDeskContext,
                    onCheckedChange = onToggleContext,
                    colors = SwitchDefaults.colors(checkedThumbColor = ObsidianBg, checkedTrackColor = MeridianTeal)
                )
            }
        }
    }
}

@Composable
private fun WebAiPortalsStrip(
    onOpenPortal: (WebAiPortal) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "FREE WEB-BASED AI PORTALS (NO API KEY NEEDED)",
                style = MaterialTheme.typography.labelMedium,
                color = BullEmerald
            )
            Text(
                text = "Tap any web AI below to copy your live Shehzad Algo desk snapshot and open the free browser workspace:",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WebAiPortals.all.forEach { portal ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateCard),
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier
                            .width(164.dp)
                            .clickable { onOpenPortal(portal) }
                            .testTag("web_portal_${portal.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(portal.name, style = MaterialTheme.typography.labelLarge, color = InkPrimary)
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "Open ${portal.name}",
                                    tint = MeridianTeal,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(portal.subtitle, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickPromptRow(
    enabled: Boolean,
    onPromptClick: (String) -> Unit
) {
    val prompts = remember {
        listOf(
            "Analyze Open Signals" to "Review all open signals in my desk context. Check stop-loss distance, cost in R, regime fit, and key invalidation risks.",
            "Risk & Drawdown Audit" to "Audit my current risk configuration, currency/crypto concentration, and paper drawdown limits. Flag any weaknesses.",
            "Strategy Gate Check" to "Review the latest Strategy Validation Gates in my desk context and explain whether Sharpe, PF, and Causality support paper vs live use.",
            "Urdu/Roman Brief" to "Meri current Shehzad Algo desk summary ko simple Roman Urdu mein explain kro: open signals, risk, aur ledger performance."
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEach { (label, prompt) ->
            OutlinedButton(
                onClick = { onPromptClick(prompt) },
                enabled = enabled,
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.height(40.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MeridianTeal)
            }
        }
    }
}

@Composable
private fun EmptyAiCard(
    activeProvider: AiProviderType,
    needsKey: Boolean,
    onOpenConfig: () -> Unit,
    onSamplePrompt: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Quantitative & All-AI Research Desk",
                style = MaterialTheme.typography.titleMedium,
                color = InkPrimary
            )
            Text(
                text = "• No API Key? Use Gemini Built-in (Free), Web Free AI (Pollinations), or tap any Web AI Portal above.\n" +
                    "• Have API Keys? Connect OpenAI, Claude, DeepSeek, Groq, OpenRouter, xAI Grok, Mistral, or your own local Ollama endpoint.\n" +
                    "• AI explains signals, risk, and validation honestly — it never overrides the Risk Engine or fabricates trades.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
            if (needsKey) {
                Button(
                    onClick = onOpenConfig,
                    colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Configure ${activeProvider.displayName}")
                }
            } else {
                Button(
                    onClick = {
                        onSamplePrompt("Give me a concise quantitative health check of my Shehzad Algo signals, risk limits, and validation gates.")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Run AI Desk Health Check")
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: AiChatMessage,
    onCopy: () -> Unit
) {
    val isUser = message.role == ChatRole.USER
    val bgColor = when {
        message.isError -> BearCrimsonTint
        isUser -> MeridianTealTint
        else -> SlateCard
    }
    val borderColor = when {
        message.isError -> BearCrimson
        isUser -> MeridianTeal
        else -> HairlineBorder
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUser) {
                        "YOU • ${message.timestampUtc}"
                    } else {
                        "${message.providerLabel} (${message.modelLabel}) • ${message.timestampUtc}"
                    },
                    style = MonoMetricSmall,
                    color = if (message.isError) BearCrimson else if (isUser) MeridianTeal else InkSecondary
                )

                if (!isUser && !message.isStreaming && message.content.isNotBlank()) {
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy response",
                            tint = InkSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (message.isStreaming && message.content.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MeridianTeal,
                        strokeWidth = 2.dp
                    )
                    Text("Streaming analysis...", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                }
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkPrimary
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigSheet(
    settings: AiHubSettings,
    onSave: (AiProviderType, String, String, String?) -> Unit
) {
    var editingProvider by remember { mutableStateOf(settings.selectedProvider) }
    var modelText by remember(editingProvider) { mutableStateOf(settings.activeModel(editingProvider)) }
    var keyText by remember(editingProvider) { mutableStateOf(settings.apiKey(editingProvider)) }
    var customUrlText by remember { mutableStateOf(settings.customBaseUrl) }
    var revealKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = MeridianTeal)
            Text("ALL AI PROVIDERS & KEYS", style = MaterialTheme.typography.titleLarge, color = InkPrimary)
        }

        Text(
            text = "Select any provider. Built-in Gemini and Web Free AI require zero API keys. Optional API keys are stored strictly on-device.",
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AiProviderType.entries.forEach { p ->
                FilterChip(
                    selected = p == editingProvider,
                    onClick = { editingProvider = p },
                    label = { Text(p.displayName, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MeridianTealTint,
                        selectedLabelColor = MeridianTeal
                    )
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(editingProvider.displayName, style = MaterialTheme.typography.titleMedium, color = InkPrimary)
                Text(editingProvider.description, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                Text("Endpoint: ${editingProvider.defaultEndpoint}", style = MonoMetricSmall, color = MeridianTeal)
            }
        }

        Text("SUGGESTED MODELS", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            editingProvider.suggestedModels.forEach { m ->
                FilterChip(
                    selected = modelText == m,
                    onClick = { modelText = m },
                    label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MeridianTealTint,
                        selectedLabelColor = MeridianTeal
                    )
                )
            }
        }

        OutlinedTextField(
            value = modelText,
            onValueChange = { modelText = it },
            label = { Text("Model ID") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MeridianTeal,
                unfocusedBorderColor = HairlineBorder,
                focusedTextColor = InkPrimary,
                unfocusedTextColor = InkPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (editingProvider == AiProviderType.CUSTOM_OPENAI) {
            OutlinedTextField(
                value = customUrlText,
                onValueChange = { customUrlText = it },
                label = { Text("OpenAI-Compatible Chat Completions URL") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeridianTeal,
                    unfocusedBorderColor = HairlineBorder,
                    focusedTextColor = InkPrimary,
                    unfocusedTextColor = InkPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (editingProvider != AiProviderType.FIREBASE_GEMINI && editingProvider != AiProviderType.POLLINATIONS_FREE) {
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = {
                    Text(
                        if (editingProvider.requiresApiKey) {
                            "${editingProvider.displayName} API Key"
                        } else {
                            "Optional Bearer Key"
                        }
                    )
                },
                singleLine = true,
                visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealKey = !revealKey }) {
                        Icon(
                            imageVector = if (revealKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = "Toggle key visibility",
                            tint = InkSecondary
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeridianTeal,
                    unfocusedBorderColor = HairlineBorder,
                    focusedTextColor = InkPrimary,
                    unfocusedTextColor = InkPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider_api_key_input")
            )
        }

        Button(
            onClick = {
                onSave(
                    editingProvider,
                    modelText.trim().ifBlank { editingProvider.defaultModel },
                    keyText,
                    if (editingProvider == AiProviderType.CUSTOM_OPENAI) customUrlText else null
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_ai_config_button")
        ) {
            Text("Save & Activate ${editingProvider.displayName}", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
