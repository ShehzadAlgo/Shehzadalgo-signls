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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.domain.model.Candle
import com.example.quant.ParameterSensitivityPoint
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BullEmerald
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.MeridianTeal
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.WarnAmber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun SignalCandleChart(
    candles: List<Candle>,
    entryLow: Double,
    entryHigh: Double,
    stopLoss: Double,
    tp1Price: Double,
    tp2Price: Double,
    decimals: Int,
    modifier: Modifier = Modifier
) {
    val visible = candles.takeLast(48)
    val levels = listOf(entryLow, entryHigh, stopLoss, tp1Price, tp2Price).filter { it > 0.0 }
    val rawMin = min(visible.minOfOrNull { it.low } ?: levels.minOrNull() ?: 0.0, levels.minOrNull() ?: 0.0)
    val rawMax = max(visible.maxOfOrNull { it.high } ?: levels.maxOrNull() ?: 1.0, levels.maxOrNull() ?: 1.0)
    val span = (rawMax - rawMin).coerceAtLeast(1e-6)
    val minY = rawMin - span * 0.08
    val maxY = rawMax + span * 0.08
    val totalSpan = (maxY - minY).coerceAtLeast(1e-6)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(GraphiteSurface, RoundedCornerShape(10.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        if (visible.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Price Levels Preview",
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSecondary
                )
                Text(
                    text = "Run a live scan to overlay closed-bar candlesticks",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkMuted
                )
            }
        }

        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            fun yFor(price: Double): Float = (h - ((price - minY) / totalSpan) * h).toFloat().coerceIn(2f, h - 2f)

            // Subtle horizontal grid
            val gridDash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            for (g in 1..3) {
                val gy = h * g / 4f
                drawLine(HairlineBorder, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f, pathEffect = gridDash)
            }

            // Entry zone band
            val yEntryTop = yFor(entryHigh)
            val yEntryBot = yFor(entryLow)
            drawRect(
                color = MeridianTeal.copy(alpha = 0.16f),
                topLeft = Offset(0f, min(yEntryTop, yEntryBot)),
                size = Size(w, max(4f, abs(yEntryBot - yEntryTop)))
            )

            // Candlesticks
            if (visible.isNotEmpty()) {
                val slotW = w / visible.size.toFloat()
                val bodyW = (slotW * 0.56f).coerceIn(3f, 12f)
                visible.forEachIndexed { i, c ->
                    val cx = slotW * i + slotW / 2f
                    val bullish = c.close >= c.open
                    val col = if (bullish) BullEmerald else BearCrimson
                    val yHigh = yFor(c.high)
                    val yLow = yFor(c.low)
                    val yOpen = yFor(c.open)
                    val yClose = yFor(c.close)
                    drawLine(col, Offset(cx, yHigh), Offset(cx, yLow), strokeWidth = 1.6f)
                    val top = min(yOpen, yClose)
                    val boxH = max(2f, abs(yClose - yOpen))
                    drawRect(col, topLeft = Offset(cx - bodyW / 2f, top), size = Size(bodyW, boxH))
                }
            }

            // Stop Loss, TP1, TP2 lines
            val levelDash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            val ySl = yFor(stopLoss)
            drawLine(BearCrimson, Offset(0f, ySl), Offset(w, ySl), strokeWidth = 2.2f, pathEffect = levelDash)

            val yTp1 = yFor(tp1Price)
            drawLine(MeridianTeal, Offset(0f, yTp1), Offset(w, yTp1), strokeWidth = 2f, pathEffect = levelDash)

            val yTp2 = yFor(tp2Price)
            drawLine(BullEmerald, Offset(0f, yTp2), Offset(w, yTp2), strokeWidth = 2.2f, pathEffect = levelDash)
        }

        // Compact legend tags
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LevelTag("SL ${"%.${decimals}f".format(stopLoss)}", BearCrimson)
            LevelTag("TP1 ${"%.${decimals}f".format(tp1Price)}", MeridianTeal)
            LevelTag("TP2 ${"%.${decimals}f".format(tp2Price)}", BullEmerald)
        }
    }
}

