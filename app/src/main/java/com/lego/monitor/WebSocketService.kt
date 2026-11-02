package com.lego.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
 * Incoming frames are routed to V4NotificationRenderer (custom
 * RemoteViews) when the body is V4 JSON, else to the stock
 * NotificationRenderer. URL is hardcoded; settings UI may land later.
 *
 * 2026-09-18 audit (S2/S3): reconnects now ask ntfy to REPLAY what was
 * missed (`since=`), back off exponentially instead of hammering an
 * unreachable Tailscale address every 5 s all night, wake on
 * network-available, and cannot reconnect after onDestroy.
 */
class WebSocketService : Service() {

    companion object {
        private const val TAG = "LegoWS"

        // The one deal topic. 2026-09-18 audit S4: the separate bundle
        // topic was retired — `bundle_ntfy_topic` was never set on the Pi,
        // so every bundle already arrived here. Bundles get their own
        // notification CHANNEL in V4NotificationRenderer instead, which is
        // what "mute bundles independently" actually needed.
        const val TOPIC_MAIN = "lego-monitor-xeP73SxvVPlq"
        // 2026-09-08: ntfy is reached over TAILSCALE, like the dashboard.
        // The public-IP forward on :8084 is being closed (it was the last
        // service the Pi exposed to the internet), and a hardcoded public
        // IP would have broken at the house move anyway. Tailscale must be
        // connected on the phone for pushes — enable "Always-on VPN".
        private const val WS_BASE = "ws://100.66.72.71:8084/$TOPIC_MAIN/ws"

        // Reconnect backoff: 5 s doubling to a 60 s cap, reset on a
        // successful open. Network-available wakes it early.
        private const val RECONNECT_MIN_MS = 5_000L
        private const val RECONNECT_MAX_MS = 60_000L

        // Replay: the ntfy server keeps 12 h (~/ntfy/server.yml
        // cache-duration). On reconnect we ask for everything since the
        // last message id we saw, so pushes that fired during a Tailscale
        // drop / Doze kill arrive late instead of never. Bounded: beyond
        // REPLAY_MAX_AGE_S the id may have expired from the cache (ntfy then
        // returns nothing at all) and replaying a whole night of ~500/day
        // pushes is not wanted either, so we fall back to since=<now - window>.
        private const val REPLAY_MAX_AGE_S = 3 * 3600L
        // A frame older than this at receipt is a replay: render it silently
        // (it lands in the tray, no buzz) so a reconnect after an hour does
        // not vibrate twenty times in a row.
        private const val REPLAY_SILENT_AFTER_S = 120L
        // 2026-09-19 re-review: the age test alone compares the ntfy
        // server clock against the phone clock — a phone running >2 min
        // ahead would have muted EVERY live push, silently. Replays only
        // happen while the since= backlog drains right after onOpen, so a
        // frame is only treated as one within this window of a connect
        // that actually asked for a replay.
        private const val REPLAY_DRAIN_WINDOW_MS = 30_000L
        // Dedup window for replayed frames (ntfy message ids).
        private const val SEEN_IDS_MAX = 200

        private const val PREFS = "lego_ws"
        private const val KEY_LAST_ID = "last_id"
        private const val KEY_LAST_TS = "last_ts"
        private const val KEY_SEEN_IDS = "seen_ids"

        // Foreground-notification plumbing.
        private const val FG_CHANNEL_ID = "lego_monitor_status"
        private const val FG_CHANNEL_ID_MIN = "lego_monitor_service_min"
        private const val FG_NOTIFICATION_ID = 1

        // Broadcast wiring so the Activity can show the live state.
        const val ACTION_STATUS = "com.lego.monitor.STATUS"
        const val EXTRA_STATE   = "state"
        const val EXTRA_DETAIL  = "detail"
    }

    private enum class SocketState { IDLE, CONNECTING, OPEN }

