package com.lego.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * In-place card redraws (2026-09-09). A tap on the photo or the info face
 * fires this broadcast instead of launching an Activity, so the shade
 * stays open; the receiver re-posts the SAME notification with the card
 * in its new mode (bigger photo / info block open). The original ntfy
 * frame travels in the intent so no state has to be kept anywhere.
 */
class CardActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // 2026-09-19 re-review: user dismissal (deleteIntent) — bump the
        // card's generation so an in-flight image download cannot re-post
        // the card the user just swiped/tapped away.
        if (intent.action == ACTION_DISMISSED) {
            V4NotificationRenderer.noteDismissed(intent.getIntExtra(EXTRA_NOTIF_ID, 0))
            return
        }
        if (intent.action != ACTION_REDRAW) return
        val frameJson = intent.getStringExtra(EXTRA_FRAME) ?: return
        val frame = try { JSONObject(frameJson) } catch (_: Exception) { return }
        val payload = V4Payload.tryParse(frame.optString("message")) ?: return
        val mode = V4NotificationRenderer.CardMode(
            photoBig = intent.getBooleanExtra(EXTRA_PHOTO_BIG, false),
            infoOpen = intent.getBooleanExtra(EXTRA_INFO_OPEN, false),
            infoPage = intent.getIntExtra(EXTRA_INFO_PAGE, 0),
        )
        Log.d("LegoV4", "redraw ${payload.listingId} mode=$mode")
        V4NotificationRenderer.show(ctx.applicationContext, frame, payload, mode)
    }

    companion object {
        const val ACTION_REDRAW = "com.lego.monitor.REDRAW_CARD"
        const val ACTION_DISMISSED = "com.lego.monitor.CARD_DISMISSED"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_FRAME = "frame"
        const val EXTRA_PHOTO_BIG = "photo_big"
        const val EXTRA_INFO_OPEN = "info_open"
        const val EXTRA_INFO_PAGE = "info_page"
    }
}
