package com.lego.monitor

import android.graphics.Color
import org.json.JSONObject

/**
 * Runtime-tunable styling for V4 notifications. Pi includes a `style`
 * block in the V4 JSON payload; this class parses it (tolerantly —
 * any missing field falls back to the [Defaults] value, matching the
 * baked-in look the app shipped with).
 *
 * Lets us tweak sizes / colours from the Pi's web UI without rebuilding
 * + re-installing the APK for every cosmetic change. Layout structure,
 * fonts, and the brand wordmark drawables stay baked-in.
 */
data class V4Style(
    val titleBaseSp: Float,
    val titlePctScale: Float,
    val titlePctColor: Int,
    val titleTimerScale: Float,
    val brandLogoDp: Int,

    val askingSp: Float,
    val askingColor: Int,
    val trueCostSp: Float,
    val trueCostColor: Int,

    val dividerSp: Float,
    val dividerColor: Int,

    val cellSp: Float,
    val cellLabelColor: Int,
    val cellValueColor: Int,
    val cellPosColor: Int,
    val cellNegColor: Int,

    val setNameSp: Float,
) {
    companion object Defaults {
        // Defaults intentionally match the current baked-in layout
        // values — see notification_expanded.xml + V4NotificationRenderer.
        val DEFAULT = V4Style(
            titleBaseSp     = 15f,
            titlePctScale   = 1.6f,
            titlePctColor   = Color.parseColor("#1F9D55"),
            titleTimerScale = 1.0f,
            brandLogoDp     = 22,

            askingSp    = 18f,
            askingColor = Color.parseColor("#3B98E0"),
            trueCostSp  = 13f,
            trueCostColor = Color.parseColor("#F5AF02"),

            dividerSp    = 14f,
            dividerColor = Color.parseColor("#555555"),

            cellSp         = 11f,
            cellLabelColor = Color.parseColor("#9AA0A6"),
            cellValueColor = Color.WHITE,
            cellPosColor   = Color.parseColor("#22C55E"),
            cellNegColor   = Color.parseColor("#EF4444"),

            setNameSp = 13f,
        )

        /** Read a `style` block from the V4 payload JSON. Any missing
         * sub-block or field falls back to [DEFAULT]. Tolerant of
         * malformed values — parse errors per field default silently. */
        fun parse(root: JSONObject?): V4Style {
            if (root == null) return DEFAULT
            val title    = root.optJSONObject("title")
            val asking   = root.optJSONObject("asking")
            val trueCost = root.optJSONObject("true_cost")
            val divider  = root.optJSONObject("divider")
            val cell     = root.optJSONObject("cell")
            val setName  = root.optJSONObject("set_name")
            val brand    = root.optJSONObject("brand")

            fun obj(o: JSONObject?, k: String, fb: Float) =
                o?.optDouble(k, fb.toDouble())?.toFloat() ?: fb
            fun objInt(o: JSONObject?, k: String, fb: Int) =
                o?.optInt(k, fb) ?: fb
            fun objColor(o: JSONObject?, k: String, fb: Int): Int {
                val s = o?.optString(k, "") ?: ""
                if (s.isBlank()) return fb
                return try { Color.parseColor(s) } catch (_: Exception) { fb }
            }

            return V4Style(
                titleBaseSp     = obj(title, "base_sp",     DEFAULT.titleBaseSp),
                titlePctScale   = obj(title, "pct_scale",   DEFAULT.titlePctScale),
                titlePctColor   = objColor(title, "pct_color", DEFAULT.titlePctColor),
                titleTimerScale = obj(title, "timer_scale", DEFAULT.titleTimerScale),
                brandLogoDp     = objInt(brand, "logo_dp",  DEFAULT.brandLogoDp),

                askingSp    = obj(asking,   "size_sp", DEFAULT.askingSp),
                askingColor = objColor(asking, "color", DEFAULT.askingColor),
                trueCostSp  = obj(trueCost, "size_sp", DEFAULT.trueCostSp),
                trueCostColor = objColor(trueCost, "color", DEFAULT.trueCostColor),

                dividerSp    = obj(divider, "size_sp", DEFAULT.dividerSp),
                dividerColor = objColor(divider, "color", DEFAULT.dividerColor),

                cellSp         = obj(cell, "size_sp", DEFAULT.cellSp),
                cellLabelColor = objColor(cell, "label_color", DEFAULT.cellLabelColor),
                cellValueColor = objColor(cell, "value_color", DEFAULT.cellValueColor),
                cellPosColor   = objColor(cell, "pct_pos_color", DEFAULT.cellPosColor),
                cellNegColor   = objColor(cell, "pct_neg_color", DEFAULT.cellNegColor),

                setNameSp = obj(setName, "size_sp", DEFAULT.setNameSp),
            )
        }
    }
}
