package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.ai.AiVerificationState
import com.example.data.ai.VerificationVerdict
import com.example.quant.MarketAnalyticsSnapshot
import com.example.ui.screens.StatusPill
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
fun MarketAnalyticsCard(
    analytics: MarketAnalyticsSnapshot,
    decimals: Int,
    modifier: Modifier = Modifier
) {
    val fmt: (Double) -> String = { "%.${decimals}f".format(it) }
    val biasColor = when {
        analytics.trendBias.contains("BULLISH") -> BullEmerald
        analytics.trendBias.contains("BEARISH") -> BearCrimson
        else -> WarnAmber
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("market_analytics_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = Icons.Outlined.Analytics,
                        contentDescription = null,
                        tint = MeridianTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "MARKET ANALYTICS (${analytics.barsAnalyzed} BARS)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                }
                Text(
                    text = analytics.trendBias,
                    style = MaterialTheme.typography.labelSmall,
                    color = biasColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                AnalyticsMetric("RSI (14)", analytics.rsi14?.let { "%.1f".format(it) } ?: "N/A")
                AnalyticsMetric("ADX (14)", analytics.adx14?.let { "%.1f".format(it) } ?: "N/A")
                AnalyticsMetric(
                    "STOP / ATR",
                    analytics.stopDistanceAtrMultiple?.let { "%.2f ATR".format(it) } ?: "N/A"
                )
                AnalyticsMetric("TP1 / COST", "${"%.1f".format(analytics.rewardToCostRatio)}x")
            }

            if (analytics.ema20 != null || analytics.ema50 != null || analytics.ema200 != null) {
                Text(
                    text = "EMA20: ${analytics.ema20?.let(fmt) ?: "-"} | EMA50: ${analytics.ema50?.let(fmt) ?: "-"} | EMA200: ${analytics.ema200?.let(fmt) ?: "-"}",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }

            if (analytics.recentSwingLow != null && analytics.recentSwingHigh != null) {
                Text(
                    text = "20-Bar Swing Range: ${fmt(analytics.recentSwingLow)} — ${fmt(analytics.recentSwingHigh)} | ATR%: ${analytics.atrPctOfPrice?.let { "%.2f%%".format(it) } ?: "N/A"}",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }

            HorizontalDivider(color = HairlineBorder)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MeridianTeal,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "TRADER RISK & POSITION SIZING",
                    style = MaterialTheme.typography.labelSmall,
                    color = MeridianTeal
                )
            }

            if (analytics.sizingResult.valid) {
                val s = analytics.sizingResult
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AnalyticsMetric("SIZE", "${s.roundedSize} ${s.sizeUnitLabel}")
                    AnalyticsMetric("RISK ($)", "$${"%.2f".format(s.riskAmountUsd)}")
                    AnalyticsMetric("NOTIONAL", "$${"%.0f".format(s.notionalUsd)}")
                    AnalyticsMetric(
                        "HEAT",
                        "${"%.2f".format(analytics.openPortfolioRiskPct)}->${"%.2f".format(analytics.projectedOpenRiskPct)}%"
                    )
                }
                s.suggestedMaxLeverage?.let { lev ->
                    Text(
                        text = "Max Safe Leverage: ${"%.1f".format(lev)}x (Liq >= ${"%.1f".format(s.estLiquidationDistanceStops ?: 3.0)}x stop)",
                        style = MonoMetricSmall,
                        color = BullEmerald
                    )
                }
            } else {
                Text(
                    text = analytics.sizingResult.rejectionReason ?: "Sizing unavailable for current balance/stop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BearCrimson
                )
            }
        }
    }
}

