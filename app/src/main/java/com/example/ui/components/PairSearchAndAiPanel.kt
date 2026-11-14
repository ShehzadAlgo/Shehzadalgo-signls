package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.data.ai.AiPairAnalysisResult
import com.example.data.ai.PairAiAnalysisState
import com.example.data.ai.PairBias
import com.example.domain.model.Candle
import com.example.domain.model.Timeframe
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
import com.example.ui.theme.MonoMetricMedium
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateCard
import com.example.ui.theme.WarnAmber
import com.example.ui.theme.WarnAmberTint

private val QUICK_SEARCH_PAIRS = listOf(
    "EURUSD", "GBPUSD", "XAUUSD", "BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT", "EURGBP", "USDJPY"
)

@Composable
fun PairSearchAndAiPanel(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    pairAnalysisState: PairAiAnalysisState,
    onAnalyzePairWithAi: (String, Timeframe) -> Unit,
    onClearPairAnalysis: () -> Unit,
    onOpenFullAiChat: (AiPairAnalysisResult) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTimeframe by remember { mutableStateOf(Timeframe.H1) }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("pair_search_bar_card")
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MeridianTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "ANY-PAIR AI SEARCH & ANALYZER",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                }
                Text(
                    text = "FX • Metals • Crypto",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { onSearchQueryChange(it.uppercase()) },
                placeholder = {
                    Text("Search any pair (e.g. XAUUSD, DOGE, EURGBP, BTCUSDT)...")
                },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = "Search pair", tint = MeridianTeal)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                onSearchQueryChange("")
                                onClearPairAnalysis()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("clear_pair_search_button")
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = InkSecondary)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (searchQuery.isNotBlank()) {
                            onAnalyzePairWithAi(searchQuery, selectedTimeframe)
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pair_search_input")
            )

            // Quick timeframe & popular ticker strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Timeframe.entries.forEach { tf ->
                    FilterChip(
                        selected = selectedTimeframe == tf,
                        onClick = { selectedTimeframe = tf },
                        label = { Text(tf.wire, style = MonoMetricSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WarnAmberTint,
                            selectedLabelColor = WarnAmber
                        ),
                        modifier = Modifier.testTag("search_tf_${tf.wire}")
                    )
                }
                QUICK_SEARCH_PAIRS.forEach { sym ->
                    FilterChip(
                        selected = searchQuery.trim().equals(sym, ignoreCase = true),
                        onClick = {
                            onSearchQueryChange(sym)
                            onAnalyzePairWithAi(sym, selectedTimeframe)
                        },
                        label = { Text(sym, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MeridianTealTint,
                            selectedLabelColor = MeridianTeal
                        ),
                        modifier = Modifier.testTag("quick_pair_$sym")
                    )
                }
            }

            Button(
                onClick = {
                    val target = searchQuery.ifBlank { "EURUSD" }
                    if (searchQuery.isBlank()) onSearchQueryChange(target)
                    onAnalyzePairWithAi(target, selectedTimeframe)
                },
                enabled = pairAnalysisState !is PairAiAnalysisState.Analyzing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MeridianTeal,
                    contentColor = ObsidianBg
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("analyze_searched_pair_ai_button")
            ) {
                if (pairAnalysisState is PairAiAnalysisState.Analyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = ObsidianBg,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Analyzing ${pairAnalysisState.symbol}...",
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    val targetLabel = searchQuery.trim().ifBlank { "Pair" }
                    Text(
                        text = "Analyze $targetLabel with AI (${selectedTimeframe.wire})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            when (pairAnalysisState) {
                is PairAiAnalysisState.Idle -> Unit
                is PairAiAnalysisState.Analyzing -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GraphiteSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = "Fetching live ${pairAnalysisState.timeframe.wire} OHLCV bars for ${pairAnalysisState.symbol} and running Gemini + Risk Engine evaluation...",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                    }
                }
                is PairAiAnalysisState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BearCrimsonTint, RoundedCornerShape(8.dp))
                            .border(1.dp, BearCrimson, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Could not analyze ${pairAnalysisState.symbol}",
                                style = MaterialTheme.typography.labelMedium,
                                color = BearCrimson
                            )
                            Text(
                                text = pairAnalysisState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkPrimary
                            )
                        }
                    }
                }
                is PairAiAnalysisState.Success -> {
                    PairAiAnalysisCard(
                        result = pairAnalysisState.result,
                        candles = pairAnalysisState.candles,
                        onClose = onClearPairAnalysis,
                        onOpenFullAiChat = { onOpenFullAiChat(pairAnalysisState.result) }
                    )
                }
            }
        }
    }
}

