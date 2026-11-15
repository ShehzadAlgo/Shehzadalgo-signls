package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.VerifiedUser
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.firestore.SignalEventRecord
import com.example.data.firestore.SignalRecord
import com.example.domain.model.ExecutionMode
import com.example.domain.model.SignalStatus
import com.example.quant.HashChainUtils
import com.example.quant.TelegramFormatter
import com.example.ui.components.EquityCurveChart
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
import com.example.ui.viewmodel.ChainVerificationResult
import com.example.ui.viewmodel.MeridianViewModel
import com.example.ui.viewmodel.UiState
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun LedgerScreen(
    signals: List<SignalRecord>,
    eventsState: UiState<List<SignalEventRecord>>,
    chainVerification: ChainVerificationResult?,
    viewModel: MeridianViewModel,
    isTabletMode: Boolean = false,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedMode by remember { mutableStateOf(ExecutionMode.PAPER) }
    val stats = remember(signals, selectedMode) {
        viewModel.computeLedgerStats(signals, selectedMode)
    }
    val events = (eventsState as? UiState.Success)?.data.orEmpty()

    val regimeBreakdown = remember(signals, selectedMode) {
        signals.filter {
            it.executionMode.equals(selectedMode.wire, ignoreCase = true) &&
                SignalStatus.fromWire(it.status).isTerminal &&
                it.status != SignalStatus.EXPIRED.wire &&
                it.status != SignalStatus.CANCELLED.wire
        }.groupBy { it.regime }.mapValues { (_, list) ->
            list.size to list.sumOf { it.realizedR }
        }
    }

    val performanceCard: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "VERIFIABLE TRACK RECORD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MeridianTeal
                )
                Text(
                    text = "Paper, Live, and Backtest results are strictly segregated and reported net of costs in R.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ExecutionMode.PAPER, ExecutionMode.LIVE).forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { selectedMode = mode },
                            label = { Text(mode.wire, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MeridianTealTint,
                                selectedLabelColor = MeridianTeal
                            )
                        )
                    }
                }
            }

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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LedgerKpi("NET R (${selectedMode.wire})", "${"%.2f".format(stats.netR)}R", if (stats.netR >= 0) BullEmerald else BearCrimson)
                        LedgerKpi("EXPECTANCY", "${"%.2f".format(stats.expectancyR)}R", if (stats.expectancyR >= 0.1) BullEmerald else InkPrimary)
                        LedgerKpi("WIN RATE", "${"%.0f".format(stats.winRatePct)}%", InkPrimary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LedgerKpi("PROFIT FACTOR", "${"%.2f".format(stats.profitFactor)}", InkPrimary)
                        LedgerKpi("MAX DD", "${"%.2f".format(stats.maxDrawdownR)}R", BearCrimson)
                        LedgerKpi("SAMPLE", "${stats.closedTrades} closed / ${stats.openSignals} open", InkSecondary)
                    }

                    EquityCurveChart(
                        equityCurveR = stats.equityCurveR,
                        drawdownCurveR = stats.drawdownCurveR
                    )
                }
            }

            if (regimeBreakdown.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("PERFORMANCE BY REGIME", style = MaterialTheme.typography.labelMedium, color = MeridianTeal)
                        regimeBreakdown.forEach { (regime, pair) ->
                            val (count, netR) = pair
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("$regime ($count trades)", style = MaterialTheme.typography.bodySmall, color = InkPrimary)
                                Text(
                                    "${"%.2f".format(netR)}R",
                                    style = MonoMetricSmall,
                                    color = if (netR >= 0) BullEmerald else BearCrimson
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val head = events.lastOrNull()?.eventHash ?: HashChainUtils.GENESIS_HASH
    val integrityCard: @Composable () -> Unit = {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(
                1.dp,
                when (chainVerification?.valid) {
                    true -> BullEmerald
                    false -> BearCrimson
                    null -> HairlineBorder
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "SHA-256 HASH-CHAIN INTEGRITY (${events.size} APPEND-ONLY EVENTS)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MeridianTeal
                )
                Text(
                    text = "Head: ${head.take(24)}...",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )

                chainVerification?.let { res ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (res.valid) Icons.Outlined.VerifiedUser else Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = if (res.valid) BullEmerald else BearCrimson
                        )
                        Text(
                            text = res.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (res.valid) BullEmerald else BearCrimson
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.verifyHashChain() },
                        colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("verify_chain_button")
                    ) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.action_verify_chain), style = MaterialTheme.typography.labelMedium)
                    }

                    OutlinedButton(
                        onClick = {
                            val digest = TelegramFormatter.formatDailyDigest(
                                latestHash = head,
                                eventCount = events.size,
                                dateUtc = LocalDate.now(ZoneOffset.UTC).toString(),
                                netPaperR = stats.netR
                            )
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Shehzad Algo Digest", digest))
                            onShowMessage("Daily SHA-256 verification digest copied.")
                        },
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = InkPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Digest", color = InkPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useHorizontalSplit = maxWidth >= 720.dp
        val useStackedSplit = isTabletMode && !useHorizontalSplit

        if (useHorizontalSplit || useStackedSplit) {
            val leftPane: @Composable (Modifier) -> Unit = { mod ->
                LazyColumn(
                    modifier = mod,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { performanceCard() }
                }
            }

            val rightPane: @Composable (Modifier) -> Unit = { mod ->
                LazyColumn(
                    modifier = mod,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { integrityCard() }
                    items(events.reversed(), key = { it.id.ifBlank { "${it.sequenceIndex}_${it.eventHash}" } }) { ev ->
                        LedgerEventCard(ev)
                    }
                }
            }

            if (useHorizontalSplit) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("tablet_dual_pane_ledger")
                ) {
                    leftPane(Modifier.weight(0.50f).fillMaxHeight())
                    rightPane(Modifier.weight(0.50f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("tablet_dual_pane_ledger")
                ) {
                    leftPane(Modifier.fillMaxWidth().weight(0.49f))
                    HorizontalDivider(thickness = 1.dp, color = MeridianTeal.copy(alpha = 0.45f))
                    rightPane(Modifier.fillMaxWidth().weight(0.51f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { performanceCard() }
                item { integrityCard() }
                items(events.reversed(), key = { it.id.ifBlank { "${it.sequenceIndex}_${it.eventHash}" } }) { ev ->
                    LedgerEventCard(ev)
                }
            }
        }
    }
}

@Composable
private fun LedgerEventCard(ev: SignalEventRecord) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "#${ev.sequenceIndex} • ${ev.eventType}",
                    style = MonoMetricMedium,
                    color = MeridianTeal
                )
                Text(
                    text = ev.timestampIso.take(19) + "Z",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }
            Text(
                text = ev.payloadSummary,
                style = MaterialTheme.typography.bodySmall,
                color = InkPrimary
            )
            Text(
                text = "prev:${ev.prevHash.take(10)}.. -> hash:${ev.eventHash.take(14)}..",
                style = MonoMetricSmall,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun LedgerKpi(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Text(text = value, style = MonoMetricMedium, color = color)
    }
}
