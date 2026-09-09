package com.lego.monitor

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The dashboard card's ⓘ sheet, native (2026-09-08). Opened by the info
 * face on a notification; reads the SAME data the web sheet uses
 * (`GET /api/listing/<id>/info` over Tailscale) and draws it with plain
 * widgets — no WebView, no browser. Vision runs from here too: POST
 * /api/vision/run then poll the info endpoint until the verdict lands.
 *
 * Sections mirror templates/index.html openInfoSheet(): header, grail
 * line (if any), Reference notes, Vision, Seller's description, and the
 * duotone action row (Like / Vision / Bought / Save / Dismiss).
 */
class InfoSheetActivity : AppCompatActivity() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val ui = Handler(Looper.getMainLooper())

    private var listingId = ""
    private var apiBase = ""
    private var sellerTitle = ""
    private var brand = ""
    private var asking = 0.0
    private var trueCost = 0.0
    private var listingUrl = ""
    private var monitorUrl = ""
    private var grail: JSONObject? = null
    // "vision": the bottom sheet opened from the in-card info block —
    // header + the Vision section only (2026-09-09, Mat: "the vision
    // should open up from the bottom in a separate window").
    private var visionOnly = false

    private lateinit var sections: LinearLayout
    private lateinit var statusLine: TextView
    private var info: JSONObject? = null
    private var visionPolls = 0

    // Palette — matches the dashboard sheet.
    private val cBg = Color.parseColor("#1F2024")
    private val cLine = Color.parseColor("#33383F")
    private val cInk = Color.parseColor("#F2F2F2")
    private val cSub = Color.parseColor("#9AA0A6")
    private val cDim = Color.parseColor("#6A6F75")
    private val cBlue = Color.parseColor("#4A9EFF")
    private val cWarn = Color.parseColor("#F2B35A")
    private val cGreen = Color.parseColor("#4CC38A")
    private val cRed = Color.parseColor("#E06060")
    private val cViolet = Color.parseColor("#8B6CFF")
    private val cAmber = Color.parseColor("#E0A63A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listingId   = intent.getStringExtra(EXTRA_LISTING_ID).orEmpty()
        apiBase     = intent.getStringExtra(EXTRA_API_BASE).orEmpty().trimEnd('/')
        sellerTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        brand       = intent.getStringExtra(EXTRA_BRAND).orEmpty()
        asking      = intent.getDoubleExtra(EXTRA_ASKING, 0.0)
        trueCost    = intent.getDoubleExtra(EXTRA_TRUE_COST, 0.0)
        listingUrl  = intent.getStringExtra(EXTRA_LISTING_URL).orEmpty()
        monitorUrl  = intent.getStringExtra(EXTRA_MONITOR_URL).orEmpty()
        grail = intent.getStringExtra(EXTRA_GRAIL_JSON)?.takeIf { it.isNotBlank() }
            ?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        visionOnly = intent.getStringExtra(EXTRA_MODE) == MODE_VISION

        setContentView(buildShell())
        if (listingId.isBlank() || apiBase.isBlank()) {
            statusLine.text = "This card carries no listing id — update the Pi."
            return
        }
        load()
    }

    // ── shell ────────────────────────────────────────────────────────

    private fun buildShell(): View {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { finish() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cBg)
                cornerRadii = floatArrayOf(dpF(24), dpF(24), dpF(24), dpF(24), 0f, 0f, 0f, 0f)
            }
            setPadding(dp(18), dp(10), dp(18), dp(18) + navBarInset())
            isClickable = true   // swallow taps so the scrim doesn't close
        }
        // Grab handle
        panel.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#4A4B50")); cornerRadius = dpF(2) }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })

        // Header
        panel.addView(text(sellerTitle.ifBlank { "Listing" }, 15f, cInk, bold = true).apply { maxLines = 3 })
        val sub = buildString {
            if (brand.isNotBlank()) append(brand)
            if (asking > 0) append(" · £${money(asking)} asking")
            if (trueCost > 0) append(" · £${"%.2f".format(trueCost)} all in")
        }.trim(' ', '·')
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headRow.addView(text(sub, 13f, cSub).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        if (listingUrl.isNotBlank()) headRow.addView(text("Open listing ↗", 13f, cBlue).apply {
            setPadding(dp(10), dp(6), 0, dp(6))
            setOnClickListener { openListing() }
        })
        panel.addView(headRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })

        // Grail line
        grail?.let { g ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1F8B6CFF")); cornerRadius = dpF(12)
                    setStroke(dp(1), Color.parseColor("#598B6CFF"))
                }
                setPadding(dp(11), dp(9), dp(11), dp(9))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_grail_brick); setColorFilter(cViolet)
                }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) })
                val ref = g.optDouble("ref", 0.0); val ask = g.optDouble("asking", asking)
                addView(text("Grail · ${g.optString("name", g.optString("num"))} · £${money(ask)} vs £${money(ref)} market · ${g.optInt("pct_under", 0)}% under",
                    13f, cViolet, bold = true))
                val cat = g.optString("catalogue_url", "")
                if (cat.isNotBlank()) setOnClickListener { openInMonitor(cat) }
            }
            panel.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }

        // Action row (duotone icons ported from the dashboard sheet) — not
        // on the vision sheet, which is a single-purpose window.
        if (!visionOnly) panel.addView(buildActionRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        statusLine = text("Loading…", 13f, cSub).apply { setPadding(0, dp(12), 0, 0) }
        panel.addView(statusLine)

        sections = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(sections)

        // Open in Monitor — for the rare time the full dashboard is wanted.
        if (monitorUrl.isNotBlank() && !visionOnly) panel.addView(text("Open in Monitor ↗", 13f, cBlue).apply {
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener { openInMonitor(monitorUrl) }
        })

        val scroll = MaxHeightScrollView(this, (resources.displayMetrics.heightPixels * 0.88f).toInt()).apply {
            isFillViewport = false
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scrim.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return scrim
    }

    private fun buildActionRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(icon: Int, label: String, onTap: () -> Unit): View {
            val b = ImageView(this).apply {
                setImageResource(icon); setColorFilter(cInk)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#26272B")); cornerRadius = dpF(12); setStroke(dp(1), cLine)
                }
                contentDescription = label
                setOnClickListener { onTap() }
            }
            return b
        }
        val lp = LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(10) }
        row.addView(btn(R.drawable.ic_act_like, "Like") { like() }, lp)
        row.addView(btn(R.drawable.ic_act_scan, "Vision") { runVision() }, lp)
        row.addView(btn(R.drawable.ic_act_bag, "Mark as bought") { bought() }, lp)
        row.addView(btn(R.drawable.ic_act_mark, "Save to catalogue") { saveToCatalogue() }, lp)
        row.addView(btn(R.drawable.ic_act_bin, "Dismiss") { dismissListing() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    // ── data ─────────────────────────────────────────────────────────

    private fun load() {
        Thread {
            val j = try { getJson("$apiBase/api/listing/${enc(listingId)}/info") } catch (_: Exception) { null }
            ui.post {
                if (j == null || !j.optBoolean("ok", true)) {
                    statusLine.text = "Can't reach the Pi. Is Tailscale on?"
                    statusLine.setTextColor(cWarn)
                    statusLine.setOnClickListener { statusLine.text = "Retrying…"; load() }
                } else {
                    info = j
                    statusLine.visibility = View.GONE
                    render(j)
                }
            }
        }.start()
    }

    private fun render(d: JSONObject) {
        sections.removeAllViews()
        if (visionOnly) {
            sections.addView(section("Vision", visionBody(d)))
            return
        }
        sections.addView(section("Reference notes", refsBody(d)))
        sections.addView(section("Vision", visionBody(d)))
        sections.addView(section("Seller's description", descBody(d)))
    }

    private fun refsBody(d: JSONObject): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = d.optJSONObject("match")
        val r = d.optJSONObject("refs") ?: JSONObject()
        val g = d.optJSONObject("guards") ?: JSONObject()
        val kind = m?.optString("kind", "") ?: ""
        if (m == null || kind.isBlank()) {
            box.addView(kv("No catalogue match yet" +
                (if (d.optInt("descmatch") >= 3) " · description checked" else "") +
                (if (d.optInt("idscan") >= 3) " · photo checked" else ""), cSub))
            if (g.optBoolean("paperwork")) box.addView(kv("Reads as a manual / paperwork listing.", cWarn))
            if (g.optBoolean("spare_part") || g.optBoolean("fig_part")) box.addView(kv("Reads as a spare part.", cWarn))
            return box
        }
        val num = m.optString("num", ""); val name = m.optString("name", ""); val theme = m.optString("theme", "")
        val catUrl = catalogueUrlFor(m)
        if (kind == "fig") {
            box.addView(labelled("Minifigure", "$num · $name" + (if (theme.isNotBlank()) " · $theme" else ""), catUrl))
            box.addView(labelled("Value", "used £${money(m.optDouble("value_used", 0.0))} · new £${money(m.optDouble("value_new", 0.0))}"))
            val sets = m.optString("sets_list", "").replace(Regex("-1\\b"), "")
            if (sets.isNotBlank()) box.addView(labelled("Appears in", sets))
            return box
        }
        box.addView(labelled("Set", "${num.removeSuffix("-1")} · $name" + (if (theme.isNotBlank()) " · $theme" else ""), catUrl))
        val note = r.optString("ref_note", "")
        val missing = r.optJSONArray("missing")
        val missingList = (0 until (missing?.length() ?: 0)).map { missing!!.optString(it) }
        val full = r.optJSONObject("full")
        when {
            Regex("manual only|spare part|fig part|loose fig").containsMatchIn(note) ->
                box.addView(kv("Priced as $note — the full-set reference is not applied.", cWarn))
            missingList.isNotEmpty() -> {
                box.addView(labelled("Seller says", missingList.joinToString(" · ") { if (it == "parts") "incomplete" else "no $it" }, color = cWarn))
                val f = r.optDouble("comp_factor", 0.0)
                if (f > 0 && f < 1) {
                    val figV = r.optDouble("minifig_value", 0.0)
                    val fullBest = full?.let { maxOf(it.optDouble("bl_used", 0.0), it.optDouble("bl_new", 0.0)) } ?: 0.0
                    box.addView(labelled("References scaled", "×${"%.2f".format(f)}" +
                        (if (figV > 0 && fullBest > 0) " · figs are £${money(figV)} of the £${money(fullBest)} set" else "")))
                }
            }
            else -> box.addView(kv("Nothing declared missing in the title or description" +
                (if (r.optBoolean("complete_claim")) " — seller says complete" else "") + ".", cGreen))
        }
        // Reference table
        val rows = mutableListOf<Pair<String, DoubleArray>>()
        full?.let { rows += "Complete set" to doubleArrayOf(it.optDouble("bl_new", 0.0), it.optDouble("bl_used", 0.0), it.optDouble("ebay_new", 0.0), it.optDouble("ebay_used", 0.0)) }
        val shown = r.optJSONObject("shown")
        if (shown != null && (missingList.isNotEmpty() || full == null)) {
            val f = r.optDouble("comp_factor", 0.0)
            val lab = (if (missingList.isNotEmpty()) "As listed" else "Reference") + (if (f > 0 && f < 1) " ×${"%.2f".format(f)}" else "")
            rows += lab to doubleArrayOf(shown.optDouble("bl_new_avg", 0.0), shown.optDouble("bl_used_avg", 0.0), shown.optDouble("ebay_new_avg", 0.0), shown.optDouble("ebay_used_avg", 0.0))
        }
        if (rows.isNotEmpty()) box.addView(refTable(rows))
        // Minifigs in this set
        val figs = r.optJSONArray("figs")
        if (figs != null && figs.length() > 0) {
            val count = r.optInt("minifig_count", figs.length()); val fv = r.optDouble("minifig_value", 0.0)
            box.addView(labelled("Minifigs in this set", "$count" + (if (fv > 0) " · £${money(fv)}" else "")).apply { setPadding(0, dp(8), 0, 0) })
            for (i in 0 until figs.length()) {
                val f = figs.optJSONObject(i) ?: continue
                val used = f.optDouble("value_used", f.optDouble("used", f.optDouble("value", 0.0)))
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(text(f.optString("fig_num", ""), 13f, cBlue).apply { minWidth = dp(64) })
                line.addView(text(f.optString("name", ""), 13f, cInk).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); maxLines = 2 })
                line.addView(text(if (used > 0) "£${money(used)}" else "—", 13f, cInk).apply { gravity = Gravity.END; minWidth = dp(44) })
                box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        }
        if (g.optBoolean("bait")) box.addView(kv("Vinted seller with no reviews at a fraction of market — treated as bait, not alerted.", cWarn))
        return box
    }

    private fun refTable(rows: List<Pair<String, DoubleArray>>): View {
        val t = TableLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        val heads = listOf("", "BL new", "BL used", "eBay new", "eBay used")
        t.addView(TableRow(this).apply {
            heads.forEach { addView(text(it.uppercase(), 10f, cDim).apply { gravity = Gravity.END; setPadding(dp(4), 0, dp(4), dp(4)) }) }
        })
        for ((label, vals) in rows) {
            t.addView(TableRow(this).apply {
                addView(text(label, 12f, cSub).apply { setPadding(0, dp(4), dp(4), dp(4)) })
                vals.forEach { v ->
                    val cell = if (v <= 0) "—" else {
                        val pct = if (asking <= v) ((v - asking) / v * 100).roundToInt() else (-(asking - v) / asking * 100).roundToInt()
                        "£${money(v)} ${if (pct >= 0) "+" else ""}$pct%"
                    }
                    addView(text(cell, 12f, cInk).apply { gravity = Gravity.END; setPadding(dp(4), dp(4), dp(4), dp(4)) })
                }
            })
        }
        for (i in 1 until heads.size) t.setColumnStretchable(i, true)
        return t
    }

    private fun visionBody(d: JSONObject): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val v = d.optJSONObject("vision")
        val fc = v?.optInt("fig_count", 0) ?: 0
        if (v != null && fc > 0) {
            val ratio = v.optDouble("ratio", 0.0); val good = v.optBoolean("good", ratio >= 1.5)
            box.addView(kv("£${money(v.optDouble("est_value", 0.0))} in $fc fig${if (fc == 1) "" else "s"}" +
                (if (ratio > 0) " · ${"%.1f".format(ratio)}×" else "") +
                (if (v.optInt("unknown_figs", 0) > 0) " · ${v.optInt("unknown_figs")} unidentified" else ""),
                if (good) cGreen else cAmber, bold = true))
            val matched = v.optJSONArray("matched")
            if (matched != null) for (i in 0 until matched.length()) {
                val m = matched.optJSONObject(i) ?: continue
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(text("${m.optInt("count", 1)}×", 13f, cSub).apply { minWidth = dp(30) })
                line.addView(text(m.optString("name", m.optString("fig_num", "")), 13f, cInk).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); maxLines = 2 })
                line.addView(text("£${money(m.optDouble("price", 0.0))}", 13f, cInk).apply { gravity = Gravity.END; minWidth = dp(44) })
                box.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        } else if (v != null && v.optString("reject_reason", "").isNotBlank()) {
            box.addView(kv("Vision looked and stopped: ${v.optString("reject_reason")}.", cSub))
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(button("▶ Run vision", primary = true) { runVision() })
            row.addView(text("about a penny · 20–30 s", 12f, cSub).apply { setPadding(dp(10), 0, 0, 0) })
            box.addView(row)
        }
        return box
    }

    private fun descBody(d: JSONObject): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ds = d.optJSONObject("description") ?: JSONObject()
        val txt = ds.optString("text", "")
        when {
            txt.isNotBlank() -> box.addView(text(txt, 13f, cInk).apply { setLineSpacing(0f, 1.2f) })
            ds.optBoolean("fetched") -> box.addView(kv("The listing has no description.", cSub))
            else -> box.addView(button("Fetch description") { fetchDescription() })
        }
        return box
    }

    // ── actions ──────────────────────────────────────────────────────

    private fun runVision() {
        toast("Vision started — result appears here")
        Thread {
            try { postJson("$apiBase/api/vision/run?id=${enc(listingId)}", JSONObject().put("id", listingId)) } catch (_: Exception) {}
            visionPolls = 0
            pollVision()
        }.start()
    }

    /** Re-read the info endpoint every 3 s until vision has a verdict
     *  (or ~90 s pass). The section re-renders in place. */
    private fun pollVision() {
        if (visionPolls++ > 30) return
        ui.postDelayed({
            Thread {
                val j = try { getJson("$apiBase/api/listing/${enc(listingId)}/info") } catch (_: Exception) { null }
                val v = j?.optJSONObject("vision")
                val done = v != null && (v.optInt("fig_count", 0) > 0 || v.optString("reject_reason", "").isNotBlank())
                ui.post {
                    if (j != null && done) { info = j; render(j) } else pollVision()
                }
            }.start()
        }, 3000)
    }

    private fun fetchDescription() {
        toast("Fetching…")
        Thread {
            val r = try { postJson("$apiBase/api/listing/${enc(listingId)}/fetch_description", JSONObject()) } catch (_: Exception) { null }
            ui.post {
                val i = info ?: return@post
                if (r != null && r.optBoolean("ok")) {
                    i.put("description", JSONObject().put("text", r.optString("text", "")).put("fetched", true))
                    render(i)
                } else toast("Could not fetch the description")
            }
        }.start()
    }

    private fun like() = postAndToast("$apiBase/api/like", JSONObject().put("id", listingId), "Liked")

    private fun bought() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (asking > 0) money(asking) else "")
            hint = "Price paid (£)"
        }
        AlertDialog.Builder(this).setTitle("Mark as bought").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val paid = input.text.toString().toDoubleOrNull() ?: 0.0
                postAndToast("$apiBase/api/purchases",
                    JSONObject().put("listing_id", listingId).put("price_paid", paid), "Marked as bought")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun saveToCatalogue() {
        val m = info?.optJSONObject("match")
        val kind = m?.optString("kind", "") ?: ""
        val num = m?.optString("num", "") ?: ""
        if (kind.isBlank() || num.isBlank()) { toast("No catalogue match to save"); return }
        Thread {
            val r = try { postJson("$apiBase/api/catalogue/save", JSONObject().put("kind", kind).put("num", num)) } catch (_: Exception) { null }
            if (r != null && r.optBoolean("ok", true) && r.optString("status") == "saved" && kind == "set") {
                try { postJson("$apiBase/api/sets/${enc(num)}/enrich", JSONObject()) } catch (_: Exception) {}
            }
            ui.post { toast(if (r == null || r.optBoolean("ok", true).not()) "Save failed" else "Saved ${r.optString("status", "")}".trim()) }
        }.start()
    }

    private fun dismissListing() {
        Thread {
            try { postJson("$apiBase/api/dismiss", JSONObject().put("id", listingId).put("title", sellerTitle)) } catch (_: Exception) {}
            ui.post {
                // Same id scheme as the renderer's replace_key (= listing id).
                NotificationManagerCompat.from(this).cancel(("lm:$listingId").hashCode())
                toast("Dismissed")
                finish()
            }
        }.start()
    }

    private fun postAndToast(url: String, body: JSONObject, okMsg: String) {
        Thread {
            val r = try { postJson(url, body) } catch (_: Exception) { null }
            ui.post { toast(if (r == null || !r.optBoolean("ok", true)) "Failed — is Tailscale on?" else okMsg) }
        }.start()
    }

    private fun openListing() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_LISTING, listingUrl)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun openInMonitor(url: String) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun catalogueUrlFor(m: JSONObject): String {
        val num = m.optString("num", ""); if (num.isBlank()) return ""
        val kind = if (m.optString("kind") == "fig") "figs" else "sets"
        val scope = m.optString("scope", "sw").ifBlank { "sw" }
        return "$apiBase/#catalogue=$scope/$kind/$num"
    }

    // ── http ─────────────────────────────────────────────────────────

    private fun getJson(url: String): JSONObject? =
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val b = r.body?.string() ?: return null
            if (!r.isSuccessful) return null
            JSONObject(b)
        }

    private fun postJson(url: String, body: JSONObject): JSONObject? =
        http.newCall(Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { r ->
                val b = r.body?.string() ?: return null
                try { JSONObject(b) } catch (_: Exception) { JSONObject().put("ok", r.isSuccessful) }
            }

    // ── view helpers ─────────────────────────────────────────────────

    private fun section(title: String, body: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(View(this).apply { setBackgroundColor(cLine) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) })
        box.addView(text(title.uppercase(), 11f, cDim, bold = true).apply { letterSpacing = 0.12f; setPadding(0, 0, 0, dp(6)) })
        box.addView(body)
        return box
    }

    private fun labelled(k: String, v: String, url: String = "", color: Int = cInk): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(text(k, 13f, cDim).apply { minWidth = dp(96) })
        row.addView(text(v, 13f, if (url.isNotBlank()) cBlue else color).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (url.isNotBlank()) setOnClickListener { openInMonitor(url) }
        })
        row.setPadding(0, dp(3), 0, dp(3))
        return row
    }

    private fun kv(s: String, color: Int, bold: Boolean = false): View =
        text(s, 13f, color, bold).apply { setPadding(0, dp(3), 0, dp(3)) }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(label: String, primary: Boolean = false, onTap: () -> Unit): View =
        text(label, 13f, if (primary) Color.parseColor("#1A1405") else cInk, bold = true).apply {
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dpF(12)
                if (primary) setColor(cAmber) else { setColor(Color.parseColor("#26272B")); setStroke(dp(1), cLine) }
            }
            setOnClickListener { onTap() }
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun money(v: Double): String = if (v == v.roundToInt().toDouble()) "${v.roundToInt()}" else "%.2f".format(v)
    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Int): Float = v * resources.displayMetrics.density
    private fun navBarInset(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    companion object {
        const val EXTRA_LISTING_ID = "listing_id"
        const val EXTRA_API_BASE = "api_base"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BRAND = "brand"
        const val EXTRA_ASKING = "asking"
        const val EXTRA_TRUE_COST = "true_cost"
        const val EXTRA_LISTING_URL = "listing_url"
        const val EXTRA_MONITOR_URL = "monitor_url"
        const val EXTRA_PHOTO_URL = "photo_url"
        const val EXTRA_GRAIL_JSON = "grail_json"
        const val EXTRA_MODE = "mode"
        const val MODE_VISION = "vision"
    }
}

/** ScrollView capped at a max height so the sheet never covers the whole
 *  screen; shorter content just wraps. */
class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