@Composable
fun PairAiAnalysisCard(
    result: AiPairAnalysisResult,
    candles: List<Candle>,
    onClose: () -> Unit,
    onOpenFullAiChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (biasBg, biasFg) = when (result.bias) {
        PairBias.BULLISH -> BullEmeraldTint to BullEmerald
        PairBias.BEARISH -> BearCrimsonTint to BearCrimson
        PairBias.NEUTRAL_WAIT -> WarnAmberTint to WarnAmber
    }
    val decimals = when {
        result.currentPrice < 5.0 -> 4
        result.currentPrice < 200.0 -> 3
        else -> 2
    }
    val fmt: (Double) -> String = { "%.${decimals}f".format(it) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RoundedCornerShape(10.dp))
            .border(1.dp, biasFg.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("pair_ai_analysis_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${result.symbol} • ${result.timeframe.wire} AI ANALYSIS",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkPrimary
                )
                Text(
                    text = "${result.feedProvider} (${result.candlesUsedCount} bars) • Spot ${fmt(result.currentPrice)}",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("close_pair_analysis_button")
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close pair analysis", tint = InkSecondary)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${result.bias.displayLabel} (${result.confidenceScore}%)",
                style = MonoMetricSmall,
                color = biasFg,
                modifier = Modifier
                    .background(biasBg, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                text = result.regime,
                style = MonoMetricSmall,
                color = MeridianTeal
            )
        }

        if (candles.isNotEmpty()) {
            SignalCandleChart(
                candles = candles,
                entryLow = result.suggestedEntryLow,
                entryHigh = result.suggestedEntryHigh,
                stopLoss = result.volatilityAdjustedStopLoss,
                tp1Price = result.takeProfit1,
                tp2Price = result.takeProfit2,
                decimals = decimals
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PairStatCol("ENTRY ZONE", "${fmt(result.suggestedEntryLow)} - ${fmt(result.suggestedEntryHigh)}", InkPrimary)
            PairStatCol("VOL SL", fmt(result.volatilityAdjustedStopLoss), BearCrimson)
            PairStatCol("TP1 / TP2", "${fmt(result.takeProfit1)} / ${fmt(result.takeProfit2)}", BullEmerald)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateCard, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "ATR14: ${fmt(result.atr14)} | R:R ${"%.2f".format(result.riskRewardRatio)}",
                style = MonoMetricSmall,
                color = MeridianTeal
            )
            Text(
                text = "RSI ${result.rsi14?.let { "%.1f".format(it) } ?: "-"} • ADX ${result.adx14?.let { "%.1f".format(it) } ?: "-"}",
                style = MonoMetricSmall,
                color = InkPrimary
            )
        }

        Text(
            text = "SIZING & RISK ENGINE: ${result.riskEngineSizingSummary}",
            style = MaterialTheme.typography.bodySmall,
            color = WarnAmber
        )

        Text(
            text = result.technicalThesis,
            style = MaterialTheme.typography.bodySmall,
            color = InkPrimary
        )

        if (result.actionChecklist.isNotEmpty()) {
            HorizontalDivider(color = HairlineBorder)
            Text("EXECUTION CHECKLIST", style = MaterialTheme.typography.labelSmall, color = MeridianTeal)
            result.actionChecklist.forEach { step ->
                Text("• $step", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
            }
        }

        if (result.keyRisks.isNotEmpty()) {
            Text("KEY RISKS", style = MaterialTheme.typography.labelSmall, color = BearCrimson)
            result.keyRisks.forEach { risk ->
                Text("• $risk", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
            }
        }

        Text(
            text = "${result.traderSummary} (${result.modelName}, ${result.analyzedAtUtc})",
            style = MaterialTheme.typography.labelSmall,
            color = InkSecondary
        )

        OutlinedButton(
            onClick = onOpenFullAiChat,
            border = BorderStroke(1.dp, MeridianTeal),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("open_pair_chat_button")
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MeridianTeal, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Continue Deep-Dive in AI Chat", color = MeridianTeal, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PairStatCol(label: String, value: String, color: Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Text(text = value, style = MonoMetricMedium, color = color)
    }
}
