package com.r3dzf.nerqo

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.net.wifi.WifiManager
import androidx.compose.runtime.*
import java.io.IOException
import java.util.concurrent.Executors

/** Process-owned observable state. Recreating the Activity never starts another writer. */
internal object TransferRuntime {
    var job by mutableStateOf<TransferJob?>(null)
    var ui by mutableStateOf<TransferUi?>(null)
    var running by mutableStateOf(false)
    var message by mutableStateOf("")
    var history by mutableStateOf<List<HistoryEntry>>(emptyList())
    var control: TransferControl? = null
    fun restore(context: Context) {
        history = TransferHistory.load(context)
        if (running) return
        job = TransferJob.load(context)
        ui = job?.let { TransferUi(it.title, paused = true, detail = "Interrupted transfer · Ready to resume") }
    }
}

/** User-started data-sync foreground service. No auto-start from boot/background. */
class TransferService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nerqo_native", MODE_PRIVATE) }
    private var pendingTransfer: TransferJob?
        get() = TransferRuntime.job
        set(value) { TransferRuntime.job = value }
    private var transferUi: TransferUi?
        get() = TransferRuntime.ui
        set(value) { TransferRuntime.ui = value }
    private var transferControl: TransferControl?
        get() = TransferRuntime.control
        set(value) { TransferRuntime.control = value }
    private var message: String
        get() = TransferRuntime.message
        set(value) { TransferRuntime.message = value }
    private var progress = 0f
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var destroyed = false
    private var lastNotification = 0L
    private fun onMain(action: () -> Unit) { handler.post(action) }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("transfers", "File transfers", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(detail: String, fraction: Float) =
        TransferNotifications.build(this, pendingTransfer, "Running", detail, fraction, running = true)
    private fun showOutcome(job: TransferJob, state: String, detail: String, fraction: Float) {
        getSystemService(NotificationManager::class.java).notify(TransferNotifications.ID,
            TransferNotifications.build(this, job, state, detail, fraction, running = false))
    }
    private fun promote(detail: String) {
        val note = notification(detail, progress)
        if (Build.VERSION.SDK_INT >= 29) startForeground(TransferNotifications.ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(TransferNotifications.ID, note)
    }
    private fun showNotification(detail: String, fraction: Float) {
        if (destroyed || !TransferRuntime.running) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification < 1000) return
        lastNotification = now
        getSystemService(NotificationManager::class.java).notify(TransferNotifications.ID, notification(detail, fraction))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = pendingTransfer ?: TransferJob.load(this)
        pendingTransfer = job
        // Every notification action uses getForegroundService, including a stale action.
        // Fulfil that contract before rejecting the action or stopping an empty session.
        try { promote("Checking saved transfer…") }
        catch (error: Exception) {
            message = error.message ?: "Could not start background transfer"
            stopSelf(); return START_NOT_STICKY
        }
        val actionJob = intent?.getStringExtra(TransferNotifications.JOB_ID)
        if (job == null || (actionJob != null && actionJob != job.id)) {
            if (!TransferRuntime.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        if (TransferRuntime.running) {
            when (intent?.action) {
                "pause" -> transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
                "cancel" -> transferControl?.interrupt(TransferStop.CANCEL, closeAsync = true)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == "pause") {
            stopForeground(STOP_FOREGROUND_REMOVE)
            showOutcome(job, "Paused", "Resume from the saved checkpoint", progress)
            stopSelf(); return START_NOT_STICKY
        }
        try {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nerqo:transfer").apply { acquire(6 * 60 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Nerqo:transfer").apply { acquire() }
            runTransfer(job, intent?.action == "cancel")
        } catch (error: Exception) {
            releaseLocks(); TransferRuntime.running = false
            transferUi = TransferUi(job.title, paused = true, detail = error.message ?: "Could not start background transfer")
            stopForeground(STOP_FOREGROUND_REMOVE)
            showOutcome(job, "Interrupted", transferUi!!.detail, progress)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun releaseLocks() {
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }; wifiLock = null
        runCatching { wake?.let { if (it.isHeld) it.release() } }; wake = null
    }
    // Android 15 data-sync time budget: save a checkpoint and stop promptly.
    override fun onTimeout(startId: Int, fgsType: Int) {
        transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        pendingTransfer?.let { showOutcome(it, "Paused", "Android background time limit reached. Open Nerqo to resume.", progress) }
        stopSelf()
    }
    override fun onDestroy() {
        destroyed = true
        transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
        releaseLocks(); executor.shutdown(); super.onDestroy()
    }
    private fun rememberTransfer(job: TransferJob, status: String, detail: String = "") {
        runCatching { TransferHistory.record(this, job, status, detail) }
        val fresh = TransferHistory.load(this)
        onMain { TransferRuntime.history = fresh }
    }
    private fun isPrivateIPv4(host: String) = LocalNetwork.isPrivateIPv4(host)
    private fun runTransfer(job: TransferJob, cancelOnly: Boolean) {
        check(TransferControl.workerActive.compareAndSet(false, true)) { "Previous transfer is still closing" }
        val control = TransferControl { label -> onMain {
            if (pendingTransfer?.id == job.id) transferUi = (transferUi ?: TransferUi(job.title)).copy(detail = label)
            showNotification(label, progress)
        } }
        control.endpointScope = "https://${job.checkpointHost}:${job.checkpointPort}"
        transferControl = control
        TransferRuntime.running = true
        transferUi = TransferUi(job.title, detail = "Checking saved transfer…")
        executor.execute {
            val destination = Device(job.deviceName, job.host, job.port)
            val access = prefs.getString("token_${job.host}", null)
            rememberTransfer(job, "Running")
            var outcome = "Interrupted"
            var outcomeDetail = "Transfer saved. Resume from the last checkpoint."
            try {
                LocalNetwork.bind(this)
                if (cancelOnly) throw TransferCancelled()
                require(isPrivateIPv4(job.host) && job.port in 1..65535 && job.nextFile in 0..job.uris.size)
                require(prefs.getString("cert_${job.host}:${job.port}", null) == job.fingerprint) {
                    "The computer's security identity changed. Cancel this transfer and pair again."
                }
                if (access == null) throw PairingExpiredException()
                NativeClient.trustIdentity(destination, job.fingerprint)
                var lastUiUpdate = 0L
                var sampleTime = System.nanoTime()
                var sampleBytes = 0L
                var bytesPerSecond = 0.0
                var sampleLabel: String? = null
                fun update(done: Long, total: Long, label: String = "") {
                    val now = System.nanoTime()
                    if (sampleLabel != label || done < sampleBytes) {
                        sampleLabel = label; sampleTime = now; sampleBytes = done; bytesPerSecond = 0.0
                    }
                    if (now - sampleTime >= 500_000_000L) {
                        bytesPerSecond = (done - sampleBytes).coerceAtLeast(0) * 1_000_000_000.0 / (now - sampleTime)
                        sampleTime = now; sampleBytes = done
                    }
                    if (now - lastUiUpdate < 100_000_000L && done != total) return
                    lastUiUpdate = now
                    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
                    fun bytes(value: Long) = when {
                        value < 1048576 -> "%.1f KB".format(java.util.Locale.ROOT, value / 1024.0)
                        value < 1073741824 -> "%.1f MB".format(java.util.Locale.ROOT, value / 1048576.0)
                        else -> "%.2f GB".format(java.util.Locale.ROOT, value / 1073741824.0)
                    }
                    val rate = if (bytesPerSecond > 0) " · " + bytes(bytesPerSecond.toLong()) + "/s" else ""
                    val seconds = if (bytesPerSecond > 0 && total > done) ((total - done) / bytesPerSecond).toLong() else -1L
                    val eta = when {
                        seconds < 0 -> ""
                        seconds < 60 -> " · ~" + seconds + "s left"
                        seconds < 3600 -> " · ~" + (seconds / 60) + "m left"
                        else -> " · ~" + (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m left"
                    }
                    val detail = (if (label.isEmpty()) "" else label + "\n") + bytes(done) + (if (total > 0) " / " + bytes(total) else "") + rate + eta
                    onMain { if (pendingTransfer?.id == job.id) {
                        progress = fraction; transferUi = TransferUi(job.title, detail = detail, fraction = fraction); showNotification(detail, fraction)
                    } }
                }
                control.run {
                    if (job.kind.startsWith("upload") && NativeClient.status(destination).optInt("transferProtocol", 1) < 2)
                        throw IOException("Update Nerqo on Windows to use safe pause and resume.")
                    when (job.kind) {
                        "uploadFiles" -> {
                            for (index in job.nextFile until job.uris.size) {
                                control.checkpoint()
                                NativeClient.sendFile(this, destination, access, Uri.parse(job.uris[index]), sessionId = job.id) { done, total ->
                                    update(done, total, "File ${index + 1} of ${job.uris.size}")
                                }
                                job.nextFile = index + 1; job.save(this)
                            }
                        }
                        "uploadFolder" -> FolderTransfer.send(this, destination, access, Uri.parse(job.uris.single()), job.id) { count, path, done, total ->
                            update(done, total, "$count entries · $path")
                        }
                        "downloadFile" -> NativeClient.download(this, destination, access, job.entry!!, Uri.parse(job.target), job.id) { done, total -> update(done, total) }
                        "downloadFolder" -> FolderReceiver.receive(this, destination, access, job.entry!!.id, Uri.parse(job.target), job.entry.content, job.id) { count, path, done, total ->
                            update(done, total, "$count entries · $path")
                        }
                        else -> throw IOException("Unknown transfer type")
                    }
                }
                if (job.kind.startsWith("download") && job.entry != null) ReceiptOutbox.enqueue(this, job)
                TransferJob.clear(this)
                rememberTransfer(job, "Completed")
                outcome = "Completed"
                outcomeDetail = "Transfer completed and SHA-256 verified"
                runCatching { NativeClient.cleanSession(this, destination, access, job.id, cancel = false) }
                onMain { pendingTransfer = null; transferUi = null; message = "Transfer completed and SHA-256 verified" }
            } catch (error: Exception) {
                if (cancelOnly || control.stop == TransferStop.CANCEL || error is TransferCancelled) {
                    runCatching { NativeClient.cleanSession(this, destination, access, job.id, cancel = true) }
                    TransferJob.clear(this)
                    rememberTransfer(job, "Cancelled")
                    outcome = "Cancelled"
                    outcomeDetail = "Transfer cancelled. Completed files were kept."
                    onMain { pendingTransfer = null; transferUi = null; message = "Transfer cancelled. Completed files were kept." }
                } else {
                    val paused = control.stop == TransferStop.PAUSE || error is TransferPaused
                    outcome = if (paused) "Paused" else "Interrupted"
                    outcomeDetail = if (paused) "Resume from the saved checkpoint" else (error.message ?: "Connection interrupted. Resume from the saved checkpoint.")
                    rememberTransfer(job, if (paused) "Paused" else "Interrupted", if (paused) "" else error.message ?: "Transfer interrupted")
                    onMain {
                        transferUi = (transferUi ?: TransferUi(job.title)).copy(paused = true, detail = if (control.stop == TransferStop.PAUSE || error is TransferPaused) "Paused · Resume from the saved checkpoint" else "Interrupted · " + (error.message ?: "Try again"))
                        message = "Transfer saved. You can resume it or cancel."
                    }
                }
            } finally {
                ReceiptOutbox.schedule(this)
                releaseLocks()
                TransferControl.workerActive.set(false)
                onMain {
                    TransferRuntime.running = false; transferControl = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!destroyed) showOutcome(job, outcome, outcomeDetail, if (outcome == "Completed") 1f else progress)
                    progress = 0f; stopSelf()
                }
            }
        }
    }
}
