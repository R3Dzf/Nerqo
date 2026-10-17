package com.r3dzf.nerqo

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.*
import androidx.compose.runtime.*
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Shared Compose state updated by one user-started, foreground live connection.
 * Never contains private pairing tokens, clipboard contents or file bytes.
 */
internal object LiveConnectionState {
    var host by mutableStateOf("")
    var online by mutableStateOf(false)
    var items by mutableStateOf<List<SharedEntry>>(emptyList())
    var detail by mutableStateOf("Connecting securely...")
}

/**
 * Android's connectedDevice foreground service owns the TLS event stream
 * independently of the Activity. It stays visible in notifications, can be
 * explicitly disconnected, and retries Wi-Fi/hotspot changes with backoff.
 *
 * Android may still end a service due to device power policy or force-stop.
 * Restart-on-process-recreation is best effort, not a hard OS guarantee.
 */
class LiveConnectionService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nerqo_native", MODE_PRIVATE) }
    private var started = false
    @Volatile private var running = true
    @Volatile private var stream: HttpURLConnection? = null
    private var lastSeen: Set<String>? = null
    private var currentMessage = "Connecting to your computer..."

    private fun onMain(action: () -> Unit) { ui.post { action() } }
    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("connection", "Continuous device connection", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(detail: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 44891, Intent(this, MainActivity::class.java), flags)
        val disconnect = PendingIntent.getService(this, 44892,
            Intent(this, LiveConnectionService::class.java).setAction("disconnect"), flags)
        return Notification.Builder(this, "connection")
            .setSmallIcon(R.drawable.ic_devices)
            .setContentTitle("Nerqo · Local connection")
            .setContentText(detail)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .build()
    }

    private fun display(detail: String, online: Boolean) {
        currentMessage = detail
        onMain {
            LiveConnectionState.online = online
            LiveConnectionState.detail = detail
            getSystemService(NotificationManager::class.java).notify(44891, notification(detail))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "disconnect") {
            prefs.edit().putBoolean("disconnected", true).apply()
            running = false
            stream?.disconnect()
            onMain {
                LiveConnectionState.online = false
                LiveConnectionState.items = emptyList()
                LiveConnectionState.detail = "Disconnected from notification"
            }
            worker.execute {
                val host = prefs.getString("host", "") ?: ""
                val port = prefs.getInt("port", 44888)
                val token = prefs.getString("token_" + host, null)
                if (token != null && LocalNetwork.isPrivateIPv4(host)) {
                    runCatching {
                        prefs.getString("cert_" + host + ":" + port, null)?.let { identity ->
                            val device = Device("Windows PC", host, port)
                            NativeClient.trustIdentity(device, identity)
                            NativeClient.disconnect(device, token)
                        }
                    }
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(44891, notification(currentMessage),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(44891, notification(currentMessage))
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            running = true
            worker.execute { runConnection() }
        } else stream?.disconnect() // Pairing/IP changed; resubscribe immediately.
        return START_STICKY
    }

    private fun recoverAddress(identity: String, token: String, old: Device): Device? {
        if (!running) return null
        val nearby = runCatching { NativeClient.discover() }.getOrDefault(emptyList())
        for (candidate in nearby.take(8)) {
            if (!running || candidate.address == old.address) continue
            if (!LocalNetwork.isPrivateIPv4(candidate.address)) continue
            try {
                // An untrusted discovery message can NEVER authorize sending a
                // bearer token to an arbitrary host: compare the pinned identity.
                if (NativeClient.previewIdentity(candidate) != identity) continue
                NativeClient.trustIdentity(candidate, identity)
                prefs.edit().putString("host", candidate.address)
                    .putInt("port", candidate.port)
                    .putString("name", candidate.name)
                    .putString("cert_" + candidate.address + ":" + candidate.port, identity)
                    .putString("token_" + candidate.address, token)
                    .apply()
                return candidate
            } catch (_: Exception) { /* Next LAN candidate. */ }
        }
        return null
    }

    private fun runConnection() {
        var retryMs = 1000L
        var failures = 0
        while (running && !prefs.getBoolean("disconnected", false)) {
            val host = prefs.getString("host", "") ?: ""
            val port = prefs.getInt("port", 44888)
            val identity = prefs.getString("cert_" + host + ":" + port, null)
                ?: prefs.getString("saved_identity", null)
            val token = prefs.getString("token_" + host, null)
                ?: prefs.getString("saved_token", null)
            if (!LocalNetwork.isPrivateIPv4(host) || port !in 1..65535 ||
                identity == null || token == null) {
                display("Pair with Windows to keep a live connection", false)
                break
            }
            val device = Device(prefs.getString("name", "Windows PC") ?: "Windows PC", host, port)
            try {
                NativeClient.trustIdentity(device, identity)
                LocalNetwork.bind(this)
                NativeClient.watchEvents(device, token, { connection ->
                    stream = connection
                }) {
                    // Subscribe first, fetch a complete snapshot on connect and
                    // after every event: reconnects cannot silently miss shares.
                    val latest = NativeClient.list(device, token)
                    val incoming = latest.filter { it.sender == "Windows" }.map { it.id }.toSet()
                    val added = if (lastSeen == null) 0 else (incoming - lastSeen!!).size
                    lastSeen = incoming
                    failures = 0
                    retryMs = 1000L
                    onMain {
                        LiveConnectionState.host = device.address
                        LiveConnectionState.items = latest
                        LiveConnectionState.online = true
                        LiveConnectionState.detail = "Live · " + device.name
                        getSystemService(NotificationManager::class.java).notify(
                            44891, notification(if (added > 0) added.toString() + " new share(s) ready" else "Connected to " + device.name))
                    }
                    ReceiptOutbox.schedule(this)
                }
                if (running) throw IOException("Live socket closed")
            } catch (_: PairingExpiredException) {
                display("Pairing expired. Open Nerqo to pair again.", false)
                break
            } catch (_: ConnectionPausedException) {
                display("Connection paused. Open Nerqo to reconnect.", false)
                break
            } catch (_: Exception) {
                if (!running) break
                failures++
                display("Connection lost · Reconnecting on local Wi-Fi...", false)
                if (failures >= 2) {
                    val moved = recoverAddress(identity, token, device)
                    if (moved != null) retryMs = 1000L
                    failures = 0
                }
            } finally { stream = null }
            if (!running) break
            try { Thread.sleep(retryMs) } catch (_: InterruptedException) { break }
            retryMs = min(retryMs * 2, 30000L)
        }
        if (running) stopSelf()
    }

    override fun onDestroy() {
        running = false
        stream?.disconnect()
        worker.shutdownNow()
        onMain { LiveConnectionState.online = false }
        super.onDestroy()
    }
}
