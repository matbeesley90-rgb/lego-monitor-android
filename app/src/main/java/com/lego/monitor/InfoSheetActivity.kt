package com.lego.monitor

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * The info window opened from a notification (2026-09-09, Mat: "duplicate
 * it and put the photo at the top"). It is the DASHBOARD'S OWN info sheet:
 * the page's `#sheet=<id>` mode renders just that sheet, photo on top, so
 * it is identical to the one on the listing cards by construction — no
 * native copy to drift. The same window also opens the vision page
 * (`EXTRA_URL`). Back walks the page's own history (vision overlay →
 * sheet) and then closes the window, returning to wherever you were.
 *
 * Marketplace links ("Open listing ↗") are handed to MainActivity's
 * openExternal so they land in the native app, never the O2 hijacker.
 */
class InfoSheetActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private var piHost = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apiBase = intent.getStringExtra(EXTRA_API_BASE).orEmpty().trimEnd('/')
        val listingId = intent.getStringExtra(EXTRA_LISTING_ID).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: "$apiBase/#sheet=${Uri.encode(listingId)}"
        piHost = Uri.parse(apiBase).host.orEmpty()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0F1117")) }
        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(48, 48, 48, 48)
            text = "Loading…"
        }
        root.addView(status, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        webView.setBackgroundColor(Color.parseColor("#0F1117"))
        // The page's close button (top-left) asks the app to close this
        // window; the dashboard's own sheet uses the same button to close.
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun close() { runOnUiThread { finish() } }
        }, "LegoApp")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val host = u.host ?: ""
                if (piHost.isNotBlank() && host.contains(piHost)) return false
                // Marketplace link → the native app via MainActivity's routing.
                startActivity(Intent(this@InfoSheetActivity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_LISTING, u.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                return true
            }
            override fun onPageFinished(view: WebView, url: String?) {
                status.visibility = View.GONE
                // The sheet's "Open listing ↗" uses window.open — route it
                // through shouldOverrideUrlLoading like the main screen does.
                view.evaluateJavascript(
                    "(function(){if(!window.__legoOpenPatched){window.__legoOpenPatched=1;" +
                    "window.open=function(u){if(u){window.location.href=u;}return null;};}})();", null)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    status.visibility = View.VISIBLE
                    status.text = "Can't reach the monitor — is Tailscale on?"
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LISTING_ID = "listing_id"
        const val EXTRA_API_BASE = "api_base"
        // Optional: a full monitor URL to show instead of the sheet
        // (the vision page). Everything else is derived from the two above.
        const val EXTRA_URL = "url"
        // Accepted for compatibility with older intents; unused here.
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
