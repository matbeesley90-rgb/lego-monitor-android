package com.lego.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 2026-09-18 audit S2: after a phone reboot nothing listened until Mat
 * opened the app — START_STICKY survives process kills, not reboots.
 * Start the foreground listener at boot, and again after an APK update
 * (MY_PACKAGE_REPLACED: a sideloaded install kills the service too).
 * BOOT_COMPLETED is a protected broadcast only the system can send, so
 * the receiver being exported is safe.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, WebSocketService::class.java))
            Log.i("LegoWS", "BootReceiver: service started on $action")
        } catch (e: Exception) {
            // Android 12+ can refuse a background FGS start in rare states
            // (ForegroundServiceStartNotAllowedException); the next app
            // open starts it as before.
            Log.w("LegoWS", "BootReceiver: could not start service", e)
        }
    }
}
