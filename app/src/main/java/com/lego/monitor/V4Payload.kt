package com.lego.monitor

import org.json.JSONObject

/**
 * Phase 2 structured payload. Schema lives at
 * docs/v4-payload-schema.md — Pi side and app must stay in sync.
 *
 * Parsing intentionally tolerant: missing optional fields default to
 * empty/zero, and any malformed JSON causes [tryParse] to return null
 * so the caller can fall back to plain-text rendering (Phase 1
 * behaviour).
 */
data class V4Payload(
    val kind: String,           // "deal" | "auction" (watchlist retired)
    val brand: String,          // "ebay" | "vinted" | "facebook"
    // Primary body header — the seller's raw marketplace title
    // (was `set_name` mislabelled before 2026-07-01 when a Darth
    // Vader Transformation catalogue match shadowed an Anakin
    // listing). Wraps to 2 lines on the phone.
    val sellerTitle: String,
    // Catalogue name (bl_name). May be empty when no catalogue match
    // — the renderer hides the secondary line in that case. Set
    // number below is the discriminator on the same secondary line.
    val setName: String,
    val setNum: String,
    val pct: Int,
    val asking: Double,
    val trueCost: Double,
    val blNew: Double,
    val blUsed: Double,
    val ebNew: Double,
    val ebUsed: Double,
    val imageUrl: String,
    val listingUrl: String,
    val monitorUrl: String,
    val catalogueUrl: String,
    // Auction-only — null for buy-now deals
    val minsLeft: Int? = null,
    val endIso: String? = null,
    // Runtime-tunable styling from the Pi's notification_style_json
    // config; defaults match the baked-in look when absent or partial.
    val style: V4Style = V4Style.DEFAULT,
    // Tier metadata (Yellow / Amber). Null when the payload is a plain
    // Green deal or an auction — neither shows the banner stripe.
    val tier: TierInfo? = null,

    // Bundles only — server-composed figs summary rendered in the
    // footer slot (e.g. "8 figs • £1.01/fig", "~£35 in 10 figs (1.7x)").
    val bundleLine: String = "",

    // Max-fig-value band colour (hex) — tints the status-bar head to
    // match the monitor card border for the priciest fig in the set.
    val iconColor: String = "",

    // "Drop everything" deal. The SERVER decides this (thresholds live in
    // Pi config: red_alert_min_profit / red_alert_bundle_value /
    // red_alert_bundle_ratio) so it can be retuned without a new APK.
    // Renders the whole notification red and routes it to a separate
    // high-importance channel with its own sound.
    val redAlert: Boolean = false,

    // Bundles only — per-token version of bundleLine so the renderer can
    // colour total (blue), profit (green/red), rest (grey). Null → render
    // the flat bundleLine.
    val bundleParts: BundleParts? = null,
) {
    val isAuction: Boolean get() = kind == "auction"

    companion object {
        /** Try to parse `messageField` as a V4 JSON payload. Returns
         * null on any failure or version mismatch — caller treats as
         * plain text. */
        fun tryParse(messageField: String?): V4Payload? {
            val raw = messageField ?: return null
            if (raw.isBlank() || !raw.trimStart().startsWith("{")) return null
            return try {
                val o = JSONObject(raw)
                if (o.optInt("v", 0) != 4) return null
                val auc = o.optJSONObject("_auction_only")
                V4Payload(
                    kind         = o.optString("kind", "deal"),
                    brand        = o.optString("brand", "ebay"),
                    sellerTitle  = o.optString("seller_title", ""),
                    setName      = o.optString("set_name", ""),
                    setNum       = o.optString("set_num", ""),
                    pct          = o.optInt("pct", 0),
                    asking       = o.optDouble("asking", 0.0),
                    trueCost     = o.optDouble("true_cost", 0.0),
                    blNew        = o.optDouble("bl_new", 0.0),
                    blUsed       = o.optDouble("bl_used", 0.0),
                    ebNew        = o.optDouble("eb_new", 0.0),
                    ebUsed       = o.optDouble("eb_used", 0.0),
                    imageUrl     = o.optString("image_url", ""),
                    listingUrl   = o.optString("listing_url", ""),
                    monitorUrl   = o.optString("monitor_url", ""),
                    catalogueUrl = o.optString("catalogue_url", ""),
                    minsLeft     = auc?.optInt("mins_left"),
                    endIso       = auc?.optString("end_iso"),
                    style        = V4Style.parse(o.optJSONObject("style")),
                    tier         = TierInfo.parse(o.optJSONObject("tier")),
                    bundleLine   = o.optString("bundle_line", ""),
                    iconColor    = o.optString("icon_color", ""),
                    redAlert     = o.optBoolean("red_alert", false),
                    bundleParts  = BundleParts.parse(o.optJSONObject("bundle_parts")),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Per-token bundle footer — lets the renderer colour each piece:
 *   [blue]{total}[/] • [grey]{figs}   {avg}[/] • [green|red]{pct} • {profit}[/] [amber]{warn}[/]
 * `positive` picks green (profit) vs red (loss) for the pct+profit group.
 */
data class BundleParts(
    val total: String,
    val figs: String,
    val avg: String,
    val pct: String,
    val profit: String,
    val positive: Boolean,
    val warn: String,
    // Auction bundles: "3h 18m" / "5m" / "auction". Blank for buy-now.
    val auction: String = "",
) {
    companion object {
        fun parse(o: org.json.JSONObject?): BundleParts? {
            if (o == null) return null
            val total = o.optString("total", "")
            if (total.isBlank()) return null
            return BundleParts(
                total    = total,
                figs     = o.optString("figs", ""),
                avg      = o.optString("avg", ""),
                pct      = o.optString("pct", ""),
                profit   = o.optString("profit", ""),
                positive = o.optBoolean("positive", true),
                warn     = o.optString("warn", ""),
                auction  = o.optString("auction", ""),
            )
        }
    }
}

/**
 * Tier 3 metadata (System 3) — present in V4 payloads for Yellow /
 * Amber tier deals. Null on plain Green deals + auctions.
 *
 *   name          "yellow" | "amber"
 *   bannerColor   ARGB int parsed from "#RRGGBB" — runtime-applied
 *                 to the 6dp notif_banner stripe at the top of the
 *                 expanded layout.
 *   footer        Pre-formatted body line (e.g. "🟡 £142 • +69%
 *                 £58 profit"). Rendered in notif_footer below the
 *                 grid.
 */
data class TierInfo(
    val name: String,
    val bannerColor: Int,
    val footer: String,
    val footerParts: FooterParts?,   // preferred over `footer` when present
) {
    companion object {
        fun parse(o: org.json.JSONObject?): TierInfo? {
            if (o == null) return null
            val name = o.optString("name", "")
            if (name.isBlank()) return null
            val colorStr = o.optString("banner_color", "")
            val color = if (colorStr.isBlank()) android.graphics.Color.GRAY
                        else try { android.graphics.Color.parseColor(colorStr) }
                             catch (_: Exception) { android.graphics.Color.GRAY }
            return TierInfo(
                name        = name,
                bannerColor = color,
                footer      = o.optString("footer", ""),
                footerParts = FooterParts.parse(o.optJSONObject("footer_parts")),
            )
        }
    }
}

/**
 * Split-out numeric segments for the Yellow/Amber footer line so the
 * renderer can colour them independently (fig_sum in asking-blue,
 * pct + profit in positive-green, everything else muted grey).
 */
data class FooterParts(
    val icon: String,
    val figSum: String,
    val pct: String,
    val profit: String,
    val profitWord: String,
) {
    companion object {
        fun parse(o: org.json.JSONObject?): FooterParts? {
            if (o == null) return null
            val icon = o.optString("icon", "")
            val figSum = o.optString("fig_sum", "")
            val pct = o.optString("pct", "")
            val profit = o.optString("profit", "")
            val word = o.optString("profit_word", "")
            // If any of the numeric parts is missing, treat the whole
            // block as absent — caller falls back to the flat text.
            if (figSum.isBlank() || pct.isBlank() || profit.isBlank()) return null
            return FooterParts(icon, figSum, pct, profit, word)
        }
    }
}
