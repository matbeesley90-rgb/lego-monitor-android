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
     * Open a tapped marketplace listing in its native app. Tries the
     * specific app package first (so eBay/Vinted/Facebook/Gumtree open
     * directly rather than in a browser); if that app isn't installed,
     * falls back to the OS handler (a browser, or any app with verified
     * App Links), and finally to loading in the WebView so a tap never
     * dead-ends.
     *
     * Note: eBay's app is occasionally flaky launching an item from a
     * cold start ("No results found" until it's warm — eBay's own
     * deep-link bug, needs a second tap). Mat prefers the app anyway.
     */
    private fun openExternal(uri: android.net.Uri) {
        val host = uri.host ?: ""
        val pkg = when {
            host.contains("vinted")   -> "com.vinted"
            host.contains("ebay")     -> "com.ebay.mobile"
            host.contains("facebook") || host.contains("fb.") -> "com.facebook.katana"
            host.contains("gumtree")  -> "com.gumtree.android"
            else -> null
        }
        if (pkg != null) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // App not installed / doesn't match — fall through.
            }
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            webView.loadUrl(uri.toString())
        }
    }

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
    }
}
