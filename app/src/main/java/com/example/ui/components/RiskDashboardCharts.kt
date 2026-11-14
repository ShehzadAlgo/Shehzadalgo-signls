package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.Direction
import com.example.domain.model.PositionSizingResult
import com.example.domain.model.RiskConfig
import com.example.quant.ExposureBreakdown
import com.example.quant.SizingSensitivityPoint
import com.example.quant.VolatilityStopProfile
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BullEmerald
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.MeridianTeal
import com.example.ui.theme.MeridianTealTint
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.WarnAmber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun VolatilityAdjustedStopChart(
    candles: List<Candle>,
    profile: VolatilityStopProfile,
    activeStopPrice: Double,
    decimals: Int,
    unitLabel: String,
    modifier: Modifier = Modifier
) {
    val visible = candles.filter { it.isClosed }.takeLast(36)
    val levels = buildList {
        add(profile.entryPrice)
        add(profile.recommendedStopPrice)
        add(profile.target2RPrice)
        if (activeStopPrice > 0.0) add(activeStopPrice)
        addAll(profile.trailStopSeries)
        addAll(profile.outerBandSeries)
        visible.forEach {
            add(it.high)
            add(it.low)
        }
    }.filter { it > 0.0 }

    val rawMin = levels.minOrNull() ?: 0.0
    val rawMax = levels.maxOrNull() ?: 1.0
    val span = (rawMax - rawMin).coerceAtLeast(profile.atr14.coerceAtLeast(1e-5))
    val minY = rawMin - span * 0.10
    val maxY = rawMax + span * 0.10
    val totalSpan = (maxY - minY).coerceAtLeast(1e-6)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RoundedCornerShape(10.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("volatility_stop_chart"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ATR(14) = ${"%.${decimals}f".format(profile.atr14)} (${"%.2f".format(profile.atrPct)}%)",
                style = MonoMetricSmall,
                color = MeridianTeal
            )
            Text(
                text = "${profile.volatilityRegime} • ${"%.1f".format(profile.stopPipsOrPoints)} $unitLabel",
                style = MonoMetricSmall,
                color = if (profile.volatilityRegime == "HIGH VOL") WarnAmber else InkSecondary
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                fun yFor(price: Double): Float =
                    (h - ((price - minY) / totalSpan) * h).toFloat().coerceIn(3f, h - 3f)

                val gridDash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                for (g in 1..3) {
                    val gy = h * g / 4f
                    drawLine(
                        color = HairlineBorder,
                        start = Offset(0f, gy),
                        end = Offset(w, gy),
                        strokeWidth = 1f,
                        pathEffect = gridDash
                    )
                }

                // Risk corridor (Entry -> Recommended ATR Stop)
                val yEntry = yFor(profile.entryPrice)
                val yRecStop = yFor(profile.recommendedStopPrice)
                drawRect(
                    color = BearCrimson.copy(alpha = 0.14f),
                    topLeft = Offset(0f, min(yEntry, yRecStop)),
                    size = Size(w, max(4f, abs(yRecStop - yEntry)))
                )

                // Reward corridor (Entry -> 2R Target)
                val yTarget2R = yFor(profile.target2RPrice)
                drawRect(
                    color = BullEmerald.copy(alpha = 0.09f),
                    topLeft = Offset(0f, min(yEntry, yTarget2R)),
                    size = Size(w, max(4f, abs(yTarget2R - yEntry)))
                )

                // Closed-bar candlesticks + volatility ATR trailing envelope
                if (visible.isNotEmpty()) {
                    val slotW = w / visible.size.toFloat()
                    val bodyW = (slotW * 0.56f).coerceIn(3f, 12f)
                    visible.forEachIndexed { idx, c ->
                        val cx = slotW * idx + slotW / 2f
                        val bullish = c.close >= c.open
                        val candleColor = if (bullish) BullEmerald else BearCrimson
                        drawLine(
                            color = candleColor,
                            start = Offset(cx, yFor(c.high)),
                            end = Offset(cx, yFor(c.low)),
                            strokeWidth = 1.5f
                        )
                        val yOpen = yFor(c.open)
                        val yClose = yFor(c.close)
                        drawRect(
                            color = candleColor,
                            topLeft = Offset(cx - bodyW / 2f, min(yOpen, yClose)),
                            size = Size(bodyW, max(2f, abs(yClose - yOpen)))
                        )
                    }

                    if (profile.outerBandSeries.size == visible.size) {
                        val outerPath = Path()
                        profile.outerBandSeries.forEachIndexed { idx, p ->
                            val x = slotW * idx + slotW / 2f
                            val y = yFor(p)
                            if (idx == 0) outerPath.moveTo(x, y) else outerPath.lineTo(x, y)
                        }
                        drawPath(
                            path = outerPath,
                            color = BearCrimson.copy(alpha = 0.45f),
                            style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                        )
                    }

                    if (profile.trailStopSeries.size == visible.size) {
                        val trailPath = Path()
                        profile.trailStopSeries.forEachIndexed { idx, p ->
                            val x = slotW * idx + slotW / 2f
                            val y = yFor(p)
                            if (idx == 0) trailPath.moveTo(x, y) else trailPath.lineTo(x, y)
                        }
                        drawPath(
                            path = trailPath,
                            color = WarnAmber,
                            style = Stroke(width = 2.6f, cap = StrokeCap.Round)
                        )
                    }
                }

                // Horizontal reference lines
                val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                drawLine(MeridianTeal, Offset(0f, yEntry), Offset(w, yEntry), strokeWidth = 2f)
                drawLine(BearCrimson, Offset(0f, yRecStop), Offset(w, yRecStop), strokeWidth = 2.2f, pathEffect = dash)
                drawLine(BullEmerald, Offset(0f, yTarget2R), Offset(w, yTarget2R), strokeWidth = 1.8f, pathEffect = dash)

                if (activeStopPrice > 0.0 && abs(activeStopPrice - profile.recommendedStopPrice) > profile.atr14 * 0.05) {
                    val yUserSl = yFor(activeStopPrice)
                    drawLine(InkPrimary.copy(alpha = 0.7f), Offset(0f, yUserSl), Offset(w, yUserSl), strokeWidth = 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                }
            }

            if (!profile.hasLiveCandles) {
                Text(
                    text = "Theoretical ATR Corridor (Scan Signals tab for live closed-bar candles)",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xD9090C10), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                VolBadge("ENTRY ${"%.${decimals}f".format(profile.entryPrice)}", MeridianTeal)
                VolBadge("${profile.multiplier}x ATR SL ${"%.${decimals}f".format(profile.recommendedStopPrice)}", BearCrimson)
                VolBadge("2R ${"%.${decimals}f".format(profile.target2RPrice)}", BullEmerald)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (profile.hasLiveCandles) "Amber line: Dynamic ${profile.multiplier}x ATR trail" else "Modeled from instrument stop/pip volatility",
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted
            )
            Text(
                text = if (profile.direction == Direction.LONG) "LONG BUFFER" else "SHORT BUFFER",
                style = MonoMetricSmall,
                color = if (profile.direction == Direction.LONG) BullEmerald else BearCrimson
            )
        }
    }
}

