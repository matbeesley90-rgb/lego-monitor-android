package com.lego.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground service holding a persistent ntfy WebSocket connection.
 *
 * Phase 1 routes incoming messages straight to NotificationRenderer
 * (stock NotificationCompat.Builder). Phase 2 will replace the renderer
 * with custom RemoteViews using brand fonts + coloured spans.
 *
 * URL is hardcoded for Phase 1; settings UI will land later.
 */
class WebSocketService : Service() {

    companion object {
        private const val TAG = "LegoWS"

        // Same topic the existing ntfy-Android app subscribes to. Phase 1
        // runs in parallel with ntfy-Android so we see notifications in
        // BOTH apps and can compare.
        private const val WS_URL =
            "ws://81.96.120.250:8084/lego-monitor-xeP73SxvVPlq/ws"

        // Foreground-notification plumbing.
        private const val FG_CHANNEL_ID = "lego_monitor_status"
        private const val FG_NOTIFICATION_ID = 1

        // Broadcast wiring so the Activity can show the live state.
        const val ACTION_STATUS = "com.lego.monitor.STATUS"
        const val EXTRA_STATE   = "state"
        const val EXTRA_DETAIL  = "detail"
    }

    private lateinit var http: OkHttpClient
    private var ws: WebSocket? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(FG_NOTIFICATION_ID, buildFgNotification("Starting…"))
        http = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // ntfy long-poll friendly
            .build()
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, "service destroyed")
        ws = null
    }

    private fun connect() {
        broadcastStatus("Connecting", WS_URL)
        val req = Request.Builder().url(WS_URL).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS open")
                broadcastStatus("Connected", WS_URL)
                updateFgNotification("Listening for deals")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS msg: $text")
                handleNtfyMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS closing $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS closed $code $reason")
                broadcastStatus("Disconnected", "code=$code")
                updateFgNotification("Reconnecting in 5s…")
                scheduleReconnect(5_000)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure", t)
                broadcastStatus("Error", t.message ?: "unknown")
                updateFgNotification("Reconnecting in 5s…")
                scheduleReconnect(5_000)
            }
        })
    }

    private fun scheduleReconnect(delayMs: Long) {
        android.os.Handler(mainLooper).postDelayed({ connect() }, delayMs)
    }

    /**
     * ntfy emits one JSON object per WS frame. Shape (relevant fields):
     *   {"id":"...","event":"message","topic":"...","title":"...",
     *    "message":"...","actions":[{"action":"view","label":"...",
     *    "url":"..."}], "icon":"...", ...}
     * We ignore non-"message" events (keepalive, open, etc.) silently.
     */
    private fun handleNtfyMessage(raw: String) {
        // Always log the incoming frame (first 300 chars) so we can see
        // whether the WS handler is even reached when a notification fails
        // to render. Filter logcat with `adb logcat -s LegoWS LegoV4`.
        Log.d(TAG, "WS rx ${raw.length}B: ${raw.take(300)}")
        try {
            val obj = JSONObject(raw)
            val event = obj.optString("event")
            if (event != "message") {
                Log.d(TAG, "WS skip event=$event")
                return
            }

            // Phase 2: if the message body is V4 JSON, route to the
            // custom RemoteViews renderer. Otherwise fall back to the
            // Phase 1 stock notification path so a plain-text test
            // (e.g. curl -d "hello") still shows something useful.
            val msgBody = obj.optString("message")
            val v4Raw = V4Payload.tryParse(msgBody)
            // Carry the outer ntfy frame's title into the payload — the
            // watchlist render path uses it verbatim.
            val v4 = v4Raw?.copy(wireTitle = obj.optString("title"))
            Log.d(TAG, "WS routing: v4_parsed=${v4 != null}  body_starts='${msgBody.take(60)}'")
            if (v4 != null) {
                V4NotificationRenderer.show(applicationContext, obj, v4)
            } else {
                NotificationRenderer.show(applicationContext, obj)
            }
        } catch (e: Exception) {
            // Full stack trace — Log.w(tag, msg, e) prints the throwable
            // chain, not just .message. The old single-arg form is what
            // hid the real exception during the Color.parseColor("#222")
            // bug.
            Log.e(TAG, "WS frame handler threw", e)
        }
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                FG_CHANNEL_ID, "LEGO Monitor status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent listener for deal notifications"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildFgNotification(text: String): Notification =
        NotificationCompat.Builder(this, FG_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LEGO Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateFgNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(FG_NOTIFICATION_ID, buildFgNotification(text))
    }

    private fun broadcastStatus(state: String, detail: String = "") {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            `package` = packageName
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_DETAIL, detail)
        })
    }
}
