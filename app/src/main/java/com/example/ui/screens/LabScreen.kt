package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.firestore.ValidationReportRecord
import com.example.domain.model.Timeframe
import com.example.quant.BacktestValidationResult
import com.example.quant.Strategy
import com.example.quant.StrategyRegistry
import com.example.ui.components.EquityCurveChart
import com.example.ui.components.SensitivityBarChart
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

@Composable
fun LabScreen(
    storedReports: List<ValidationReportRecord>,
    liveDetails: Map<String, BacktestValidationResult>,
    validatingStrategyId: String?,
    onValidateStrategy: (String) -> Unit,
    onImportCsv: (String, Timeframe, Uri) -> Unit
) {
    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportCsv("BTCUSDT", Timeframe.H4, uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
                        text = "STRATEGY VALIDATION LAB (HONESTY GATES)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                    Text(
                        text = "Every strategy runs cost-aware OOS simulation, 500x Monte Carlo (10% skip, 2x cost), +/-20% parameter stability, 95th-percentile random baseline, and the mandatory Causality Gate. Failing strategies are marked REJECTED.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                    OutlinedButton(
                        onClick = { csvLauncher.launch(arrayOf("text/*", "text/csv", "application/octet-stream")) },
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = InkPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import Historical CSV (BTCUSDT 4H)", color = InkPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        items(StrategyRegistry.mvpStrategies, key = { it.id }) { strategy ->
            val stored = storedReports.firstOrNull { it.strategyId == strategy.id }
            val live = liveDetails[strategy.id]
            StrategyValidationCard(
                strategy = strategy,
                stored = stored,
                live = live,
                isValidating = validatingStrategyId == strategy.id,
                anyValidating = validatingStrategyId != null,
                onValidateClick = { onValidateStrategy(strategy.id) }
            )
        }
    }
}

@Composable
private fun StrategyValidationCard(
    strategy: Strategy,
    stored: ValidationReportRecord?,
    live: BacktestValidationResult?,
    isValidating: Boolean,
    anyValidating: Boolean,
    onValidateClick: () -> Unit
) {
    val lifecycle = live?.lifecycleStatus?.wire ?: stored?.lifecycleStatus ?: "DRAFT"
    val (badgeBg, badgeFg) = when (lifecycle) {
        "PAPER", "LIVE" -> BullEmeraldTint to BullEmerald
        "REJECTED", "SUSPENDED" -> BearCrimsonTint to BearCrimson
        "BACKTESTED" -> WarnAmberTint to WarnAmber
        else -> GraphiteSurface to InkSecondary
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(strategy.displayName, style = MaterialTheme.typography.titleLarge, color = InkPrimary)
                    Text(
                        text = "${strategy.id} v${strategy.version} • cfg ${strategy.configHash}",
                        style = MonoMetricSmall,
                        color = InkSecondary
                    )
                }
                StatusPill(text = lifecycle, bg = badgeBg, fg = badgeFg)
            }

            Text(
                text = "Hypothesis: ${strategy.hypothesis}",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )

            Text(
                text = "Timeframes: ${strategy.supportedTimeframes.joinToString { it.wire }} | Regimes: ${strategy.regimesAllowed.joinToString { it.name }}",
                style = MonoMetricSmall,
                color = MeridianTeal
            )

            val oosTrades = live?.oosTrades ?: stored?.oosTrades?.toInt()
            val expectancy = live?.netExpectancyR ?: stored?.netExpectancyR
            val pf = live?.profitFactor ?: stored?.profitFactor
            val sharpe = live?.sharpe ?: stored?.sharpe
            val dsr = live?.deflatedSharpe ?: stored?.deflatedSharpe
            val mc95 = live?.mc95DrawdownPct ?: stored?.mc95DrawdownPct
            val causality = live?.causalityPassed ?: stored?.causalityPassed
            val failures = live?.failureReasons ?: stored?.failureReasons.orEmpty()
            val curve = live?.equityCurveR ?: stored?.equityCurveR.orEmpty()

            if (oosTrades != null && expectancy != null && pf != null && sharpe != null && mc95 != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LabStat("OOS TRADES", oosTrades.toString())
                        LabStat("EXPECTANCY", "${"%.2f".format(expectancy)}R")
                        LabStat("PROFIT FACTOR", "${"%.2f".format(pf)}")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        LabStat("OOS SHARPE", "${"%.2f".format(sharpe)}")
                        LabStat("DEFLATED SR", "${"%.2f".format(dsr ?: 0.0)}")
                        LabStat("MC 95% DD", "${"%.1f".format(mc95)}%")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (causality == true) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = if (causality == true) BullEmerald else BearCrimson,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (causality == true) "Causality Gate: PASS (0 look-ahead leaks)" else "Causality Gate: FAILED",
                            style = MonoMetricSmall,
                            color = if (causality == true) BullEmerald else BearCrimson
                        )
                    }

                    if (curve.size >= 2) {
                        EquityCurveChart(equityCurveR = curve)
                    }

                    live?.sensitivityNeighbors?.let { neighbors ->
                        SensitivityBarChart(neighbors = neighbors)
                    }

                    if (failures.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BearCrimsonTint),
                            border = BorderStroke(1.dp, BearCrimson),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("VALIDATION GATES FAILED (REJECTED)", style = MaterialTheme.typography.labelSmall, color = BearCrimson)
                                failures.forEach { reason ->
                                    Text("• $reason", style = MaterialTheme.typography.bodySmall, color = InkPrimary)
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onValidateClick,
                enabled = !anyValidating,
                colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("validate_${strategy.id}")
            ) {
                if (isValidating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = ObsidianBg, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Running OOS + Monte Carlo...", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_run_validation), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun LabStat(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Text(text = value, style = MonoMetricMedium, color = InkPrimary)
    }
}
