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
    val kind: String,           // "deal" | "auction" | "watchlist"
    val brand: String,          // "ebay" | "vinted" | "facebook"
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
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
