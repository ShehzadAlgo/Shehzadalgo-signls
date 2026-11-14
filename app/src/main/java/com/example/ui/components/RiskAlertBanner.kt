package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.quant.AlertSeverity
import com.example.quant.RiskAlertEvent
import com.example.ui.theme.BearCrimson
import com.example.ui.theme.BearCrimsonTint
import com.example.ui.theme.BullEmerald
import com.example.ui.theme.BullEmeraldTint
import com.example.ui.theme.GraphiteSurface
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.MeridianTeal
import com.example.ui.theme.MonoMetricSmall
import com.example.ui.theme.SlateCard
import com.example.ui.theme.WarnAmber
import com.example.ui.theme.WarnAmberTint

@Composable
fun RiskAlertBanner(
    alerts: List<RiskAlertEvent>,
    live24hEnabled: Boolean,
    openMonitoredCount: Int,
    onDismissAlert: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (alerts.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateCard),
            border = BorderStroke(1.dp, HairlineBorder),
            modifier = modifier
                .fillMaxWidth()
                .testTag("stop_alert_banner")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = BullEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "VOLATILITY STOP GUARDIAN: ALL CLEAR",
                            style = MaterialTheme.typography.labelMedium,
                            color = BullEmerald
                        )
                        Text(
                            text = "$openMonitoredCount open position(s) + active ATR setup monitored",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .background(
                            if (live24hEnabled) BullEmeraldTint else GraphiteSurface,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(if (live24hEnabled) BullEmerald else MeridianTeal, CircleShape)
                    )
                    Text(
                        text = if (live24hEnabled) "24H LIVE" else "ARMED",
                        style = MonoMetricSmall,
                        color = if (live24hEnabled) BullEmerald else MeridianTeal
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .testTag("stop_alert_banner"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            alerts.forEachIndexed { idx, alert ->
                val isCritical = alert.severity == AlertSeverity.CRITICAL
                val borderCol = if (isCritical) BearCrimson else WarnAmber
                val bgCol = if (isCritical) BearCrimsonTint else WarnAmberTint

                Card(
                    colors = CardDefaults.cardColors(containerColor = bgCol),
                    border = BorderStroke(1.dp, borderCol),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isCritical) Icons.Outlined.WarningAmber else Icons.Outlined.NotificationsActive,
                                contentDescription = "Stop-loss proximity warning",
                                tint = borderCol,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = alert.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = borderCol
                                    )
                                    val badge = alert.distanceAtr?.let { "${"%.2f".format(it)}x ATR" }
                                        ?: "${"%.0f".format(alert.distancePctOfRisk)}% RISK"
                                    Text(
                                        text = badge,
                                        style = MonoMetricSmall,
                                        color = InkPrimary,
                                        modifier = Modifier
                                            .background(GraphiteSurface, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = alert.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkPrimary
                                )
                                Text(
                                    text = "Updated ${alert.timestampUtc}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkSecondary
                                )
                            }
                        }
                        IconButton(
                            onClick = { onDismissAlert(alert.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .let { m ->
                                    if (idx == 0) m.testTag("dismiss_alert_button") else m.testTag("dismiss_alert_${alert.id}")
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Dismiss stop-loss alert",
                                tint = InkPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}
