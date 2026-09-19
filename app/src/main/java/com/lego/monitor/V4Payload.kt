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
    // Option D (2026-09-11): whole-body colour by profit band (red/green/amber/"")
    // and the collapsed row's profit text ("🔥 +369%", "+85%", "checking…").
    val band: String = "",
    val bundleTail: String = "",
    // Option B (2026-09-14): per-band figure counts. bands = all, bandsFit =
    // the ones that fit the collapsed row, total = whole lot (n, gbp, counted).
    val figBands: FigBands? = null,

    // Bundles only — per-token version of bundleLine so the renderer can
    // colour total (blue), profit (green/red), rest (grey). Null → render
    // the flat bundleLine.
    val bundleParts: BundleParts? = null,

    // Stable per-listing key. When present, the notification ID derives
    // from THIS instead of the ntfy message id, so a follow-up push for
    // the same listing (⚡ flash → 🧮 appraisal → 🔥 hot) REPLACES the
    // displayed card in place instead of stacking a second notification.
    val replaceKey: String = "",

    // True on follow-up appraisal cards — post silently (no sound or
    // vibration); the card content just morphs.
    val isUpdate: Boolean = false,

    // True = RETRACT: remove the notification with this replaceKey and
    // render nothing. Sent when a flash card's verified verdict is
    // "nothing here" (packaging artwork, unwanted theme).
    val isCancel: Boolean = false,

    // ── v0.2 (2026-09-08): grail tab, info face, photo tap ──────────
    // Listing id + the Pi's API base (over Tailscale) — the info sheet
    // fetches /api/listing/<id>/info from these.
    val listingId: String = "",
    val apiBase: String = "",
    // Info-face state, decided server-side to match the dashboard card:
    // "" neutral | "adj" refs adjusted | "vis" vision ran | "good" ratio ≥ 1.5
    val face: String = "",
    // Un-cropped, proxied listing photo for the full-screen viewer.
    val photoUrl: String = "",
    // 🎯 Grail block — present only when the matched set/fig is on Mat's
    // want list at a real discount. Drives the violet banner + tab.
    val grail: GrailInfo? = null,
    // In-card info block (2026-09-09): the sheet's facts, pre-rendered by
    // the Pi so the card can redraw in place with no network at tap time.
    val infoCard: InfoCard? = null,
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
                    band         = o.optString("band", ""),
                    bundleTail   = o.optString("bundle_tail", ""),
                    figBands     = FigBands.parse(o.optJSONObject("fig_bands")),
                    bundleParts  = BundleParts.parse(o.optJSONObject("bundle_parts")),
                    replaceKey   = o.optString("replace_key", ""),
                    isUpdate     = o.optBoolean("update", false),
                    isCancel     = o.optBoolean("cancel", false),
                    listingId    = o.optString("listing_id", ""),
                    apiBase      = o.optString("api_base", ""),
                    face         = o.optString("face", ""),
                    photoUrl     = o.optString("photo_url", ""),
                    grail        = GrailInfo.parse(o.optJSONObject("grail")),
                    infoCard     = InfoCard.parse(o.optJSONObject("info_card")),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 🎯 Grail block (2026-09-08). The card shows only the violet banner and
 * the tab; the numbers live in the info sheet. `catalogueUrl` is the
 * GRAIL's own set/fig page (the tab's link). `raw` is carried to the
 * sheet as-is so it can render the gold line without re-parsing.
 */
data class GrailInfo(
    val num: String,
    val name: String,
    val ref: Double,
    val asking: Double,
    val pctUnder: Int,
    val catalogueUrl: String,
    val raw: String,
) {
    companion object {
        fun parse(o: org.json.JSONObject?): GrailInfo? {
            if (o == null) return null
            val num = o.optString("num", "")
            if (num.isBlank()) return null
            return GrailInfo(
                num          = num,
                name         = o.optString("name", num),
                ref          = o.optDouble("ref", 0.0),
                asking       = o.optDouble("asking", 0.0),
                pctUnder     = o.optInt("pct_under", 0),
                catalogueUrl = o.optString("catalogue_url", ""),
                raw          = o.toString(),
            )
        }
    }
}

