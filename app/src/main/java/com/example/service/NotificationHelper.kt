package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {
    const val CHANNEL_LIVE_MONITOR = "meridian_live_monitor"
    const val CHANNEL_SIGNALS = "meridian_signals"
    const val CHANNEL_RISK_ALERTS = "meridian_risk_alerts"
    const val FOREGROUND_NOTIFICATION_ID = 4101

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val monitorChannel = NotificationChannel(
            CHANNEL_LIVE_MONITOR,
            "24H Live Market Monitor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent status notification while 24-hour FX & Crypto scanning is active."
        }

        val signalChannel = NotificationChannel(
            CHANNEL_SIGNALS,
            "Live Trade Signals",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Instant alerts whenever a closed-bar strategy triggers an approved trade signal."
        }

        val riskChannel = NotificationChannel(
            CHANNEL_RISK_ALERTS,
            "Stop-Loss & Risk Engine Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent alerts when price approaches or breaches a volatility-adjusted stop-loss."
        }

        manager.createNotificationChannels(listOf(monitorChannel, signalChannel, riskChannel))
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun buildLiveMonitorNotification(
        context: Context,
        statusText: String
    ): Notification {
        ensureChannels(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_LIVE_MONITOR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Shehzad Algo 24H Live Monitor")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    fun postSignalNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String
    ) {
        postAlert(context, CHANNEL_SIGNALS, notificationId, title, body, NotificationCompat.PRIORITY_HIGH)
    }

    fun postRiskAlertNotification(
        context: Context,
        notificationId: Int,
        title: String,
        body: String
    ) {
        postAlert(context, CHANNEL_RISK_ALERTS, notificationId, title, body, NotificationCompat.PRIORITY_MAX)
    }

    private fun postAlert(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        body: String,
        priority: Int
    ) {
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }
}
