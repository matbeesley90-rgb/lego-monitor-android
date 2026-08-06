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
    // "Drop everything" deals — own channel so they sound different, not
    // just look different. See ensureChannel().
    private const val RED_CHANNEL_ID = "lego_monitor_red"
    // Deep red: strong against both One UI's warm notification shade and
    // a dark background, while leaving white body text readable.
    private const val RED_ALERT_BG = "#8B0000"
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

        // All kinds share the same title layout: brand wordmark +
        // RelativeSizeSpan'd headline pct. Watchlist prepends a 👁
        // marker so Mat can tell at a glance it's from a saved-set
        // alert vs a generic deal.
        val brandDrawable = brandDrawableFor(p.brand)
        collapsed.setImageViewResource(R.id.notif_brand_logo, brandDrawable)
        expanded.setImageViewResource(R.id.notif_brand_logo, brandDrawable)
        collapsed.setViewVisibility(R.id.notif_brand_logo, View.VISIBLE)
        expanded.setViewVisibility(R.id.notif_brand_logo, View.VISIBLE)

        val titleTail = brandTitleTailSpannable(p)
        collapsed.setTextViewText(R.id.notif_title_tail, titleTail)
        expanded.setTextViewText(R.id.notif_title_tail, titleTail)

        // Body header — seller's raw title (verbatim from the
        // marketplace). Collapsed heads-up shows the same string
        // single-line ellipsized; expanded lets it wrap to 2 lines.
        // Secondary catalogue-match line retired 2026-07-01 — the
        // seller title already contains the set number and marketing
        // name in practically every case; a second line was pure
        // noise. p.setName is retained on the payload as a defensive
        // fallback here (empty seller title → use catalogue name).
        val sellerTitle: CharSequence = p.sellerTitle.ifBlank { p.setName }
        collapsed.setTextViewText(R.id.notif_subtitle, sellerTitle)
        expanded.setTextViewText(R.id.notif_setname, sellerTitle)

        // Tier banner + footer — present only when the V4 payload
        // carries a `tier` block (Yellow / Amber). Plain Green deals
        // and auctions get no banner, no stripe, no footer.
        val tier = p.tier
        if (tier != null) {
            // Top-of-expanded banner + left-edge collapsed stripe are
            // driven from the SAME tier.bannerColor so the two surfaces
            // stay in sync.
            expanded.setViewVisibility(R.id.notif_banner, View.VISIBLE)
            expanded.setInt(R.id.notif_banner,
                "setBackgroundColor", tier.bannerColor)
            // Collapsed stripe: TOP for yellow, LEFT for amber. Two
            // dimensions (position + colour) differentiate the tiers
            // faster than colour alone against Samsung's warm
            // notification background.
            if (tier.name == "yellow") {
                collapsed.setViewVisibility(R.id.notif_collapsed_top_stripe, View.VISIBLE)
                collapsed.setInt(R.id.notif_collapsed_top_stripe,
                    "setBackgroundColor", tier.bannerColor)
                collapsed.setViewVisibility(R.id.notif_collapsed_stripe, View.GONE)
            } else {
                collapsed.setViewVisibility(R.id.notif_collapsed_stripe, View.VISIBLE)
                collapsed.setInt(R.id.notif_collapsed_stripe,
                    "setBackgroundColor", tier.bannerColor)
                collapsed.setViewVisibility(R.id.notif_collapsed_top_stripe, View.GONE)
            }
            // Tier deals keep their own text-icon; the leading footer head
            // is a bundle-only element.
            expanded.setViewVisibility(R.id.notif_footer_head, View.GONE)
            val footerParts = tier.footerParts
            if (footerParts != null) {
                // Multi-colour render: fig_sum in asking-blue, pct +
                // profit in positive-green, icon + separators + word
                // muted grey. Colours pulled from the same V4Style
                // block the price grid uses, so a tweak in the
                // Settings panel propagates.
                expanded.setViewVisibility(R.id.notif_footer_row, View.VISIBLE)
                expanded.setTextViewText(R.id.notif_footer,
                    tierFooterSpannable(footerParts, s))
            } else if (tier.footer.isNotBlank()) {
                expanded.setViewVisibility(R.id.notif_footer_row, View.VISIBLE)
                expanded.setTextViewText(R.id.notif_footer, tier.footer)
            } else {
                expanded.setViewVisibility(R.id.notif_footer_row, View.GONE)
            }
        } else {
            expanded.setViewVisibility(R.id.notif_banner, View.GONE)
            collapsed.setViewVisibility(R.id.notif_collapsed_stripe, View.GONE)
            collapsed.setViewVisibility(R.id.notif_collapsed_top_stripe, View.GONE)
            if (p.kind == "bundle" && p.bundleLine.isNotBlank()) {
                // Bundles: no tier banner/stripe, but the footer slot
                // carries the server-composed figs line. A minifig head
                // tinted to the top fig's value band leads it (replacing
                // the old orange diamond); grey when no band applies.
                expanded.setViewVisibility(R.id.notif_footer_row, View.VISIBLE)
                // Coloured per-token render when the payload carries parts
                // (total blue, profit green/red, rest grey); flat text else.
                if (p.bundleParts != null) {
                    expanded.setTextViewText(R.id.notif_footer,
                        bundleFooterSpannable(p.bundleParts, s))
                } else {
                    expanded.setTextViewText(R.id.notif_footer, p.bundleLine)
                }
                expanded.setViewVisibility(R.id.notif_footer_head, View.VISIBLE)
                val headTint = if (p.iconColor.isNotBlank()) {
                    try { Color.parseColor(p.iconColor) }
                    catch (_: Exception) { Color.parseColor("#8A8A95") }
                } else Color.parseColor("#8A8A95")
                expanded.setInt(R.id.notif_footer_head, "setColorFilter", headTint)
            } else {
                expanded.setViewVisibility(R.id.notif_footer_row, View.GONE)
            }
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

        // Per-tier accent — Samsung tints the smallIcon with this
        // colour in the stacked/grouped tray view (where our custom
        // RemoteViews are ignored). Green / Fig / auction default to
        // brand blue; Yellow / Amber pull the tier colour so a
        // multi-notification stack shows a coloured icon in the row
        // that matters. Also tints the small-icon on heads-up + lock
        // screen — consistent tier cue across every surface.
        // Fig-value head on the card title row — the noticon tinted to
        // the band colour, right after the %. Both surfaces; hidden
        // when no band applies.
        if (p.kind == "bundle") {
            // Bundle marker: stacked bricks + muted grey head, sitting
            // together (no separator) after "• £X •". Grey — not a fig-
            // value band colour — because a bundle's contents (sets or
            // figs) and their value are unknown. Both tinted the same
            // muted grey so they read as one "bundle" unit.
            val greyMarker = Color.parseColor("#8A8A95")
            for (rv in listOf(collapsed, expanded)) {
                rv.setViewVisibility(R.id.notif_bundle_bricks, View.VISIBLE)
                rv.setInt(R.id.notif_bundle_bricks, "setColorFilter", greyMarker)
                // Title-row head hidden for bundles now — the value-tinted
                // head lives on the footer fig line (Mat, 2026-07-23), so a
                // second grey head here would just be noise.
                rv.setViewVisibility(R.id.notif_fig_head, View.GONE)
            }
        } else if (p.iconColor.isNotBlank()) {
            try {
                val band = Color.parseColor(p.iconColor)
                for (rv in listOf(collapsed, expanded)) {
                    rv.setViewVisibility(R.id.notif_bundle_bricks, View.GONE)
                    rv.setViewVisibility(R.id.notif_fig_head, View.VISIBLE)
                    rv.setInt(R.id.notif_fig_head, "setColorFilter", band)
                }
            } catch (_: Exception) {}
        } else {
            for (rv in listOf(collapsed, expanded)) {
                rv.setViewVisibility(R.id.notif_bundle_bricks, View.GONE)
                rv.setViewVisibility(R.id.notif_fig_head, View.GONE)
            }
        }

        // Priority: server-sent icon_color (max-fig-value band, matching
        // the monitor card border: gold >=£50, red >=£30, orange >=£20,
        // green >=£10) > tier colour > brand blue. The head silhouette
        // in the status bar becomes a value cue for the best fig in the
        // set at a glance.
        val accent = when {
            p.iconColor.isNotBlank() -> try {
                Color.parseColor(p.iconColor)
            } catch (_: Exception) {
                Color.parseColor("#3B98E0")
            }
            tier?.name == "yellow" -> tier.bannerColor
            tier?.name == "amber"  -> tier.bannerColor
            else -> Color.parseColor("#3B98E0")  // brand blue
        }

        // RED ALERT — paint the whole body, not just an accent. The
        // server decides this (see _is_red_alert in notifications.py);
        // the app only renders it, so thresholds retune without an APK.
        // Both layout roots are painted because the collapsed view is
        // what shows on the lock screen and in the heads-up popup.
        if (p.redAlert) {
            val redBg = Color.parseColor(RED_ALERT_BG)
            collapsed.setInt(R.id.notif_root_collapsed,
                             "setBackgroundColor", redBg)
            expanded.setInt(R.id.notif_root_expanded,
                            "setBackgroundColor", redBg)
        }

        val builder = NotificationCompat.Builder(
                ctx, if (p.redAlert) RED_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_head)
            .setColor(if (p.redAlert) Color.parseColor(RED_ALERT_BG)
                      else accent)
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
        // Asking price on the title line — the number that decides whether a
        // deal is worth opening. Kept here (not just in the price grid below)
        // so it survives the collapsed / stacked tray view, where the grid is
        // hidden. Blue to match the card headline; rounded so title and grid
        // agree (fillGridRow uses the same asking.roundToInt()).
        val priceStart = sb.length
        sb.append("£${p.asking.roundToInt()}")
        sb.setSpan(ForegroundColorSpan(Color.parseColor("#3B98E0")),
            priceStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Bundles have no meaningful headline % (nothing to compare
        // against, and no known fig value). Instead of a pct the title
        // row carries the stacked-bricks marker + a muted grey head —
        // both ImageViews, set VISIBLE in post(). Here we just close the
        // text with a trailing "•" so it reads "[brand] • £19 •" and the
        // bricks+head pair sit right after it as one "bundle" unit.
        if (p.kind == "bundle") {
            sb.append(" •")
            return sb
        }
        sb.append(" • ")
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
        // Mat's spec: the head is the last element on the row, preceded
        // by the same plain "•" separator as everything else —
        // "ebay • 70% • [head]" / "ebay • 70% • 5m • [head]". Only
        // appended when a band applies (head hidden otherwise).
        if (p.iconColor.isNotBlank()) {
            sb.append(" •")
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

    /** Bundle footer rendered as a SpannableString (Mat, 2026-07-23):
     *   [blue bold]{total}[/] [grey] • {figs}   {avg} • [/]
     *   [green|red bold]{pct} • {profit}[/] [amber] {warn}[/]
     * Total in asking-blue; the pct+profit group in positive-green or
     * negative-red per `positive`; a wide gap between fig-count and average;
     * everything else muted grey. Colours track V4Style so a Settings tweak
     * propagates. */
    private fun bundleFooterSpannable(b: BundleParts, s: V4Style): CharSequence {
        val grey  = Color.parseColor("#9AA0A6")
        val blue  = s.askingColor
        val money = if (b.positive) s.cellBN.pctPosColor else s.cellBN.pctNegColor
        val amber = Color.parseColor("#E0A53B")
        val bold  = android.graphics.Typeface.BOLD

        val sb = SpannableStringBuilder()
        fun run(text: String, color: Int, boldRun: Boolean = false) {
            if (text.isEmpty()) return
            val st = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(color), st, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (boldRun) sb.setSpan(StyleSpan(bold), st, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        run(b.total, blue, true)                 // £142  (blue, bold)
        run(" • ", grey)
        run(b.figs, grey)                        // 26 figs
        run("   ", grey)                         // the requested gap
        run(b.avg, grey)                         // x̄£5.5
        run(" • ", grey)
        run("${b.pct} • ${b.profit}", money, true)   // +103% • £72 (green/red)
        if (b.auction.isNotBlank()) {
            // Auction bundles carry a hammer + time remaining, in the
            // tier-amber so the countdown reads as urgent.
            run(" \u2022 ", grey)
            run("\uD83D\uDD28 " + b.auction, amber, true)
        }
        if (b.warn.isNotBlank()) {
            run("  ", grey)
            run(b.warn, amber, true)             // ⚠2
        }
        return sb
    }

    /** Yellow / Amber tier footer rendered as a SpannableString:
     *   [grey]{icon}[/] [blue]{fig_sum}[/] [grey] • [/] [green bold]{pct} {profit}[/] [grey] {profit_word}[/]
     * Blue matches the asking-price colour (top-left of the price
     * grid) and green matches the positive-% colour (per-cell +N%),
     * both pulled from V4Style so they track any Settings-panel
     * tweak. */
    private fun tierFooterSpannable(parts: FooterParts, s: V4Style): CharSequence {
        val greyColor = Color.parseColor("#9AA0A6")
        val blueColor = s.askingColor
        // The four per-cell CellStyles all default to the same green,
        // and users tweaking one cell's green rarely leave the others
        // out of sync — take BN's positive-% colour as the canonical.
        val greenColor = s.cellBN.pctPosColor

        val sb = SpannableStringBuilder()

        // Icon (grey)
        val iconStart = sb.length
        sb.append(parts.icon).append(" ")
        sb.setSpan(ForegroundColorSpan(greyColor),
            iconStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Fig sum (blue, bold)
        val figStart = sb.length
        sb.append(parts.figSum)
        sb.setSpan(ForegroundColorSpan(blueColor),
            figStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD),
            figStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Separator (grey)
        val sepStart = sb.length
        sb.append(" • ")
        sb.setSpan(ForegroundColorSpan(greyColor),
            sepStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Pct + profit £ (green, bold) — kept together so a single
        // green run reads as one continuous number-group.
        val greenStart = sb.length
        sb.append(parts.pct).append(" ").append(parts.profit)
        sb.setSpan(ForegroundColorSpan(greenColor),
            greenStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD),
            greenStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Trailing " profit" word (grey)
        if (parts.profitWord.isNotBlank()) {
            val wordStart = sb.length
            sb.append(" ").append(parts.profitWord)
            sb.setSpan(ForegroundColorSpan(greyColor),
                wordStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return sb
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
        // Directional formula — see notifications.py:fmt_pct for the
        // rationale. Prevents alarming −241% inversions when the
        // seller's price exceeds the reference.
        sb.append(" ")
        val pct = if (trueCost <= marketVal) {
            ((marketVal - trueCost) / marketVal * 100).roundToInt()
        } else {
            (-(trueCost - marketVal) / trueCost * 100).roundToInt()
        }
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
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID, "Deal alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "LEGO marketplace deal notifications"
            enableVibration(true)
        }
        mgr.createNotificationChannel(ch)

        // SEPARATE CHANNEL for "drop everything" deals. Colour only helps
        // if you happen to be looking at the screen — a red notification
        // in a pocket is identical to a grey one. Its own channel gives
        // it a distinct sound and a heavier vibration, and (Android 13+)
        // lets the user grant it DND bypass in Settings while ordinary
        // deals stay quiet. Measured to fire ~1.9x/day at the default
        // thresholds; keeping it rare is what keeps it meaningful.
        val red = NotificationChannel(
            RED_CHANNEL_ID, "Definite buys",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description =
                "Exceptional deals only — high profit or a very cheap " +
                "high-value bundle. Rare by design (~2/day)."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 400)
            enableLights(true)
            lightColor = Color.parseColor(RED_ALERT_BG)
            setBypassDnd(true)
        }
        mgr.createNotificationChannel(red)
    }
}
