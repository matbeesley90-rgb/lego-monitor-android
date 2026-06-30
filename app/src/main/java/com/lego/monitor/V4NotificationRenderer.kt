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
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
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

        val s = p.style
        val subtitle  = setNameLine(p)

        // Title path forks on kind:
        //   • watchlist → use the Pi's wire title verbatim ("👁 eBay •
        //     Set Name • £340"); hide the brand wordmark since the
        //     platform name is already in the title.
        //   • deal / auction → brand wordmark + RelativeSizeSpan pct.
        if (p.isWatchlist) {
            collapsed.setViewVisibility(R.id.notif_brand_logo, View.GONE)
            expanded.setViewVisibility(R.id.notif_brand_logo, View.GONE)
            collapsed.setTextViewText(R.id.notif_title_tail, p.wireTitle)
            expanded.setTextViewText(R.id.notif_title_tail, p.wireTitle)
        } else {
            val brandDrawable = brandDrawableFor(p.brand)
            val titleTail = brandTitleTailSpannable(p)
            collapsed.setViewVisibility(R.id.notif_brand_logo, View.VISIBLE)
            expanded.setViewVisibility(R.id.notif_brand_logo, View.VISIBLE)
            collapsed.setImageViewResource(R.id.notif_brand_logo, brandDrawable)
            expanded.setImageViewResource(R.id.notif_brand_logo, brandDrawable)
            collapsed.setTextViewText(R.id.notif_title_tail, titleTail)
            expanded.setTextViewText(R.id.notif_title_tail, titleTail)
        }
        collapsed.setTextViewText(R.id.notif_subtitle, subtitle)
        expanded.setTextViewText(R.id.notif_setname, subtitle)

        // Watchlist footer line — shown only for watchlist kind.
        if (p.isWatchlist && p.watchlistFooter.isNotBlank()) {
            expanded.setViewVisibility(R.id.notif_footer, View.VISIBLE)
            expanded.setTextViewText(R.id.notif_footer, p.watchlistFooter)
        } else {
            expanded.setViewVisibility(R.id.notif_footer, View.GONE)
        }

        // Apply runtime-tunable sizes (from V4Style) to every text view
        // that doesn't already get its size from a Spannable. Spans
        // (pct, timer, cell value/pct) inherit from their TextView's
        // base size — so setting the base size here scales everything
        // proportionally.
        expanded.setTextViewTextSize(R.id.notif_title_tail,
            TypedValue.COMPLEX_UNIT_SP, s.titleBaseSp)
        expanded.setTextViewTextSize(R.id.notif_setname,
            TypedValue.COMPLEX_UNIT_SP, s.setNameSp)
        expanded.setTextViewTextSize(R.id.notif_r1_price,
            TypedValue.COMPLEX_UNIT_SP, s.askingSp)
        expanded.setTextColor(R.id.notif_r1_price, s.askingColor)
        expanded.setTextViewTextSize(R.id.notif_r2_price,
            TypedValue.COMPLEX_UNIT_SP, s.trueCostSp)
        expanded.setTextColor(R.id.notif_r2_price, s.trueCostColor)
        for (divId in intArrayOf(R.id.notif_r1_div, R.id.notif_r2_div)) {
            expanded.setTextViewTextSize(divId,
                TypedValue.COMPLEX_UNIT_SP, s.dividerSp)
            expanded.setTextColor(divId, s.dividerColor)
        }
        // Per-cell text sizes (B N / E N / B U / E U each tunable).
        expanded.setTextViewTextSize(R.id.notif_r1_cell_a,
            TypedValue.COMPLEX_UNIT_SP, s.cellBN.sizeSp)
        expanded.setTextViewTextSize(R.id.notif_r1_cell_b,
            TypedValue.COMPLEX_UNIT_SP, s.cellEN.sizeSp)
        expanded.setTextViewTextSize(R.id.notif_r2_cell_a,
            TypedValue.COMPLEX_UNIT_SP, s.cellBU.sizeSp)
        expanded.setTextViewTextSize(R.id.notif_r2_cell_b,
            TypedValue.COMPLEX_UNIT_SP, s.cellEU.sizeSp)

        // Layout gaps via setViewPadding (px). dp → px conversion uses
        // the system display density. setViewPadding works since API 1,
        // unlike setViewLayoutMargin which needs API 31.
        val density = ctx.resources.displayMetrics.density
        val cellGapPx = (s.cellGapDp * density).toInt()
        val rowGapPx  = (s.rowGapDp  * density).toInt()
        // Right padding on cell_a creates the horizontal gap before cell_b.
        for (cellAId in intArrayOf(R.id.notif_r1_cell_a, R.id.notif_r2_cell_a)) {
            expanded.setViewPadding(cellAId, 0, 0, cellGapPx, 0)
        }
        // Top padding on row 2's container creates the vertical gap.
        expanded.setViewPadding(R.id.notif_row2, 0, rowGapPx, 0, 0)

        // Collapsed gets the same title scale.
        collapsed.setTextViewTextSize(R.id.notif_title_tail,
            TypedValue.COMPLEX_UNIT_SP, s.titleBaseSp)

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

    /** The text AFTER the brand wordmark — " • 56%" or
     * " • 31% • 🔨 17m". The brand wordmark itself is set as an
     * ImageView (brandDrawableFor) so it uses the actual SVG logo.
     * The percentage span is coloured green so it matches the V4
     * design ("green % next to ebay/vinted/facebook"). */
    private fun brandTitleTailSpannable(p: V4Payload): CharSequence {
        val s = p.style
        val sb = SpannableStringBuilder()
        sb.append("• ")
        val pctStart = sb.length
        sb.append("${p.pct}%")
        val pctEnd = sb.length
        // Headline pct: RelativeSizeSpan scales it above the base, colour
        // from style.
        sb.setSpan(RelativeSizeSpan(s.titlePctScale),
            pctStart, pctEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(s.titlePctColor),
            pctStart, pctEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (p.isAuction && p.minsLeft != null) {
            // Hammer emoji intentionally removed — the brand wordmark
            // already conveys "this is from eBay auctions" and the
            // timer reads cleanly without the icon.
            val timerStart = sb.length
            sb.append(" • ${p.minsLeft}m")
            if (s.titleTimerScale != 1.0f) {
                sb.setSpan(RelativeSizeSpan(s.titleTimerScale),
                    timerStart, sb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** Drawable resource for the brand's wordmark. Falls back to the
     * eBay drawable for unknown brands (extremely defensive — the V4
     * parser already coerces brand into the known set). */
    private fun brandDrawableFor(brand: String): Int = when (brand) {
        "ebay"     -> R.drawable.brand_ebay
        "vinted"   -> R.drawable.brand_vinted
        "facebook" -> R.drawable.brand_facebook
        else       -> R.drawable.brand_ebay
    }

    private fun setNameLine(p: V4Payload): CharSequence {
        val n = p.setNum.takeIf { it.isNotBlank() } ?: return p.setName
        val baseNum = n.substringBefore("-")
        return if (baseNum.isNotBlank() && !p.setName.contains(baseNum)) {
            "${p.setName} · $baseNum"
        } else p.setName
    }

    /** Populate one of the two grid rows. Top row = New prices (asking
     * shown in big blue on the left), bottom row = Used (true cost in
     * smaller amber). Per-cell % is computed app-side from the market
     * value and true cost (green ≥0, red <0). */
    private fun fillGridRow(rv: RemoteViews, p: V4Payload, top: Boolean) {
        val priceStr = if (top) {
            "£${p.asking.roundToInt()}"
        } else {
            "£%.2f".format(p.trueCost)
        }
        val (labA, valA) = if (top) "B N" to p.blNew else "B U" to p.blUsed
        val (labB, valB) = if (top) "E N" to p.ebNew else "E U" to p.ebUsed

        val idPrice  = if (top) R.id.notif_r1_price  else R.id.notif_r2_price
        val idCellA  = if (top) R.id.notif_r1_cell_a else R.id.notif_r2_cell_a
        val idCellB  = if (top) R.id.notif_r1_cell_b else R.id.notif_r2_cell_b

        val styleA = if (top) p.style.cellBN else p.style.cellBU
        val styleB = if (top) p.style.cellEN else p.style.cellEU

        rv.setTextViewText(idPrice, priceStr)
        rv.setTextViewText(idCellA, cellSpannable(labA, valA, p.trueCost, styleA))
        rv.setTextViewText(idCellB, cellSpannable(labB, valB, p.trueCost, styleB))
    }

    /** One cell rendered as a SpannableString:
     *   [grey]B N:[/] [bold white]£240[/] [green]+74%[/]
     * Three styled segments in one TextView so the cell visually
     * matches the listing-card design without needing 3 TextViews
     * per cell. */
    private fun cellSpannable(lab: String, marketVal: Double,
                                trueCost: Double,
                                cs: CellStyle): CharSequence {
        val sb = SpannableStringBuilder()

        // Label "B N:" — colour from style.
        sb.append("$lab: ")
        sb.setSpan(ForegroundColorSpan(cs.labelColor),
            0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (marketVal <= 0) {
            sb.append("—")
            return sb
        }

        // Value "£240" — bold, colour from style.
        val valStart = sb.length
        sb.append("£${marketVal.roundToInt()}")
        sb.setSpan(ForegroundColorSpan(cs.valueColor),
            valStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD),
            valStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Percentage "+74%" — bold, pos/neg colour from cell style.
        sb.append(" ")
        val pct = ((marketVal - trueCost) / marketVal * 100).roundToInt()
        val sign = if (pct >= 0) "+" else ""
        val pctStart = sb.length
        sb.append("$sign$pct%")
        val pctColor = if (pct >= 0) cs.pctPosColor else cs.pctNegColor
        sb.setSpan(ForegroundColorSpan(pctColor),
            pctStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD),
            pctStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        return sb
    }

    private fun brandLabel(brand: String): String = when (brand) {
        "ebay"     -> "eBay"
        "vinted"   -> "Vinted"
        "facebook" -> "Facebook"
        else       -> brand.replaceFirstChar { it.uppercase() }
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
