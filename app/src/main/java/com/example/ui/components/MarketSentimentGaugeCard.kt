package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ai.MarketSentimentReport
import com.example.data.ai.MarketSentimentState
import com.example.data.ai.SentimentRegime
import com.example.domain.model.RiskConfig
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BearCrimsonTint
import com.example.ui.theme.BullEmerald
import com.example.ui.theme.BullEmeraldTint
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MarketSentimentGaugeCard(
    sentimentState: MarketSentimentState,
    riskConfig: RiskConfig,
    sentimentOverlayEnabled: Boolean,
    onToggleSentimentOverlay: (Boolean) -> Unit,
    onRefreshSentiment: () -> Unit,
    onApplyToPersistedRiskConfig: (MarketSentimentReport) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("market_sentiment_gauge_card")
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MeridianTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "GEMINI MARKET SENTIMENT GAUGE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MeridianTeal
                        )
                        Text(
                            text = "Live Crypto & Forex news risk radar",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                    }
                }
                OutlinedButton(
                    onClick = onRefreshSentiment,
                    enabled = sentimentState !is MarketSentimentState.Analyzing,
                    border = BorderStroke(1.dp, MeridianTeal),
                    modifier = Modifier
                        .height(40.dp)
                        .testTag("refresh_sentiment_button")
                ) {
                    if (sentimentState is MarketSentimentState.Analyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = MeridianTeal,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh Gemini news sentiment",
                            tint = MeridianTeal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (sentimentState is MarketSentimentState.Analyzing) "Analyzing..." else "Refresh",
                        style = MaterialTheme.typography.labelMedium,
                        color = MeridianTeal
                    )
                }
            }

            when (sentimentState) {
                is MarketSentimentState.Idle -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GraphiteSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Tap Refresh to scan live CryptoCompare, Yahoo Finance & Forex headlines with Gemini AI and compute your macro risk posture.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                            Button(
                                onClick = onRefreshSentiment,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MeridianTeal,
                                    contentColor = ObsidianBg
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("run_initial_sentiment_button")
                            ) {
                                Icon(Icons.Outlined.Newspaper, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Scan Crypto & Forex News Sentiment", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }

                is MarketSentimentState.Analyzing -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GraphiteSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = MeridianTeal, modifier = Modifier.size(28.dp))
                            Text(
                                text = "Fetching Crypto & Forex wire + scoring Risk Engine impact with Gemini...",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                    }
                }

                is MarketSentimentState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BearCrimsonTint, RoundedCornerShape(8.dp))
                            .border(1.dp, BearCrimson, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = sentimentState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkPrimary
                            )
                            Text(
                                text = "Deterministic Risk Engine sizing & ATR stops remain active.",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkSecondary
                            )
                        }
                    }
                }

                is MarketSentimentState.Success -> {
                    val report = sentimentState.report
                    val regimeColor = when (report.regime) {
                        SentimentRegime.EXTREME_FEAR -> BearCrimson
                        SentimentRegime.RISK_OFF_BEARISH -> WarnAmber
                        SentimentRegime.NEUTRAL_CAUTIOUS -> InkSecondary
                        SentimentRegime.RISK_ON_BULLISH -> MeridianTeal
                        SentimentRegime.EUPHORIA_OVERHEATED -> BullEmerald
                    }
                    val regimeBg = when (report.regime) {
                        SentimentRegime.EXTREME_FEAR -> BearCrimsonTint
                        SentimentRegime.RISK_OFF_BEARISH -> WarnAmberTint
                        SentimentRegime.NEUTRAL_CAUTIOUS -> GraphiteSurface
                        SentimentRegime.RISK_ON_BULLISH -> MeridianTealTint
                        SentimentRegime.EUPHORIA_OVERHEATED -> BullEmeraldTint
                    }

                    // 1. Semicircular Canvas Dial Gauge
                    SentimentArcGaugeCanvas(
                        gaugeIndex = report.gaugeIndex0To100,
                        signedScore = report.overallScore,
                        regimeLabel = report.regime.displayLabel,
                        regimeColor = regimeColor,
                        regimeBg = regimeBg,
                        updatedAtUtc = report.updatedAtUtc,
                        modelName = report.modelName
                    )

                    // 2. Crypto vs Forex Bipolar Sub-Gauges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SentimentSubScoreBar(
                            label = "CRYPTO WIRE",
                            score = report.cryptoScore,
                            modifier = Modifier.weight(1f)
                        )
                        SentimentSubScoreBar(
                            label = "FOREX / MACRO",
                            score = report.forexScore,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 3. Risk Engine Adaptive Integration Panel
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GraphiteSurface, RoundedCornerShape(8.dp))
                            .border(1.dp, regimeColor.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = regimeColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "ADAPTIVE RISK POSTURE: ${report.riskPosture.displayLabel}",
                                            style = MonoMetricSmall,
                                            color = regimeColor
                                        )
                                        val effectiveRisk = (riskConfig.riskPerTradePct * report.recommendedRiskMultiplier).coerceAtLeast(0.1)
                                        Text(
                                            text = "Effective Risk/Trade: ${"%.2f".format(effectiveRisk)}% • ATR Stop Bias: ${"%.1f".format(report.atrMultiplierBias)}x",
                                            style = MonoMetricSmall,
                                            color = InkPrimary
                                        )
                                    }
                                }
                                Switch(
                                    checked = sentimentOverlayEnabled,
                                    onCheckedChange = onToggleSentimentOverlay,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = ObsidianBg,
                                        checkedTrackColor = MeridianTeal
                                    ),
                                    modifier = Modifier.testTag("sentiment_overlay_toggle")
                                )
                            }

                            Text(
                                text = report.riskWarning,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )

                            OutlinedButton(
                                onClick = { onApplyToPersistedRiskConfig(report) },
                                border = BorderStroke(1.dp, regimeColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("apply_sentiment_config_button")
                            ) {
                                Text(
                                    text = "Sync ${"%.2f".format(report.recommendedRiskMultiplier)}x Risk Scalar to Persisted Risk Engine",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = regimeColor
                                )
                            }
                        }
                    }

                    // 4. Key News Drivers & Wire Headlines
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "KEY NEWS CATALYSTS (${report.analyzedHeadlines.size} HEADLINES)",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkSecondary
                        )
                        Text(
                            text = report.headlineSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = InkPrimary
                        )
                        report.keyDrivers.take(3).forEach { driver ->
                            Text(
                                text = "• $driver",
                                style = MaterialTheme.typography.bodySmall,
                                color = MeridianTeal
                            )
                        }
                        if (report.analyzedHeadlines.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            report.analyzedHeadlines.take(4).forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(GraphiteSurface, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.category,
                                        style = MonoMetricSmall,
                                        color = if (item.category == "CRYPTO") WarnAmber else MeridianTeal
                                    )
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = InkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = item.source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkMuted,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SentimentArcGaugeCanvas(
    gaugeIndex: Int,
    signedScore: Int,
    regimeLabel: String,
    regimeColor: Color,
    regimeBg: Color,
    updatedAtUtc: String,
    modelName: String
) {
    val animatedIndex by animateFloatAsState(
        targetValue = gaugeIndex.toFloat().coerceIn(0f, 100f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sentimentNeedle"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RoundedCornerShape(10.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
            .testTag("sentiment_arc_canvas")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(145.dp)
                ) {
                    val strokePx = 20.dp.toPx()
                    val diameter = minOf(size.width * 0.78f, (size.height - 16.dp.toPx()) * 2f)
                    val radius = diameter / 2f
                    val centerX = size.width / 2f
                    val centerY = size.height - 14.dp.toPx()
                    val arcTopLeft = Offset(centerX - radius, centerY - radius)
                    val arcSize = Size(diameter, diameter)

                    val segments = listOf(
                        Triple(180f, 33f, BearCrimson),
                        Triple(216f, 33f, WarnAmber),
                        Triple(252f, 36f, InkSecondary.copy(alpha = 0.75f)),
                        Triple(291f, 33f, MeridianTeal),
                        Triple(327f, 33f, BullEmerald)
                    )
                    segments.forEach { (startDeg, sweepDeg, color) ->
                        drawArc(
                            color = color.copy(alpha = 0.85f),
                            startAngle = startDeg,
                            sweepAngle = sweepDeg,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }

                    // Needle angle: 0 -> 180 deg (left), 100 -> 360 deg (right)
                    val needleDeg = 180.0 + (animatedIndex / 100.0) * 180.0
                    val rad = needleDeg * PI / 180.0
                    val needleLen = radius - strokePx * 0.3f
                    val tipX = (centerX + cos(rad) * needleLen).toFloat()
                    val tipY = (centerY + sin(rad) * needleLen).toFloat()

                    drawLine(
                        color = InkPrimary,
                        start = Offset(centerX, centerY),
                        end = Offset(tipX, tipY),
                        strokeWidth = 3.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = regimeColor,
                        radius = 7.dp.toPx(),
                        center = Offset(centerX, centerY)
                    )
                    drawCircle(
                        color = InkPrimary,
                        radius = 3.dp.toPx(),
                        center = Offset(centerX, centerY)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 26.dp)
                ) {
                    Text(
                        text = "$gaugeIndex",
                        style = MonoMetricLarge,
                        color = regimeColor
                    )
                    Text(
                        text = "SCORE ${if (signedScore >= 0) "+" else ""}$signedScore",
                        style = MonoMetricSmall,
                        color = InkSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("FEAR", style = MaterialTheme.typography.labelSmall, color = BearCrimson)
                Text(
                    text = regimeLabel,
                    style = MonoMetricSmall,
                    color = regimeColor,
                    modifier = Modifier
                        .background(regimeBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Text("GREED", style = MaterialTheme.typography.labelSmall, color = BullEmerald)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Model: $modelName • Updated $updatedAtUtc",
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted
            )
        }
    }
}

@Composable
private fun SentimentSubScoreBar(
    label: String,
    score: Int,
    modifier: Modifier = Modifier
) {
    val barColor = when {
        score <= -25 -> BearCrimson
        score < 0 -> WarnAmber
        score >= 25 -> BullEmerald
        else -> MeridianTeal
    }
    Column(
        modifier = modifier
            .background(GraphiteSurface, RoundedCornerShape(8.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            Text(
                text = "${if (score >= 0) "+" else ""}$score",
                style = MonoMetricSmall,
                color = barColor
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val w = size.width
            val h = size.height
            val mid = w / 2f
            drawRoundRect(
                color = HairlineBorder,
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawLine(InkSecondary, Offset(mid, 0f), Offset(mid, h), strokeWidth = 1.5f)
            val frac = (kotlin.math.abs(score).coerceIn(0, 100) / 100f) * (w / 2f)
            val left = if (score >= 0) mid else mid - frac
            if (frac > 1f) {
                drawRect(color = barColor, topLeft = Offset(left, 1f), size = Size(frac, h - 2f))
            }
        }
    }
}