/**
 * In-card info block (2026-09-09) — the dashboard ⓘ sheet's facts as
 * ready-to-draw strings. `table` rows are [label, complete, asListed]
 * with cells like "£152 +74%"; `figs` rows are [num, name, used].
 * Kinds ("warn" | "ok" | "" ) pick the line colour.
 */
data class InfoCard(
    val setLine: String,
    val setUrl: String,
    val line1: String,
    val line1Kind: String,
    val line2: String,
    val line2Kind: String,
    val tableHead: List<String>,
    val table: List<List<String>>,
    val figsLine: String,
    val figs: List<List<String>>,
    val visionLine: String,
    val visionKind: String,
    // "Seller says" quick card (2026-09-09): short facts [text, kind] and
    // up to three of the seller's own sentences. saysKind colours the icon.
    val facts: List<List<String>> = emptyList(),
    val quotes: List<String> = emptyList(),
    val saysKind: String = "",
    // DEAL CHECK (2026-09-09): fixed rows [label, value, kind] — Condition,
    // Value, Figs, You pay, Seller, Vision, Grail. checkKind colours the icon.
    val rows: List<List<String>> = emptyList(),
    val checkKind: String = "",
) {
    companion object {
        private fun strings(a: org.json.JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.optString(it, "") }
        private fun rows(a: org.json.JSONArray?): List<List<String>> =
            if (a == null) emptyList() else (0 until a.length()).map { strings(a.optJSONArray(it)) }
        fun parse(o: org.json.JSONObject?): InfoCard? {
            if (o == null) return null
            val setLine = o.optString("set_line", "")
            if (setLine.isBlank()) return null
            return InfoCard(
                setLine    = setLine,
                setUrl     = o.optString("set_url", ""),
                line1      = o.optString("line1", ""),
                line1Kind  = o.optString("line1_kind", ""),
                line2      = o.optString("line2", ""),
                line2Kind  = o.optString("line2_kind", ""),
                tableHead  = strings(o.optJSONArray("table_head")),
                table      = rows(o.optJSONArray("table")),
                figsLine   = o.optString("figs_line", ""),
                figs       = rows(o.optJSONArray("figs")),
                visionLine = o.optString("vision_line", ""),
                visionKind = o.optString("vision_kind", ""),
                facts      = rows(o.optJSONArray("facts")),
                quotes     = strings(o.optJSONArray("quotes")),
                saysKind   = o.optString("says_kind", ""),
                rows       = rows(o.optJSONArray("rows")),
                checkKind  = o.optString("check_kind", ""),
            )
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

/** One band of figures on the card: colour (hex, or "ghost" for
 * unidentified) and how many. */
data class FigBand(val color: String, val n: Int)

data class FigBands(val bands: List<FigBand>, val bandsFit: List<FigBand>,
                    val totalN: Int, val totalGbp: Int?, val counted: Boolean) {
    companion object {
        fun parse(o: org.json.JSONObject?): FigBands? {
            if (o == null) return null
            fun list(a: org.json.JSONArray?): List<FigBand> {
                val out = ArrayList<FigBand>()
                if (a == null) return out
                for (i in 0 until a.length()) {
                    val b = a.optJSONObject(i) ?: continue
                    out.add(FigBand(b.optString("color", ""), b.optInt("n", 0)))
                }
                return out
            }
            val t = o.optJSONObject("total")
            val gbp = if (t != null && !t.isNull("gbp")) t.optInt("gbp") else null
            return FigBands(list(o.optJSONArray("bands")), list(o.optJSONArray("bands_fit")),
                            t?.optInt("n") ?: 0, gbp, t?.optBoolean("counted") ?: false)
        }
    }
}
