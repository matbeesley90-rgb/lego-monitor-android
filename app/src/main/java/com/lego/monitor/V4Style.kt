package com.lego.monitor

import android.graphics.Color
import org.json.JSONObject

/**
 * Per-cell style. Each of the four grid cells (B N / E N / B U / E U)
 * resolves to one of these — either via its `cells.{key}` override or
 * by inheriting from the shared `cell.*` block in the V4 payload.
 */
data class CellStyle(
    val sizeSp: Float,
    val labelColor: Int,
    val valueColor: Int,
    val pctPosColor: Int,
    val pctNegColor: Int,
)

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

    // Per-cell styles — already-resolved (override merged into defaults)
    // so the renderer doesn't have to walk back to the shared `cell`
    // block.
    val cellBN: CellStyle,
    val cellEN: CellStyle,
    val cellBU: CellStyle,
    val cellEU: CellStyle,

    val setNameSp: Float,
    val cellGapDp: Int,      // horizontal gap between cell_a and cell_b
    val rowGapDp: Int,       // vertical gap between grid row 1 and row 2
) {
    companion object Defaults {
        val CELL_DEFAULT = CellStyle(
            sizeSp      = 11f,
            labelColor  = Color.parseColor("#9AA0A6"),
            valueColor  = Color.WHITE,
            pctPosColor = Color.parseColor("#22C55E"),
            pctNegColor = Color.parseColor("#EF4444"),
        )

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

            cellBN = CELL_DEFAULT,
            cellEN = CELL_DEFAULT,
            cellBU = CELL_DEFAULT,
            cellEU = CELL_DEFAULT,

            setNameSp = 13f,
            cellGapDp = 6,
            rowGapDp  = 2,
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
            val cells    = root.optJSONObject("cells")
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

            // Cell defaults come from the shared `cell.*` block (backward
            // compat with style JSON saved before per-cell overrides
            // existed) falling through to CELL_DEFAULT.
            val cellDefaults = CellStyle(
                sizeSp      = obj(cell, "size_sp",       CELL_DEFAULT.sizeSp),
                labelColor  = objColor(cell, "label_color",   CELL_DEFAULT.labelColor),
                valueColor  = objColor(cell, "value_color",   CELL_DEFAULT.valueColor),
                pctPosColor = objColor(cell, "pct_pos_color", CELL_DEFAULT.pctPosColor),
                pctNegColor = objColor(cell, "pct_neg_color", CELL_DEFAULT.pctNegColor),
            )

            // Per-cell overrides under `cells.{bn,en,bu,eu}` — each
            // field falls back to cellDefaults if absent so partial
            // overrides work cleanly.
            fun parseCell(key: String): CellStyle {
                val o = cells?.optJSONObject(key)
                return CellStyle(
                    sizeSp      = obj(o, "size_sp",       cellDefaults.sizeSp),
                    labelColor  = objColor(o, "label_color",   cellDefaults.labelColor),
                    valueColor  = objColor(o, "value_color",   cellDefaults.valueColor),
                    pctPosColor = objColor(o, "pct_pos_color", cellDefaults.pctPosColor),
                    pctNegColor = objColor(o, "pct_neg_color", cellDefaults.pctNegColor),
                )
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

                cellBN = parseCell("bn"),
                cellEN = parseCell("en"),
                cellBU = parseCell("bu"),
                cellEU = parseCell("eu"),

                setNameSp = obj(setName, "size_sp", DEFAULT.setNameSp),
                cellGapDp = objInt(cell, "gap_dp",       DEFAULT.cellGapDp),
                rowGapDp  = objInt(root.optJSONObject("row"), "gap_dp", DEFAULT.rowGapDp),
            )
        }
    }
}
