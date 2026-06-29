package com.lego.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Phase 2 renderer — custom RemoteViews using the V4 payload. Loads
 * the listing image in the background, builds collapsed + expanded
 * layouts with brand colour applied to the brand text and per-cell %
 * coloured green/red, then posts via NotificationCompat with
 * setCustomContentView + setCustomBigContentView.
 *
 * Stock NotificationRenderer (Phase 1) is still used when the message
 * isn't V4 JSON — WebSocketService picks which path based on
 * V4Payload.tryParse.
 */
object V4NotificationRenderer {

    private const val TAG = "LegoV4"
    private const val CHANNEL_ID = "lego_monitor_alerts"
    private val imageThread = Executors.newSingleThreadExecutor()

    fun show(ctx: Context, frame: org.json.JSONObject, payload: V4Payload) {
        Log.d(TAG, "V4 show: brand=${payload.brand} kind=${payload.kind} pct=${payload.pct} setName='${payload.setName}'")
        try {
            ensureChannel(ctx)
            val msgId  = frame.optString("id")
            val notifId = msgId.hashCode()

            // Build the bare notification synchronously, then post; if the
            // image is in cache (and our /img/proxy sets a Cache-Control:
            // public,max-age=86400), it lands almost instantly. Image
            // download happens off-thread; once it finishes we re-post the
            // same notifId with the bitmap filled in.
            Log.d(TAG, "V4 posting initial (no bitmap yet) notifId=$notifId")
            post(ctx, notifId, payload, bmp = null)
            Log.d(TAG, "V4 initial post ok")

            if (payload.imageUrl.isNotBlank()) {
                imageThread.execute {
                    try {
                        Log.d(TAG, "V4 fetching image: ${payload.imageUrl}")
                        val conn = URL(payload.imageUrl).openConnection()
                        conn.connectTimeout = 5000
                        conn.readTimeout = 10000
                        conn.getInputStream().use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) {
                                Log.d(TAG, "V4 image decoded ${bmp.width}x${bmp.height}, re-posting")
                                post(ctx, notifId, payload, bmp)
                            } else {
                                Log.w(TAG, "V4 image decode returned null")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "V4 image fetch failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "V4 render threw", e)
        }
    }

    private fun post(
        ctx: Context, notifId: Int, p: V4Payload,
        bmp: android.graphics.Bitmap?
    ) {
        val collapsed = RemoteViews(ctx.packageName, R.layout.notification_collapsed)
        val expanded  = RemoteViews(ctx.packageName, R.layout.notification_expanded)

        val brandColor = brandColorFor(p.brand)
        val titleText  = brandTitleSpannable(p, brandColor)
        val subtitle   = setNameLine(p)

        // Collapsed (heads-up + lock-screen) — single-line layout with
        // thumb on the left.
        collapsed.setTextViewText(R.id.notif_title, titleText)
        collapsed.setTextViewText(R.id.notif_subtitle, subtitle)

        // Expanded — text gets full width, image goes at the bottom.
        // Each grid row is 4 separate cells so they can't wrap into
        // each other regardless of length.
        expanded.setTextViewText(R.id.notif_title, titleText)
        expanded.setTextViewText(R.id.notif_setname, subtitle)
        fillGridRow(expanded, p, top = true)
        fillGridRow(expanded, p, top = false)

        if (bmp != null) {
            collapsed.setImageViewBitmap(R.id.notif_thumb, bmp)
            expanded.setImageViewBitmap(R.id.notif_thumb, bmp)
        } else {
            // Placeholder while the image is downloading. Plain dark
            // square keeps the layout from jumping when the bitmap
            // arrives in the second post.
            collapsed.setInt(R.id.notif_thumb,
                "setBackgroundColor", Color.parseColor("#222222"))
            expanded.setInt(R.id.notif_thumb,
                "setBackgroundColor", Color.parseColor("#222222"))
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            // Setting a content title so the channel summary in
            // Settings stays useful; the custom view overrides display.
            .setContentTitle("${brandLabel(p.brand)} • ${p.pct}%")

        addAction(ctx, builder, "Listing",   p.listingUrl,   p.kind, msgIdSuffix = "L")
        addAction(ctx, builder, "Monitor",   p.monitorUrl,   p.kind, msgIdSuffix = "M")
        addAction(ctx, builder, "Catalogue", p.catalogueUrl, p.kind, msgIdSuffix = "C")

        ctx.getSystemService(NotificationManager::class.java)
            .notify(notifId, builder.build())
    }

    /** "Vinted • 56%" or "eBay • 31% • 🔨 17m" with the brand word
     * coloured + bold-italic for Vinted to evoke its branding. */
    private fun brandTitleSpannable(p: V4Payload, brandColor: Int): CharSequence {
        val brand = brandLabel(p.brand)
        val timer = if (p.isAuction && p.minsLeft != null) " • 🔨 ${p.minsLeft}m" else ""
        val tail  = " • ${p.pct}%${timer}"
        val sb = SpannableStringBuilder()
        sb.append(brand)
        sb.setSpan(ForegroundColorSpan(brandColor), 0, brand.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (p.brand == "vinted") {
            // Italic nod to Vinted's wordmark — RemoteViews supports
            // StyleSpan reliably across Android versions.
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD_ITALIC),
                0, brand.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append(tail)
        return sb
    }

    private fun setNameLine(p: V4Payload): CharSequence {
        val n = p.setNum.takeIf { it.isNotBlank() } ?: return p.setName
        val baseNum = n.substringBefore("-")
        return if (baseNum.isNotBlank() && !p.setName.contains(baseNum)) {
            "${p.setName} · $baseNum"
        } else p.setName
    }

    /** Populate one of the two grid rows. Top row = New prices, bottom
     * = Used. Each cell is its own TextView in the layout so they
     * can't wrap into each other. Per-cell % is computed app-side from
     * the market value and true cost (green ≥0, red <0). */
    private fun fillGridRow(rv: RemoteViews, p: V4Payload, top: Boolean) {
        val price    = if (top) p.asking else p.trueCost
        val priceStr = if (price < 100) "£%.2f".format(price) else "£%.0f".format(price)
        val (labA, valA) = if (top) "B N" to p.blNew else "B U" to p.blUsed
        val (labB, valB) = if (top) "E N" to p.ebNew else "E U" to p.ebUsed

        val idPrice  = if (top) R.id.notif_r1_price  else R.id.notif_r2_price
        val idCellA  = if (top) R.id.notif_r1_cell_a else R.id.notif_r2_cell_a
        val idCellB  = if (top) R.id.notif_r1_cell_b else R.id.notif_r2_cell_b

        rv.setTextViewText(idPrice, priceStr)
        rv.setTextViewText(idCellA, cellSpannable(labA, valA, p.trueCost))
        rv.setTextViewText(idCellB, cellSpannable(labB, valB, p.trueCost))
    }

    /** One cell: "B N: £60 +33%" with the % coloured. Falls back to
     * "B N: —" when the market price is missing. */
    private fun cellSpannable(lab: String, marketVal: Double,
                                trueCost: Double): CharSequence {
        if (marketVal <= 0) return "$lab: —"
        val intVal = marketVal.roundToInt()
        val pct = ((marketVal - trueCost) / marketVal * 100).roundToInt()
        val sign = if (pct >= 0) "+" else ""
        val sb = SpannableStringBuilder()
        sb.append("$lab: £$intVal ")
        val start = sb.length
        sb.append("$sign$pct%")
        val color = if (pct >= 0) Color.parseColor("#1F9D55")
                     else          Color.parseColor("#D32F2F")
        sb.setSpan(ForegroundColorSpan(color), start, sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun brandLabel(brand: String): String = when (brand) {
        "ebay"     -> "eBay"
        "vinted"   -> "Vinted"
        "facebook" -> "Facebook"
        else       -> brand.replaceFirstChar { it.uppercase() }
    }

    private fun brandColorFor(brand: String): Int = when (brand) {
        // eBay's primary brand colours rotate red/blue/yellow/green —
        // pick the red as the most recognisable single hue for the
        // wordmark.
        "ebay"     -> Color.parseColor("#E53238")
        "vinted"   -> Color.parseColor("#09B1BA")
        "facebook" -> Color.parseColor("#1877F2")
        else       -> Color.WHITE
    }

    private fun addAction(
        ctx: Context, b: NotificationCompat.Builder,
        label: String, url: String, kind: String, msgIdSuffix: String
    ) {
        if (url.isBlank()) return
        val pi = PendingIntent.getActivity(
            ctx,
            (kind + url + msgIdSuffix).hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        b.addAction(0, label, pi)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID, "Deal alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "LEGO marketplace deal notifications"
            enableVibration(true)
        }
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(ch)
    }
}
