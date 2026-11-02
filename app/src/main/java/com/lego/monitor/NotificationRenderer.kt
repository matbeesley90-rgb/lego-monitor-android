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

    /** [silent]: a replayed frame (WebSocketService `since=` catch-up) —
     *  no sound/vibration. 2026-09-18: the bundle-topic branch is gone
     *  (the topic split never went live; V4 bundles get their own channel
     *  in V4NotificationRenderer). */
    fun show(ctx: Context, frame: JSONObject, silent: Boolean = false) {
        ensureChannel(ctx)

        val title  = frame.optString("title")
        val body   = frame.optString("message")
        val msgId  = frame.optString("id")
        // Stable per-message int id so each ntfy frame gets its own slot
        // in the tray rather than overwriting the previous one.
        val notifId = msgId.hashCode()

        // Grail (🎯) alerts should STAY in the tray when tapped — they're a
        // rare, high-value heads-up the user wants to keep referring back
        // to. Everything else auto-dismisses on tap as normal.
        val isGrail = title.contains("Grail", ignoreCase = true)

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_head)
            .setContentTitle(if (title.isNotBlank()) title else "LEGO deal")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(!isGrail)
        if (silent) builder.setSilent(true)

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
    }
}