    private lateinit var http: OkHttpClient
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    // Socket bookkeeping is mutated on the main thread only — the OkHttp
    // listener posts to `handler` first — so a stale socket's late callback
    // can never race a fresh connect(). @Volatile for the cross-thread reads.
    @Volatile private var ws: WebSocket? = null
    @Volatile private var destroyed = false
    private var socketState = SocketState.IDLE
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private val reconnectRunnable = Runnable { connect() }
    private var lastFgText = ""
    // elapsedRealtime() deadline for treating old-looking frames as
    // replays; 0 when the current connection asked for no replay. Written
    // on the OkHttp reader thread (onOpen) before any onMessage, read on
    // the same thread — @Volatile for the connect()-side write.
    @Volatile private var replayDrainUntilMs = 0L
    @Volatile private var lastConnectHadSince = false
    private var connectivity: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // The last SEEN_IDS_MAX ntfy message ids, persisted so a process
    // restart followed by a since=<ts> replay still recognises the frame
    // it already rendered. Insertion-ordered; eldest evicted.
    private val seenIds = object : LinkedHashMap<String, Boolean>(SEEN_IDS_MAX, 0.75f, false) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Boolean>?
        ): Boolean = size > SEEN_IDS_MAX
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadSeenIds()
        ensureChannel()
        lastFgText = "Starting…"
        startForeground(FG_NOTIFICATION_ID, buildFgNotification(lastFgText))
        http = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // ntfy long-poll friendly
            .build()
        registerNetworkCallback()
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Flag first: the close() below fires onClosed, which must not
        // schedule a reconnect (it used to — leaking a socket and posting
        // an FG notification after stopForeground).
        destroyed = true
        handler.removeCallbacks(reconnectRunnable)
        unregisterNetworkCallback()
        ws?.close(1000, "service destroyed")
        ws = null
        socketState = SocketState.IDLE
        super.onDestroy()
    }

    // ── Connection ─────────────────────────────────────────────────────

    private fun connect() {
        if (destroyed) return
        handler.removeCallbacks(reconnectRunnable)
        if (socketState != SocketState.IDLE) return   // already connecting / open
        socketState = SocketState.CONNECTING
        val url = wsUrl()
        lastConnectHadSince = url.contains("since=")
        Log.i(TAG, "WS connect ${url.substringAfter("/ws")}")
        broadcastStatus("Connecting", WS_BASE)
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WS open")
            // Set the replay window HERE (reader thread, before the first
            // onMessage) — posting it to the main thread would let the
            // first replayed frames race past with the window still shut.
            replayDrainUntilMs = if (lastConnectHadSince)
                android.os.SystemClock.elapsedRealtime() + REPLAY_DRAIN_WINDOW_MS
            else 0L
            handler.post {
                if (webSocket !== ws) return@post
                socketState = SocketState.OPEN
                reconnectDelayMs = RECONNECT_MIN_MS
                broadcastStatus("Connected", WS_BASE)
                updateFgNotification("Listening for deals")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleNtfyMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closing $code $reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WS closed $code $reason")
            onSocketDown(webSocket, "Disconnected", "code=$code")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure", t)
            onSocketDown(webSocket, "Error", t.message ?: "unknown")
        }
    }

    private fun onSocketDown(webSocket: WebSocket, state: String, detail: String) {
        handler.post {
            if (webSocket !== ws) return@post   // a superseded socket's callback
            ws = null
            socketState = SocketState.IDLE
            if (destroyed) return@post
            broadcastStatus(state, detail)
            // One FG-notification post per state change, not per failure.
            updateFgNotification("Reconnecting…")
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        handler.removeCallbacks(reconnectRunnable)
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        Log.i(TAG, "WS reconnect in ${delay / 1000}s")
        handler.postDelayed(reconnectRunnable, delay)
    }

    /** Reconnect the moment a network (WiFi, mobile, the Tailscale VPN)
     *  comes up instead of waiting out the backoff. ACCESS_NETWORK_STATE
     *  is declared in the manifest for exactly this. */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (destroyed || socketState != SocketState.IDLE) return@post
                    Log.i(TAG, "network available → connecting now")
                    reconnectDelayMs = RECONNECT_MIN_MS
                    connect()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            connectivity = cm
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "network callback unavailable — backoff only", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivity ?: return
        val cb = netCallback ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        connectivity = null
        netCallback = null
    }

    /**
     * Base URL plus a `since=` for replay when we have seen a message
     * before. Cold start (nothing stored) → no `since` at all: `since=all`
     * would dump the whole 12 h cache onto a fresh install.
     *   last message < REPLAY_MAX_AGE_S ago → since=<its id>  (exact)
     *   older                              → since=<now - window> (unix ts)
     */
    private fun wsUrl(): String {
        val lastId = prefs.getString(KEY_LAST_ID, "") ?: ""
        val lastTs = prefs.getLong(KEY_LAST_TS, 0L)
        if (lastTs <= 0L) return WS_BASE
        val now = System.currentTimeMillis() / 1000
        val age = now - lastTs
        return if (lastId.isNotBlank() && age in 0..REPLAY_MAX_AGE_S) {
            "$WS_BASE?since=$lastId"
        } else {
            "$WS_BASE?since=${now - REPLAY_MAX_AGE_S}"
        }
    }

    // ── Frames ─────────────────────────────────────────────────────────

    /**
     * ntfy emits one JSON object per WS frame. Shape (relevant fields):
     *   {"id":"...","time":1694000000,"event":"message","topic":"...",
     *    "title":"...","message":"...","actions":[...], "icon":"...", ...}
     * We ignore non-"message" events (keepalive, open, etc.) silently.
     * Runs on the OkHttp reader thread — rendering is off the main thread
     * by design.
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

            val id = obj.optString("id")
            val time = obj.optLong("time", 0L)
            val nowSec = System.currentTimeMillis() / 1000
            // Replay dedup. V4 frames also collapse by replace_key (same
            // notifId → in-place), and a late `cancel` for a card that never
            // showed is a no-op — so the id LRU mainly protects plain-text
            // frames and the one frame a since=<ts> replay repeats.
            if (id.isNotBlank() && !noteMessage(id, if (time > 0) time else nowSec)) {
                Log.d(TAG, "WS dup id=$id skipped")
                return
            }
            // Replay = old frame arriving while the since= backlog drains
            // (both conditions): age alone would mute live pushes on a
            // skewed phone clock (2026-09-19 re-review).
            val replayed = time > 0 && nowSec - time > REPLAY_SILENT_AFTER_S &&
                android.os.SystemClock.elapsedRealtime() < replayDrainUntilMs

            // If the message body is V4 JSON, route to the custom
            // RemoteViews renderer. Otherwise fall back to the stock
            // notification path so a plain-text test (e.g. curl -d
            // "hello") still shows something useful.
            val msgBody = obj.optString("message")
            val v4 = V4Payload.tryParse(msgBody)
            Log.d(TAG, "WS routing: v4_parsed=${v4 != null} replayed=$replayed body_starts='${msgBody.take(60)}'")
            if (v4 != null) {
                // V4 payloads — deals, auctions AND bundles — all render
                // through the V4 pipeline (the renderer branches on
                // kind=="bundle" for the channel, pct slot + figs footer).
                V4NotificationRenderer.show(applicationContext, obj, v4, silent = replayed)
            } else {
                // Plain-text frames (system alerts, tests) take the stock
                // renderer.
                NotificationRenderer.show(applicationContext, obj, silent = replayed)
            }
        } catch (e: Exception) {
            // Full stack trace — Log.w(tag, msg, e) prints the throwable
            // chain, not just .message. The old single-arg form is what
            // hid the real exception during the Color.parseColor("#222")
            // bug.
            Log.e(TAG, "WS frame handler threw", e)
        }
    }

    /** Record a delivered frame: last id/time for `since=` on the next
     *  reconnect, plus the id LRU. Returns false if the id was already
     *  seen (a replay of something we rendered). One prefs write per frame. */
    private fun noteMessage(id: String, timeSec: Long): Boolean {
        val joined: String
        synchronized(seenIds) {
            if (seenIds.containsKey(id)) return false
            seenIds[id] = true
            joined = seenIds.keys.joinToString(",")
        }
        prefs.edit()
            .putString(KEY_LAST_ID, id)
            .putLong(KEY_LAST_TS, timeSec)
            .putString(KEY_SEEN_IDS, joined)
            .apply()
        return true
    }

    private fun loadSeenIds() {
        val stored = prefs.getString(KEY_SEEN_IDS, "") ?: ""
        if (stored.isBlank()) return
        synchronized(seenIds) {
            for (id in stored.split(',')) if (id.isNotBlank()) seenIds[id] = true
        }
    }

    // ── FG notification / status ───────────────────────────────────────

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            // IMPORTANCE_MIN: silent, no status-bar icon, collapsed into
            // the drawer's silent section — as invisible as Android
            // allows a foreground-service notification to be. (Zero is
            // impossible: the OS mandates a notification for a
            // persistent socket, and channel importance can't be
            // lowered after creation, hence the new channel id.)
            val ch = NotificationChannel(
                FG_CHANNEL_ID_MIN, "Service status",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Background connection status (hidden)"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
            // Retire the old, more visible status channel if present.
            try { mgr.deleteNotificationChannel(FG_CHANNEL_ID) } catch (_: Exception) {}
        }
    }

    private fun buildFgNotification(text: String): Notification =
        NotificationCompat.Builder(this, FG_CHANNEL_ID_MIN)
            .setSmallIcon(R.drawable.ic_notification_head)
            .setContentTitle("LEGO Monitor")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** Rate-limited to state changes: the same text is not re-posted. */
    private fun updateFgNotification(text: String) {
        if (text == lastFgText) return
        lastFgText = text
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
