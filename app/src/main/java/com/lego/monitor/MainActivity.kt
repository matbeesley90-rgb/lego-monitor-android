package com.lego.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Home screen. Phase A of "make it a proper app": the Pi-hosted web UI
 * (listings / catalogue / filters / settings) is rendered full-screen in
 * a WebView, so the monitor is one installable app instead of a browser
 * bookmark + a separate notification app. The WebSocketService still runs
 * in the background doing the real notification work; this Activity starts
 * it and shows a thin connection-status strip that hides once connected.
 *
 * Phase B (future) migrates the heaviest screens to native Kotlin on the
 * same Pi JSON API.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var webView: WebView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(WebSocketService.EXTRA_STATE) ?: "Unknown"
            val detail = intent?.getStringExtra(WebSocketService.EXTRA_DETAIL).orEmpty()
            // Once connected the strip just steals screen space — hide it.
            // Any other state (Connecting / Disconnected / error) shows it
            // so a silent notification outage is visible at a glance.
            if (state.equals("Connected", ignoreCase = true)) {
                statusView.visibility = View.GONE
            } else {
                statusView.visibility = View.VISIBLE
                statusView.text = if (detail.isEmpty()) {
                    "LEGO Monitor — $state"
                } else {
                    "LEGO Monitor — $state · $detail"
                }
            }
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result ignored — start the service either way; the WebView UI
        // works regardless of whether POST_NOTIFICATIONS was granted.
        startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)
        statusView.text = "LEGO Monitor — Starting…"

        setupWebView()

        // WebView back button navigates page history before leaving the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Notification permission (Android 13+) then start the listener.
        // The WebView above is already loading regardless of the result.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startMonitorService()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            // The UI persists filter/tab state in localStorage — needs DOM
            // storage on or every reopen resets to defaults.
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val host = uri.host ?: ""
                // The monitor UI itself (Pi host) stays in the WebView —
                // internal pages, /vision, API-backed screens, etc.
                if (host.contains(PI_HOST)) return false
                // Anything else is a marketplace link the user tapped —
                // hand it to the matching native app (Vinted / eBay /
                // Facebook / Gumtree), falling back to a browser.
                openExternal(uri)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                // UI loaded = the app is working; hide the status strip.
                // (It defaults to hidden and only the ntfy service's own
                // "not connected" broadcasts or a page-load error bring it
                // back — so it no longer gets stuck on a stale "Starting…"
                // when the service connected before this screen opened.)
                statusView.visibility = View.GONE
                // The web UI opens listings with window.open(url,'_blank'),
                // which a WebView ignores by default — so a tap would do
                // nothing. Redirect window.open to a same-window navigation
                // so shouldOverrideUrlLoading above can route it to the app.
                view.evaluateJavascript(
                    "(function(){if(!window.__legoOpenPatched){" +
                    "window.__legoOpenPatched=1;" +
                    "window.open=function(u){if(u){window.location.href=u;}" +
                    "return null;};}})();",
                    null
                )
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // Only surface the top-level page failing (not sub-resources)
                // so a missing favicon doesn't nag.
                if (request.isForMainFrame) {
                    statusView.visibility = View.VISIBLE
                    statusView.text = "Can't reach the monitor — is the Pi online?"
                }
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(WEB_UI_URL)
    }

    /**
     * Open a tapped marketplace listing in its real native app, actively
     * skipping browsers AND link-hijackers like the My O2 app.
     *
     * Order of attempts:
     *  1. The app that specifically owns this domain (Vinted/eBay/Gumtree/
     *     Facebook), found by elimination via dedicatedAppFor().
     *  2. A real browser, launched explicitly by package — so a generic
     *     hijacker (My O2) can never be picked as the default handler.
     *  3. The in-app WebView, so a tap can never dead-end.
     *
     * Needs the QUERY_ALL_PACKAGES permission (declared in the manifest)
     * so the package queries actually see the installed apps on Android
     * 11+ — without it the queries come back empty and nothing resolves.
     */
    private fun openExternal(uri: android.net.Uri) {
        // Open a listing WITHOUT ever letting the OS pick a default handler
        // (the My O2 app has registered itself as the verified handler for
        // marketplace domains on Mat's phone and hijacks any implicit
        // resolve). O2 is neither a known marketplace app nor a browser, so
        // it is structurally excluded from every path below.
        val scheme = uri.scheme ?: ""
        if (scheme != "http" && scheme != "https") {
            // e.g. the intent:// app-redirect a marketplace page fires —
            // handle it like Chrome does instead of erroring.
            handleIntentUri(uri)
            return
        }
        // Gumtree note: the app never shows in the OS resolver because its
        // App Links verification fails on this phone, but its manifest
        // (checked against the July-2026 APK) matches
        // https://www.gumtree.com pathPattern /p/.* — i.e. exactly the
        // monitor's listing URLs — so an explicit setPackage launch works
        // without verification. A URL the filter doesn't cover throws
        // ActivityNotFoundException in launchIn and falls through to the
        // browser, so the direct attempt is safe for every marketplace.
        for (pkg in knownAppsFor(uri)) {
            if (isInstalled(pkg) && launchIn(pkg, uri)) return
        }
        firstInstalled(BROWSERS)?.let { if (launchIn(it, uri)) return }
        webView.loadUrl(uri.toString())
    }

    /**
     * Handle an intent:// (or other non-http) URL the way Chrome does:
     * parse it, launch the app it names (the URI carries the package, e.g.
     * com.gumtree.android — this is exactly how the Gumtree site opens its
     * app), and if that app isn't available fall back to the URL the page
     * supplied in S.browser_fallback_url. Never load a non-http scheme into
     * the WebView (that's the ERR_UNKNOWN_URL_SCHEME dead-end).
     */
    private fun handleIntentUri(uri: android.net.Uri) {
        val parsed = try {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            parsed.addCategory(Intent.CATEGORY_BROWSABLE) // mirror Chrome
            parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            parsed.component = null
            parsed.selector = null
            startActivity(parsed)
            return
        } catch (_: Exception) {
            // App named by the intent isn't available — use the fallback.
        }
        val fallback = try {
            parsed.getStringExtra("browser_fallback_url")
        } catch (_: Exception) {
            null
        }
        if (!fallback.isNullOrBlank()) {
            val fbUri = android.net.Uri.parse(fallback)
            if (fbUri.scheme == "http" || fbUri.scheme == "https") {
                openExternal(fbUri)
            }
        }
    }

    /** Well-known package names per marketplace host (fallback hints). */
    private fun knownAppsFor(uri: android.net.Uri): List<String> {
        val h = uri.host ?: ""
        return when {
            // Two distinct Vinted apps exist: fr.vinted is the main
            // international app whose manifest lists www.vinted.co.uk (and
            // every other EU domain); com.vinted is the separate US app and
            // handles ONLY www.vinted.com — it's not what a UK user has
            // installed. Try the international app first.
            h.contains("vinted")   -> listOf("fr.vinted", "com.vinted")
            h.contains("ebay")     -> listOf("com.ebay.mobile")
            h.contains("gumtree")  -> listOf("com.gumtree.android")
            h.contains("facebook") || h.contains("fb.") ->
                listOf("com.facebook.katana", "com.facebook.lite")
            else -> emptyList()
        }
    }

    /** Launch [uri] explicitly in [pkg]; true on success. Never throws.
     *  Deliberately NO extra categories: an intent's categories must all be
     *  declared by the target's filter, so adding BROWSABLE made the match
     *  STRICTER and broke apps whose link activity doesn't declare it
     *  (that was the v13 Vinted-opens-a-browser regression). Plain
     *  ACTION_VIEW+DEFAULT is the most permissive explicit launch. */
    private fun launchIn(pkg: String, uri: android.net.Uri): Boolean = try {
        startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) {
        false
    }

    private fun firstInstalled(pkgs: List<String>): String? =
        pkgs.firstOrNull { isInstalled(it) }

    private fun startMonitorService() {
        val svc = Intent(this, WebSocketService::class.java)
        ContextCompat.startForegroundService(this, svc)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(WebSocketService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    companion object {
        // The Pi that hosts both the Flask UI and the ntfy WebSocket.
        // Cleartext to this IP is whitelisted in network_security_config.xml.
        private const val PI_HOST = "81.96.120.250"
        // The Pi-hosted Flask UI.
        private const val WEB_UI_URL = "http://$PI_HOST:5000/"
        // Real browsers to fall back to, launched explicitly by package so
        // a generic link-hijacker (My O2) is never picked. First installed
        // wins; order = most common first.
        private val BROWSERS = listOf(
            "com.android.chrome",
            "com.sec.android.app.sbrowser",   // Samsung Internet
            "org.mozilla.firefox",
            "com.microsoft.emmx",             // Edge
            "com.brave.browser",
            "com.opera.browser",
            "com.duckduckgo.mobile.android",
        )
    }
}
