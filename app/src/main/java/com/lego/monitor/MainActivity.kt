package com.lego.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Phase 1 UI: a single-line status indicator. The WebSocketService does
 * the real work; this Activity just starts/stops it and reflects the
 * latest connection state broadcast by the service.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(WebSocketService.EXTRA_STATE) ?: "Unknown"
            val detail = intent?.getStringExtra(WebSocketService.EXTRA_DETAIL).orEmpty()
            statusView.text = if (detail.isEmpty()) {
                "LEGO Monitor — $state"
            } else {
                "LEGO Monitor — $state\n$detail"
            }
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Result ignored — Phase 1 starts the service either way so the
        // user can see "Connected" even if they deny POST_NOTIFICATIONS.
        startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.statusText)
        statusView.text = "LEGO Monitor — Starting…"

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
}