@Composable
fun AiVerificationScorecard(
    verificationState: AiVerificationState,
    onVerifyClick: () -> Unit,
    onOpenInAiHub: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val borderColor = when (verificationState) {
        is AiVerificationState.Success -> when (verificationState.result.verdict) {
            VerificationVerdict.HIGH_CONVICTION -> BullEmerald
            VerificationVerdict.PROCEED_WITH_CAUTION -> WarnAmber
            VerificationVerdict.WAIT_OR_SKIP -> BearCrimson
        }
        is AiVerificationState.Error -> BearCrimson
        else -> MeridianTeal.copy(alpha = 0.5f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_verification_scorecard")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        tint = MeridianTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "PRO TRADER AI VERIFICATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                }

                if (verificationState is AiVerificationState.Success) {
                    val v = verificationState.result.verdict
                    val (bg, fg) = when (v) {
                        VerificationVerdict.HIGH_CONVICTION -> BullEmeraldTint to BullEmerald
                        VerificationVerdict.PROCEED_WITH_CAUTION -> WarnAmberTint to WarnAmber
                        VerificationVerdict.WAIT_OR_SKIP -> BearCrimsonTint to BearCrimson
                    }
                    StatusPill(text = v.displayLabel, bg = bg, fg = fg)
                }
            }

            when (verificationState) {
                is AiVerificationState.Idle -> {
                    Text(
                        text = "Run structured AI verification on this setup's EMA/ADX/RSI analytics, ATR stop placement, cost-to-reward ratio, and portfolio exposure like an institutional trader.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }

                is AiVerificationState.Verifying -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MeridianTeal,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Auditing market structure, ATR stop, cost & risk limits...",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkPrimary
                        )
                    }
                }

                is AiVerificationState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BearCrimsonTint),
                        border = BorderStroke(1.dp, BearCrimson),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = verificationState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkPrimary,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                is AiVerificationState.Success -> {
                    val res = verificationState.result
                    val scoreColor = when {
                        res.confidenceScore >= 70 -> BullEmerald
                        res.confidenceScore >= 45 -> WarnAmber
                        else -> BearCrimson
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRADER CONFIDENCE: ${res.confidenceScore}%",
                            style = MonoMetricMedium,
                            color = scoreColor
                        )
                        Text(
                            text = "${res.providerName} • ${res.verifiedAtUtc}",
                            style = MonoMetricSmall,
                            color = InkSecondary
                        )
                    }

                    LinearProgressIndicator(
                        progress = { (res.confidenceScore / 100f).coerceIn(0f, 1f) },
                        color = scoreColor,
                        trackColor = SlateCard,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )

                    AuditSection("Market Structure & Regime", res.marketStructureAssessment)
                    AuditSection("Entry, Stop & Cost Audit", res.entryAndStopAudit)
                    AuditSection("Trader Risk & Sizing", res.traderRiskAssessment)

                    if (res.executionChecklist.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("EXECUTION CHECKLIST", style = MaterialTheme.typography.labelSmall, color = MeridianTeal)
                            res.executionChecklist.forEach { step ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        tint = BullEmerald,
                                        modifier = Modifier.size(14.dp).padding(top = 2.dp)
                                    )
                                    Text(step, style = MaterialTheme.typography.bodySmall, color = InkPrimary)
                                }
                            }
                        }
                    }

                    if (res.keyRiskFlags.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(WarnAmberTint, RoundedCornerShape(6.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = WarnAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text("RISK FLAGS", style = MaterialTheme.typography.labelSmall, color = WarnAmber)
                            }
                            res.keyRiskFlags.forEach { flag ->
                                Text("• $flag", style = MaterialTheme.typography.bodySmall, color = InkPrimary)
                            }
                        }
                    }

                    Text(
                        text = res.traderSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onVerifyClick,
                    enabled = verificationState !is AiVerificationState.Verifying,
                    colors = ButtonDefaults.buttonColors(containerColor = MeridianTeal, contentColor = ObsidianBg),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("verify_signal_ai_button")
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (verificationState) {
                            is AiVerificationState.Verifying -> "Verifying..."
                            is AiVerificationState.Success -> "Re-Verify Setup"
                            else -> "Verify Signal & Risk with AI"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (onOpenInAiHub != null) {
                    OutlinedButton(
                        onClick = onOpenInAiHub,
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("analyze_signal_ai_button")
                    ) {
                        Text("AI Chat", color = InkPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsMetric(label: String, value: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Text(text = value, style = MonoMetricSmall, color = InkPrimary)
    }
}

@Composable
private fun AuditSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title.uppercase(), style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        Text(text = body, style = MaterialTheme.typography.bodySmall, color = InkPrimary)
    }
}
