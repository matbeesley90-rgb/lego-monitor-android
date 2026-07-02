package com.lego.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Phase 1: stock notification using NotificationCompat.Builder. Title +
 * body land straight from the ntfy frame; action buttons are extracted
 * from the JSON `actions` array and wired to ACTION_VIEW intents.
 *
 * Phase 2 will replace this with custom RemoteViews using brand-coloured
 * spans, italic fonts, and inline thumbnails.
 */
object NotificationRenderer {

    private const val CHANNEL_ID = "lego_monitor_alerts"
    private const val BUNDLE_CHANNEL_ID = "lego_monitor_bundles"

    fun show(ctx: Context, frame: JSONObject, isBundle: Boolean = false) {
        ensureChannel(ctx)

        val title  = frame.optString("title")
        val body   = frame.optString("message")
        val msgId  = frame.optString("id")
        // Stable per-message int id so each ntfy frame gets its own slot
        // in the tray rather than overwriting the previous one.
        val notifId = msgId.hashCode()

        val channel = if (isBundle) BUNDLE_CHANNEL_ID else CHANNEL_ID
        val builder = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification_head)
            .setContentTitle(if (title.isNotBlank()) title
                             else if (isBundle) "LEGO bundle" else "LEGO deal")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (isBundle) NotificationCompat.PRIORITY_DEFAULT
                         else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // Up to 3 action buttons from ntfy's actions array. Each gets an
        // ACTION_VIEW PendingIntent for the action's URL.
        val actions = frame.optJSONArray("actions")
        if (actions != null) {
            for (i in 0 until minOf(actions.length(), 3)) {
                val a = actions.optJSONObject(i) ?: continue
                val label = a.optString("label")
                val url   = a.optString("url")
                if (label.isBlank() || url.isBlank()) continue
                val pi = PendingIntent.getActivity(
                    ctx,
                    (msgId + label).hashCode(),
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, label, pi)
            }
        }

        ctx.getSystemService(NotificationManager::class.java)
            .notify(notifId, builder.build())
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Deal alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "LEGO marketplace deal notifications"
            enableVibration(true)
        })
        // Separate channel = independent mute/sound/vibration control in
        // Android settings, without a second app or topic client.
        mgr.createNotificationChannel(NotificationChannel(
            BUNDLE_CHANNEL_ID, "Bundle alerts 📦",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Job-lot / bundle heads-up notifications"
            enableVibration(false)
        })
    }
}