@Composable
private fun LevelTag(text: String, color: Color) {
    Text(
        text = text,
        style = MonoMetricSmall,
        color = color,
        modifier = Modifier
            .background(Color(0xCC090C10), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

@Composable
fun EquityCurveChart(
    equityCurveR: List<Double>,
    drawdownCurveR: List<Double> = emptyList(),
    modifier: Modifier = Modifier
) {
    val points = if (equityCurveR.size >= 2) equityCurveR else listOf(0.0, 0.0)
    val minR = min(0.0, points.minOrNull() ?: 0.0)
    val maxR = max(1.0, points.maxOrNull() ?: 1.0)
    val span = (maxR - minR).coerceAtLeast(0.5)
    val positiveEnd = points.last() >= 0.0
    val lineColor = if (positiveEnd) MeridianTeal else BearCrimson

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(176.dp)
            .background(GraphiteSurface, RoundedCornerShape(10.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            fun yFor(r: Double): Float = (h - ((r - minR) / span) * h).toFloat().coerceIn(2f, h - 2f)

            // Zero baseline
            val zeroY = yFor(0.0)
            drawLine(
                color = HairlineBorder,
                start = Offset(0f, zeroY),
                end = Offset(w, zeroY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )

            val path = Path()
            val fillPath = Path()
            val stepX = w / (points.size - 1).coerceAtLeast(1)
            points.forEachIndexed { idx, r ->
                val x = idx * stepX
                val y = yFor(r)
                if (idx == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, zeroY)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo((points.size - 1) * stepX, zeroY)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)
                )
            )
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            if (drawdownCurveR.size >= 2) {
                val maxDd = drawdownCurveR.maxOrNull()?.coerceAtLeast(0.5) ?: 1.0
                val ddPath = Path()
                val ddStep = w / (drawdownCurveR.size - 1)
                drawdownCurveR.forEachIndexed { idx, dd ->
                    val x = idx * ddStep
                    val y = (h - (1.0 - (dd / maxDd) * 0.28) * h).toFloat().coerceIn(2f, h - 2f)
                    if (idx == 0) ddPath.moveTo(x, y) else ddPath.lineTo(x, y)
                }
                drawPath(
                    path = ddPath,
                    color = BearCrimson.copy(alpha = 0.65f),
                    style = Stroke(width = 1.6f)
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LevelTag("Net ${"%.2f".format(points.last())}R", lineColor)
        }
    }
}

@Composable
fun SensitivityBarChart(
    neighbors: List<ParameterSensitivityPoint>,
    modifier: Modifier = Modifier
) {
    if (neighbors.isEmpty()) return
    val maxAbs = neighbors.maxOf { abs(it.expectancyR) }.coerceAtLeast(0.2)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteSurface, RoundedCornerShape(8.dp))
            .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "PARAMETER NEIGHBOR STABILITY (+/-20%)",
            style = MaterialTheme.typography.labelMedium,
            color = InkSecondary
        )
        neighbors.forEach { n ->
            val barColor = when {
                n.expectancyR >= 0.10 -> BullEmerald
                n.expectancyR > 0.0 -> WarnAmber
                else -> BearCrimson
            }
            val frac = (abs(n.expectancyR) / maxAbs).toFloat().coerceIn(0.05f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = n.label,
                    style = MonoMetricSmall,
                    color = InkSecondary,
                    modifier = Modifier.weight(0.45f)
                )
                Box(
                    modifier = Modifier
                        .weight(0.35f)
                        .height(8.dp)
                        .background(HairlineBorder, RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .height(8.dp)
                            .background(barColor, RoundedCornerShape(4.dp))
                    )
                }
                Text(
                    text = "${"%.2f".format(n.expectancyR)}R",
                    style = MonoMetricSmall,
                    color = barColor,
                    modifier = Modifier.weight(0.20f)
                )
            }
        }
    }
}