@Composable
private fun VolBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MonoMetricSmall,
        color = color,
        modifier = Modifier
            .background(Color(0xD9090C10), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
fun PositionSizingCurveChart(
    points: List<SizingSensitivityPoint>,
    activeSizing: PositionSizingResult,
    activeStopDistance: Double,
    sizeUnitLabel: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RoundedCornerShape(10.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("position_sizing_chart"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SIZE DECAY CURVE (${sizeUnitLabel.uppercase()} VS ATR STOP WIDTH)",
                style = MaterialTheme.typography.labelSmall,
                color = InkSecondary
            )
            if (activeSizing.valid) {
                Text(
                    text = "${activeSizing.roundedSize} $sizeUnitLabel",
                    style = MonoMetricSmall,
                    color = MeridianTeal
                )
            }
        }

        if (points.isEmpty()) {
            Text(
                text = "Enter valid balance, entry, and stop distance to plot sizing sensitivity.",
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
                modifier = Modifier.padding(vertical = 18.dp)
            )
        } else {
            val maxSize = max(points.maxOf { it.roundedSize }, activeSizing.roundedSize).coerceAtLeast(0.01)
            val maxNotional = max(points.maxOf { it.notionalUsd }, activeSizing.notionalUsd).coerceAtLeast(1.0)
            val minStop = points.first().stopDistancePrice
            val maxStop = points.last().stopDistancePrice.coerceAtLeast(minStop + 1e-6)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val w = size.width
                    val h = size.height
                    val chartH = h - 10f
                    val gridDash = PathEffect.dashPathEffect(floatArrayOf(5f, 7f))

                    for (g in 1..3) {
                        val y = chartH * g / 4f
                        drawLine(HairlineBorder, Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = gridDash)
                    }

                    val stepX = w / (points.size - 1).coerceAtLeast(1)
                    val barW = (stepX * 0.42f).coerceIn(8f, 28f)

                    // Background notional bars
                    points.forEachIndexed { idx, pt ->
                        val cx = idx * stepX
                        val barFrac = (pt.notionalUsd / maxNotional).toFloat().coerceIn(0.02f, 1f)
                        val barH = chartH * 0.45f * barFrac
                        drawRoundRect(
                            color = MeridianTeal.copy(alpha = 0.14f),
                            topLeft = Offset((cx - barW / 2f).coerceIn(0f, w - barW), chartH - barH),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                    }

                    // Position size sensitivity curve
                    val linePath = Path()
                    val fillPath = Path()
                    points.forEachIndexed { idx, pt ->
                        val x = idx * stepX
                        val frac = (pt.roundedSize / maxSize).toFloat().coerceIn(0f, 1f)
                        val y = (chartH - frac * (chartH - 14f)).coerceIn(8f, chartH)
                        if (idx == 0) {
                            linePath.moveTo(x, y)
                            fillPath.moveTo(x, chartH)
                            fillPath.lineTo(x, y)
                        } else {
                            linePath.lineTo(x, y)
                            fillPath.lineTo(x, y)
                        }
                    }
                    fillPath.lineTo((points.size - 1) * stepX, chartH)
                    fillPath.close()

                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(MeridianTeal.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = MeridianTeal,
                        style = Stroke(width = 3f, cap = StrokeCap.Round)
                    )

                    // Point dots
                    points.forEachIndexed { idx, pt ->
                        val x = idx * stepX
                        val frac = (pt.roundedSize / maxSize).toFloat().coerceIn(0f, 1f)
                        val y = (chartH - frac * (chartH - 14f)).coerceIn(8f, chartH)
                        drawCircle(
                            color = if (pt.roundedSize > 0.0) MeridianTeal else BearCrimson,
                            radius = 4.5f,
                            center = Offset(x, y)
                        )
                    }

                    // Active trade setup marker
                    if (activeSizing.valid && activeStopDistance > 0.0) {
                        val stopFrac = ((activeStopDistance - minStop) / (maxStop - minStop)).toFloat().coerceIn(0f, 1f)
                        val activeX = stopFrac * w
                        val sizeFrac = (activeSizing.roundedSize / maxSize).toFloat().coerceIn(0f, 1f)
                        val activeY = (chartH - sizeFrac * (chartH - 14f)).coerceIn(8f, chartH)
                        drawLine(
                            color = WarnAmber.copy(alpha = 0.7f),
                            start = Offset(activeX, 0f),
                            end = Offset(activeX, chartH),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                        )
                        drawCircle(color = WarnAmber.copy(alpha = 0.3f), radius = 12f, center = Offset(activeX, activeY))
                        drawCircle(color = WarnAmber, radius = 6f, center = Offset(activeX, activeY))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val first = points.first()
                val mid = points[points.size / 2]
                val last = points.last()
                Text(
                    text = "${first.atrMultiplier}x ATR -> ${first.roundedSize}",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
                Text(
                    text = "${mid.atrMultiplier}x -> ${mid.roundedSize}",
                    style = MonoMetricSmall,
                    color = MeridianTeal
                )
                Text(
                    text = "${last.atrMultiplier}x -> ${last.roundedSize}",
                    style = MonoMetricSmall,
                    color = InkSecondary
                )
            }
        }
    }
}

@Composable
fun RealTimeExposureCharts(
    exposure: ExposureBreakdown,
    riskConfig: RiskConfig,
    proposedRiskPct: Double,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("real_time_exposure_chart"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Portfolio Heat + Drawdown Ladder + Daily Limit Canvas Bars
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GraphiteSurface, RoundedCornerShape(10.dp))
                .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PORTFOLIO HEAT & RISK BUDGET",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeridianTeal
                    )
                    Text(
                        text = "${"%.2f".format(exposure.openRiskPct)}% active (+${"%.2f".format(proposedRiskPct)}% setup)",
                        style = MonoMetricSmall,
                        color = if (exposure.projectedRiskPct > riskConfig.maxOpenRiskPct) BearCrimson else InkPrimary
                    )
                }

                // Open Heat + Projected Trade Segmented Canvas Bar
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val cap = riskConfig.maxOpenRiskPct.coerceAtLeast(0.5)
                    val maxAxis = max(cap * 1.15, exposure.projectedRiskPct)

                    drawRoundRect(
                        color = HairlineBorder,
                        topLeft = Offset(0f, 3f),
                        size = Size(w, h - 6f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )

                    val openFrac = (exposure.openRiskPct / maxAxis).toFloat().coerceIn(0f, 1f)
                    val openColor = when {
                        exposure.openRiskPct >= cap -> BearCrimson
                        exposure.openRiskPct >= cap * 0.75 -> WarnAmber
                        else -> MeridianTeal
                    }
                    if (openFrac > 0f) {
                        drawRoundRect(
                            color = openColor,
                            topLeft = Offset(0f, 3f),
                            size = Size(w * openFrac, h - 6f),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }

                    val projTotalFrac = (exposure.projectedRiskPct / maxAxis).toFloat().coerceIn(0f, 1f)
                    if (projTotalFrac > openFrac) {
                        val projColor = if (exposure.projectedRiskPct > cap) BearCrimson.copy(alpha = 0.55f) else BullEmerald.copy(alpha = 0.45f)
                        drawRect(
                            color = projColor,
                            topLeft = Offset(w * openFrac, 3f),
                            size = Size(w * (projTotalFrac - openFrac), h - 6f)
                        )
                    }

                    // Max open risk cap marker line
                    val capX = ((cap / maxAxis).toFloat().coerceIn(0f, 1f)) * w
                    drawLine(
                        color = BearCrimson,
                        start = Offset(capX, 0f),
                        end = Offset(capX, h),
                        strokeWidth = 3f
                    )
                }

                // Drawdown Ladder & Daily Loss Gauges
                BudgetMeterRow(
                    label = "Drawdown Ladder",
                    valueText = "${"%.2f".format(exposure.currentDrawdownPct)}% / ${riskConfig.drawdownHaltPct}% halt (halves @ ${riskConfig.drawdownHalvePct}%)",
                    usedPct = exposure.currentDrawdownPct,
                    capPct = riskConfig.drawdownHaltPct,
                    warnThresholdPct = riskConfig.drawdownHalvePct
                )
                BudgetMeterRow(
                    label = "Daily Loss Budget",
                    valueText = "${"%.2f".format(exposure.realizedDailyLossPct)}% / ${riskConfig.dailyLossLimitPct}% cap",
                    usedPct = exposure.realizedDailyLossPct,
                    capPct = riskConfig.dailyLossLimitPct,
                    warnThresholdPct = riskConfig.dailyLossLimitPct * 0.7
                )
            }
        }

        // 2. Diverging FX Currency-Leg Net Exposure Chart
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GraphiteSurface, RoundedCornerShape(10.dp))
                .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "FX CURRENCY-LEG NET EXPOSURE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeridianTeal
                    )
                    Text(
                        text = "Cap ±${riskConfig.maxClusterRiskPct}%",
                        style = MonoMetricSmall,
                        color = InkSecondary
                    )
                }

                if (exposure.fxNetExposurePct.isEmpty()) {
                    Text(
                        text = "0% active FX currency-leg exposure across USD, EUR, GBP, JPY.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkMuted
                    )
                } else {
                    val entries = exposure.fxNetExposurePct.entries
                        .sortedByDescending { abs(it.value) }
                    val axisLimit = max(riskConfig.maxClusterRiskPct * 1.25, entries.maxOf { abs(it.value) }).coerceAtLeast(0.5)

                    entries.forEach { (ccy, netPct) ->
                        val over = abs(netPct) > riskConfig.maxClusterRiskPct
                        val barColor = when {
                            over -> BearCrimson
                            netPct >= 0.0 -> BullEmerald
                            else -> WarnAmber
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = ccy,
                                style = MonoMetricSmall,
                                color = InkPrimary,
                                modifier = Modifier.weight(0.16f)
                            )
                            Canvas(
                                modifier = Modifier
                                    .weight(0.62f)
                                    .height(16.dp)
                            ) {
                                val w = size.width
                                val h = size.height
                                val centerX = w / 2f
                                drawRoundRect(
                                    color = HairlineBorder.copy(alpha = 0.55f),
                                    topLeft = Offset(0f, 2f),
                                    size = Size(w, h - 4f),
                                    cornerRadius = CornerRadius(4f, 4f)
                                )
                                // Cluster cap lines
                                val capOffset = ((riskConfig.maxClusterRiskPct / axisLimit).toFloat().coerceIn(0f, 1f)) * (w / 2f)
                                val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
                                drawLine(BearCrimson.copy(alpha = 0.65f), Offset(centerX + capOffset, 0f), Offset(centerX + capOffset, h), 1.5f, pathEffect = dash)
                                drawLine(BearCrimson.copy(alpha = 0.65f), Offset(centerX - capOffset, 0f), Offset(centerX - capOffset, h), 1.5f, pathEffect = dash)
                                drawLine(InkSecondary, Offset(centerX, 0f), Offset(centerX, h), 1.5f)

                                val barLen = ((abs(netPct) / axisLimit).toFloat().coerceIn(0f, 1f)) * (w / 2f)
                                val left = if (netPct >= 0.0) centerX else centerX - barLen
                                drawRect(
                                    color = barColor,
                                    topLeft = Offset(left, 3f),
                                    size = Size(max(2f, barLen), h - 6f)
                                )
                            }
                            Text(
                                text = "${if (netPct >= 0) "+" else ""}${"%.2f".format(netPct)}%",
                                style = MonoMetricSmall,
                                color = barColor,
                                modifier = Modifier.weight(0.22f)
                            )
                        }
                    }
                }
            }
        }

        // 3. Crypto Cluster & BTC-Beta Risk Monitor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(GraphiteSurface, RoundedCornerShape(10.dp))
                .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val betaLimit = riskConfig.maxClusterRiskPct * 1.5
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "CRYPTO CLUSTER & BTC-BETA MONITOR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MeridianTeal
                    )
                    Text(
                        text = "Beta: ${"%.2f".format(exposure.cryptoNetBtcBetaPct)}% / ${"%.2f".format(betaLimit)}%",
                        style = MonoMetricSmall,
                        color = if (abs(exposure.cryptoNetBtcBetaPct) > betaLimit) BearCrimson else InkPrimary
                    )
                }

                BudgetMeterRow(
                    label = "Net BTC-Beta Weighted Risk",
                    valueText = "${if (exposure.cryptoNetBtcBetaPct >= 0) "+" else ""}${"%.2f".format(exposure.cryptoNetBtcBetaPct)}%",
                    usedPct = abs(exposure.cryptoNetBtcBetaPct),
                    capPct = betaLimit,
                    warnThresholdPct = riskConfig.maxClusterRiskPct
                )

                if (exposure.cryptoClusterRiskPct.isEmpty()) {
                    Text(
                        text = "No active crypto perpetual cluster exposure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkMuted
                    )
                } else {
                    exposure.cryptoClusterRiskPct.forEach { (cluster, riskPct) ->
                        BudgetMeterRow(
                            label = cluster,
                            valueText = "${"%.2f".format(riskPct)}% / ${riskConfig.maxClusterRiskPct}%",
                            usedPct = riskPct,
                            capPct = riskConfig.maxClusterRiskPct,
                            warnThresholdPct = riskConfig.maxClusterRiskPct * 0.75
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetMeterRow(
    label: String,
    valueText: String,
    usedPct: Double,
    capPct: Double,
    warnThresholdPct: Double
) {
    val safeCap = capPct.coerceAtLeast(0.1)
    val frac = (usedPct / safeCap).toFloat().coerceIn(0f, 1f)
    val fillColor = when {
        usedPct >= safeCap -> BearCrimson
        usedPct >= warnThresholdPct -> WarnAmber
        else -> BullEmerald
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            Text(text = valueText, style = MonoMetricSmall, color = if (usedPct >= safeCap) BearCrimson else InkPrimary)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = HairlineBorder,
                topLeft = Offset.Zero,
                size = Size(w, h),
                cornerRadius = CornerRadius(4f, 4f)
            )
            if (frac > 0f) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset.Zero,
                    size = Size(max(6f, w * frac), h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
            val warnX = ((warnThresholdPct / safeCap).toFloat().coerceIn(0f, 1f)) * w
            drawLine(
                color = InkPrimary.copy(alpha = 0.45f),
                start = Offset(warnX, 0f),
                end = Offset(warnX, h),
                strokeWidth = 1.5f
            )
        }
    }
}
