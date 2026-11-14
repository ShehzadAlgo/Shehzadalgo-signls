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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.firestore.SignalRecord
import com.example.domain.model.DataQualityIssue
import com.example.domain.model.Direction
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.RiskConfig
import com.example.domain.model.SignalStatus
import com.example.quant.OpenExposureItem
import com.example.quant.RiskEngine
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BearCrimsonTint
import com.example.ui.theme.BullEmerald
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

@Composable
fun RiskScreen(
    riskConfig: RiskConfig,
    signals: List<SignalRecord>,
    spotRates: Map<String, Double>,
    qualityIssues: List<DataQualityIssue>,
    viewModel: MeridianViewModel
) {
    var selectedSymbol by remember { mutableStateOf("EURUSD") }
    val selectedIns = InstrumentUniverse.find(selectedSymbol) ?: InstrumentUniverse.all.first()
    val defaultEntry = remember(selectedSymbol, spotRates) {
        when (selectedSymbol) {
            "BTCUSDT" -> "90000.0"
            "ETHUSDT" -> "3100.0"
            "SOLUSDT" -> "205.0"
            "XAUUSD" -> "2650.0"
            else -> "%.4f".format(spotRates[selectedSymbol] ?: 1.0850)
        }
    }
    var balanceText by remember(riskConfig.accountBalanceUsd) { mutableStateOf(riskConfig.accountBalanceUsd.toInt().toString()) }
    var riskPctText by remember(riskConfig.riskPerTradePct) { mutableStateOf(riskConfig.riskPerTradePct.toString()) }
    var entryText by remember(selectedSymbol) { mutableStateOf(defaultEntry) }
    var stopText by remember(selectedSymbol) {
        val e = defaultEntry.toDoubleOrNull() ?: 1.0850
        val sl = if (selectedIns.quoteCurrency == "JPY") e - 0.45 else if (selectedSymbol == "BTCUSDT") e * 0.98 else e - selectedIns.pipSize * 30
        mutableStateOf("%.4f".format(sl))
    }

    var editBalance by remember(riskConfig) { mutableStateOf(riskConfig.accountBalanceUsd.toString()) }
    var editRiskPct by remember(riskConfig) { mutableStateOf(riskConfig.riskPerTradePct.toString()) }
    var editMaxOpen by remember(riskConfig) { mutableStateOf(riskConfig.maxOpenRiskPct.toString()) }
    var editMaxCluster by remember(riskConfig) { mutableStateOf(riskConfig.maxClusterRiskPct.toString()) }

    val sizing = remember(selectedIns, balanceText, riskPctText, entryText, stopText, spotRates) {
        viewModel.computeSizingPreview(
            instrument = selectedIns,
            entryPrice = entryText.toDoubleOrNull() ?: 0.0,
            stopLoss = stopText.toDoubleOrNull() ?: 0.0,
            customBalanceUsd = balanceText.toDoubleOrNull() ?: 10_000.0,
            customRiskPct = riskPctText.toDoubleOrNull() ?: 0.5
        )
    }

    val openItems = remember(signals) {
        signals.filter { SignalStatus.fromWire(it.status).isOpen }.mapNotNull { rec ->
            val ins = InstrumentUniverse.find(rec.instrument) ?: return@mapNotNull null
            OpenExposureItem(ins, Direction.fromWire(rec.direction), rec.suggestedRiskPct)
        }
    }
    val fxNetMap = remember(openItems) { RiskEngine.computeCurrencyNetExposurePct(openItems) }
    val (btcBetaNet, cryptoClusters) = remember(openItems) { RiskEngine.computeCryptoClusterAndBetaExposure(openItems, emptyMap()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                            viewModel.saveRiskConfig(
                                riskConfig.copy(manualKillSwitch = checked),
                                killSwitchToggled = true
                            )
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
                                )
                            )
                        }
                    }

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
                                Text(
                                    text = "Stop: ${"%.1f".format(sizing.stopPipsOrPoints)} ${if (selectedIns.assetClass.wire == "fx") "pips" else "pts"} | Notional: $${"%.0f".format(sizing.notionalUsd)}",
                                    style = MonoMetricSmall,
                                    color = InkSecondary
                                )
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
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, HairlineBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "NET PORTFOLIO EXPOSURE (${openItems.size} OPEN)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                    Text(
                        text = "Total Open Risk: ${"%.2f".format(openItems.sumOf { it.riskPct })}% / ${riskConfig.maxOpenRiskPct}% cap",
                        style = MonoMetricMedium,
                        color = InkPrimary
                    )
                    if (fxNetMap.isNotEmpty()) {
                        Text("FX Currency Legs (cap ${riskConfig.maxClusterRiskPct}%):", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
                        fxNetMap.forEach { (ccy, pct) ->
                            val over = abs(pct) > riskConfig.maxClusterRiskPct
                            Text(
                                text = "$ccy: ${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%",
                                style = MonoMetricSmall,
                                color = if (over) BearCrimson else InkPrimary
                            )
                        }
                    }
                    Text(
                        text = "Crypto Net BTC-Beta Risk: ${"%.2f".format(btcBetaNet)}% | Clusters: ${if (cryptoClusters.isEmpty()) "0" else cryptoClusters.entries.joinToString { "${it.key}=${"%.2f".format(it.value)}%" }}",
                        style = MonoMetricSmall,
                        color = InkSecondary
                    )
                }
            }
        }

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
                            viewModel.saveRiskConfig(updated)
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
