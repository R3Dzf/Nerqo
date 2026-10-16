package com.r3dzf.nerqo

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.IOException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** Fully native Android UI — no WebView, browser URL or remote page. */
class MainActivity : ComponentActivity() {
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content != null) { if (content.startsWith("WIFI:")) joinWifiQr(content) else pairFromQr(content) }
    }
    private var pendingWifiQr: String? = null
    private var sessionGeneration = 0
    private var polling = false
    private val executor = Executors.newSingleThreadExecutor()
    private var history: List<HistoryEntry>
        get() = TransferRuntime.history
        set(value) { TransferRuntime.history = value }
    private var connected by mutableStateOf(false)
    private var darkMode by mutableStateOf(false)
    private var device by mutableStateOf<Device?>(null)
    private var token by mutableStateOf<String?>(null)
    private var nearby by mutableStateOf<List<Device>>(emptyList())
    private var entries by mutableStateOf<List<SharedEntry>>(emptyList())
    private var message by mutableStateOf("Find your Windows device to start")
    private var operationBusy by mutableStateOf(false)
    private var busy: Boolean
        get() = operationBusy || TransferRuntime.running
        set(value) { operationBusy = value }
    private var progress by mutableFloatStateOf(0f)
    private var pendingTransfer: TransferJob?
        get() = TransferRuntime.job
        set(value) { TransferRuntime.job = value }
    private var transferUi: TransferUi?
        get() = TransferRuntime.ui
        set(value) { TransferRuntime.ui = value }
    private val transferControl get() = TransferRuntime.control
    private var shareText by mutableStateOf("")
    private var manualIp by mutableStateOf("")
    private var pinText by mutableStateOf("")
    private var showPair by mutableStateOf(false)
    private var previewFingerprint by mutableStateOf("")
    private var fingerprintConfirmed by mutableStateOf(false)
    private val hotspotText get() = LocalNetwork.hotspotText
    private var pendingDownload: SharedEntry? = null
    private var pendingFolderDownload: SharedEntry? = null
    private val prefs by lazy { getSharedPreferences("nerqo_native", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = prefs.getBoolean("dark_mode", false)
        history = TransferHistory.load(this)
        TransferRuntime.restore(this)
        pendingTransfer?.takeIf { !TransferRuntime.running }?.let {
            transferUi = TransferUi(it.title, paused = true, detail = "Interrupted transfer · Ready to resume")
            if (history.none { entry -> entry.id == it.id && entry.status == "Completed" }) rememberTransfer(it, "Paused", "Restored after app restart")
        }
        val host = prefs.getString("host", "") ?: ""
        val storedPort = prefs.getInt("port", 44888)
        if (isPrivateIPv4(host)) {
            device = Device(prefs.getString("name", "Windows PC") ?: "Windows PC", host, storedPort)
            token = prefs.getString("token_$host", null)
            val storedCertificate = prefs.getString("cert_${host}:$storedPort", null)
            if (storedCertificate != null) {
                NativeClient.trustIdentity(device!!, storedCertificate)
                token?.let { prefs.edit().putString("saved_identity", storedCertificate).putString("saved_token", it).apply() }
            }
            else token = null
        }
        bindLocalNetwork()
        receiveShare(intent)
        setContent { NativeScreen() }
        if (prefs.getBoolean("disconnected", false)) token = null
        if (device != null && token != null) refresh()
        scan()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }
    private fun receiveShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            if (intent.type == "text/plain") shareText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            else {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) submitFiles(listOf(uri))
            }
        } else if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION") val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (uris != null) submitFiles(uris)
        }
    }
    private fun task(action: () -> Unit) {
        if (busy) return
        busy = true
        TransferRuntime.message = ""
        executor.execute {
            try { bindLocalNetwork(); action() }
            catch (e: Exception) { runOnUiThread { if (e is IOException) connected = false; message = e.message ?: "Connection failed" } }
            finally { runOnUiThread { busy = false; progress = 0f } }
        }
    }
    private fun bindLocalNetwork() { runCatching { LocalNetwork.bind(this) } }
    private fun scan() = task {
        bindLocalNetwork()
        runOnUiThread { message = "Finding devices on local Wi-Fi…" }
        val result = NativeClient.discover()
        runOnUiThread {
            nearby = result
            message = if (result.isEmpty()) "No devices found. Check same Wi-Fi, Windows Firewall or use IP below." else "${result.size} device(s) found nearby"
        }
    }
    private fun select(found: Device) {
        if (TransferRuntime.running) { message = "Pause the transfer before reconnecting"; return }
        sessionGeneration++
        device = found
        connected = false
        token = null
        entries = emptyList()
        previewFingerprint = ""
        fingerprintConfirmed = false
        prefs.edit().putString("host", found.address).putInt("port", found.port).putString("name", found.name).apply()
        task {
            val discovered = NativeClient.previewIdentity(found)
            val stored = prefs.getString("cert_${found.address}:${found.port}", null)
                ?: prefs.getString("saved_identity", null)
            if (stored != null && stored == discovered) {
                NativeClient.trustIdentity(found, stored)
                val access = prefs.getString("token_${found.address}", null) ?: if (prefs.getString("saved_identity", null) == discovered) prefs.getString("saved_token", null) else null
                if (pendingTransfer != null && pendingTransfer!!.fingerprint != discovered) throw IOException("Saved transfer belongs to another computer")
                if (access != null) try { NativeClient.reconnect(found, access) } catch (_: PairingExpiredException) {
                    prefs.edit().remove("token_${found.address}").remove("saved_token").apply()
                    runOnUiThread { previewFingerprint = discovered; showPair = true; message = "Pairing expired. Verify identity and pair again." }
                    return@task
                }
                if (access != null) {
                    prefs.edit().putString("cert_${found.address}:${found.port}", discovered).putString("token_${found.address}", access)
                        .putBoolean("disconnected", false).apply()
                    pendingTransfer?.let { previous ->
                        val moved = previous.copy(host = found.address, port = found.port); moved.save(this)
                        runOnUiThread { pendingTransfer = moved }
                    }
                }
                val fresh = try { if (access != null) NativeClient.list(found, access) else emptyList() }
                    catch (_: PairingExpiredException) { prefs.edit().remove("token_${found.address}").apply(); emptyList() }
                val approvedToken = if (access != null && prefs.getString("token_${found.address}", null) == access) access else null
                runOnUiThread { connected = approvedToken != null; token = approvedToken; entries = fresh; message = if (approvedToken == null) "Pair this device again" else "Verified secure Windows connection"; if (approvedToken == null) { previewFingerprint = discovered; showPair = true } }
            } else {
                runOnUiThread {
                    token = null
                    previewFingerprint = discovered
                    showPair = true
                    message = if (stored == null) "Confirm the Windows identity before first pairing" else "Windows identity changed! Check before pairing again."
                }
            }
        }
    }
    private fun pair() {
        val current = device ?: return
        if (!pinText.matches(Regex("[0-9]{6}"))) { message = "Enter the six-digit code shown on Windows"; return }
        if (previewFingerprint.length != 64 || !fingerprintConfirmed) { message = "Verify the Windows identity first"; return }
        val confirmedIdentity = previewFingerprint
        task {
            NativeClient.trustIdentity(current, confirmedIdentity)
            val saved = NativeClient.pair(current, pinText)
            prefs.edit().putString("cert_${current.address}:${current.port}", confirmedIdentity)
                .putString("token_${current.address}", saved).putString("saved_identity", confirmedIdentity).putString("saved_token", saved).putBoolean("disconnected", false).apply()
            runOnUiThread { connected = true; token = saved; showPair = false; pinText = ""; fingerprintConfirmed = false; message = "Securely paired with ${current.name}" }
            val fresh = NativeClient.list(current, saved)
            runOnUiThread { entries = fresh }
        }
    }
    private fun refresh(silent: Boolean = false) {
        val d = device ?: return
        val access = token ?: return
        if (busy || polling) return
        val generation = sessionGeneration
        polling = true
        if (!silent) busy = true
        executor.execute {
            try {
                bindLocalNetwork()
                val fresh = NativeClient.list(d, access)
                ReceiptOutbox.schedule(this)
                runOnUiThread {
                    if (generation == sessionGeneration && !isDestroyed) {
                        connected = true
                        if (entries != fresh) entries = fresh
                        if (!silent) message = "Connected to ${d.name}"
                    }
                }
            } catch (_: ConnectionPausedException) {
                runOnUiThread { if (generation == sessionGeneration && !isDestroyed) { connected = false; entries = emptyList(); message = "Disconnected. Choose Reconnect to continue." } }
            } catch (_: PairingExpiredException) {
                prefs.edit().remove("token_${d.address}").apply()
                runOnUiThread { if (generation == sessionGeneration && !isDestroyed) {
                    connected = false; token = null; entries = emptyList(); showPair = false
                    message = "Disconnected from Windows. Scan the pairing QR to reconnect."
                } }
            } catch (e: Exception) {
                runOnUiThread { if (generation == sessionGeneration && !isDestroyed) {
                    connected = false
                    if (!silent) message = e.message ?: "Computer unavailable"
                } }
            } finally {
                runOnUiThread { polling = false; if (!silent) busy = false }
            }
        }
    }
    private fun disconnect() {
        val d = device ?: return
        val access = token ?: return
        sessionGeneration++
        TransferRuntime.message = ""
        transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
        connected = false; token = null; entries = emptyList(); showPair = false
        prefs.edit().putBoolean("disconnected", true).apply()
        message = "Disconnected. Any unfinished transfer is saved. Wi-Fi stays connected."
        executor.execute {
            // Best effort server suspension; local disconnect always succeeds, even offline.
            runCatching { bindLocalNetwork(); NativeClient.disconnect(d, access) }
        }
    }
    private fun reconnect() {
        val current = device ?: return
        val identity = prefs.getString("cert_${current.address}:${current.port}", null) ?: prefs.getString("saved_identity", null)
        val savedAccess = prefs.getString("token_${current.address}", null)
            ?: if (prefs.getString("saved_identity", null) == identity) prefs.getString("saved_token", null) else null
        if (identity == null || savedAccess == null) { message = "Scan the pairing QR to restore access"; return }
        sessionGeneration++
        task {
            var last: Exception? = null
            val candidates = (listOf(current) + NativeClient.discover()).distinctBy { it.endpoint }
            for (candidate in candidates) try {
                // A discovery name or IP never authorizes sending saved credentials.
                if (NativeClient.previewIdentity(candidate) != identity) continue
                require(pendingTransfer == null || pendingTransfer!!.fingerprint == identity) { "Saved transfer belongs to another computer" }
                NativeClient.trustIdentity(candidate, identity)
                NativeClient.reconnect(candidate, savedAccess)
                val fresh = NativeClient.list(candidate, savedAccess)
                prefs.edit().putString("host", candidate.address).putInt("port", candidate.port).putString("name", candidate.name)
                    .putString("cert_${candidate.address}:${candidate.port}", identity).putString("token_${candidate.address}", savedAccess)
                    .putString("saved_identity", identity).putString("saved_token", savedAccess).putBoolean("disconnected", false).apply()
                val moved = pendingTransfer?.copy(host = candidate.address, port = candidate.port)
                moved?.save(this)
                ReceiptOutbox.schedule(this)
                runOnUiThread { pendingTransfer = moved; device = candidate; token = savedAccess; entries = fresh; connected = true; message = "Reconnected securely to ${candidate.name}" }
                return@task
            } catch (error: PairingExpiredException) {
                runOnUiThread { device = candidate; token = null; connected = false; previewFingerprint = identity; showPair = true; message = "Pairing expired. Verify identity and pair again." }
                return@task
            } catch (error: Exception) { last = error }
            throw IOException("Saved computer unavailable. Check Wi-Fi/Hotspot and Windows receiver, then Reconnect. " + (last?.message ?: ""))
        }
    }
    private fun forget() {
        TransferRuntime.message = ""
        val current = device ?: return
        AlertDialog.Builder(this).setTitle("Forget this computer?")
            .setMessage("Saved access will be removed. Pair again with the Windows QR. Cancel unfinished transfers first.")
            .setNegativeButton("Keep", null).setPositiveButton("Forget") { _, _ ->
                if (pendingTransfer != null || busy) { message = "Cancel the saved transfer before forgetting this computer"; return@setPositiveButton }
                val old = prefs.getString("token_${current.address}", null)
                val identity = prefs.getString("cert_${current.address}:${current.port}", null)
                val edit = prefs.edit().remove("token_${current.address}").remove("saved_token").remove("saved_identity").remove("host")
                prefs.all.filter { it.key.startsWith("cert_") && it.value == identity }.keys.forEach { key ->
                    edit.remove(key).remove("token_" + key.removePrefix("cert_").substringBefore(':'))
                }
                edit.apply()
                token = null; connected = false; device = null; entries = emptyList()
                if (old != null) executor.execute { runCatching { NativeClient.forget(current, old) } }
                message = "Computer forgotten. Pair again to restore access."
            }.show()
    }
    private fun sendText() {
        val d = device ?: return
        val access = token ?: run { showPair = true; return }
        val input = shareText.trim()
        if (input.isEmpty()) return
        task {
            NativeClient.sendNote(d, access, input)
            val fresh = NativeClient.list(d, access)
            runOnUiThread { shareText = ""; entries = fresh; message = "Text sent to Windows" }
        }
    }
    private fun keepPermission(uri: Uri, write: Boolean = false) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    }
    private fun rememberTransfer(job: TransferJob, status: String, detail: String = "") {
        val saved = runCatching { TransferHistory.record(this, job, status, detail) }.getOrDefault(false)
        val fresh = TransferHistory.load(this)
        runOnUiThread { if (!isDestroyed) {
            history = fresh
            if (!saved) Toast.makeText(this, "Could not save transfer history", Toast.LENGTH_SHORT).show()
        } }
    }
    private fun createTransfer(kind: String, title: String, uris: List<Uri> = emptyList(),
                               target: Uri? = null, entry: SharedEntry? = null) {
        if (pendingTransfer != null || busy) { message = "Resume or cancel the current transfer first"; return }
        val d = device ?: run { message = "Pair with Windows first"; return }
        if (token == null) { message = "Pair with Windows first"; return }
        val fingerprint = prefs.getString("cert_${d.address}:${d.port}", null) ?: return
        uris.forEach { keepPermission(it) }
        target?.let { keepPermission(it, write = true) }
        val job = TransferJob(kind = kind, title = title, host = d.address, port = d.port,
            deviceName = d.name, fingerprint = fingerprint, uris = uris.map { it.toString() },
            target = target?.toString() ?: "", entry = entry)
        try {
            job.save(this); pendingTransfer = job
            rememberTransfer(job, "Queued")
            transferUi = TransferUi(job.title, paused = true, detail = "Ready to start")
            resumeTransfer()
        }
        catch (error: Exception) { message = error.message ?: "Could not save transfer" }
    }
    private fun submitFiles(uris: List<Uri>) {
        fun name(uri: Uri): String = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "file"
        if (uris.isNotEmpty()) createTransfer("uploadFiles", uris.take(3).joinToString(", ") { name(it) } + if (uris.size > 3) " +${uris.size - 3} more" else "", uris)
    }
    private fun submitFolder(treeUri: Uri) = createTransfer("uploadFolder", "Send folder: " + (androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)?.name ?: "folder"), listOf(treeUri))
    private fun download(entry: SharedEntry, target: Uri) = createTransfer("downloadFile", "Save " + entry.content, target = target, entry = entry)
    private fun receiveFolder(entry: SharedEntry, target: Uri) = createTransfer("downloadFolder", "Save folder: " + entry.content, target = target, entry = entry)

    private fun clearHistory() {
        AlertDialog.Builder(this).setTitle("Clear transfer history?").setMessage("Your files and current transfer are kept.")
            .setNegativeButton("Keep", null).setPositiveButton("Clear history") { _, _ ->
                if (TransferHistory.clear(this)) history = emptyList() else message = "Could not clear history"
            }.show()
    }
    private fun openHistory(entry: HistoryEntry) {
        val job = TransferJob.decode(entry.job) ?: return
        if (job.target.isBlank()) { message = "This operation has no saved destination on this phone"; return }
        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.target), "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: Exception) { message = "No app can open this saved document. Use your Files app." }
    }
    private fun retryHistory(entry: HistoryEntry) {
        if (busy || (pendingTransfer != null && pendingTransfer?.id != entry.id)) { message = "Resume or cancel the current transfer first"; return }
        val job = TransferJob.decode(entry.job) ?: run { message = "Retry details are unavailable for this older entry"; return }
        val current = device ?: run { message = "Reconnect with Windows first"; return }
        if (token == null || prefs.getString("cert_${current.address}:${current.port}", null) != job.fingerprint) { message = "Reconnect to the original computer first"; return }
        val retry = job.copy(host = current.address, port = current.port)
        try { retry.save(this); pendingTransfer = retry; resumeTransfer() }
        catch (e: Exception) { message = e.message ?: "Could not retry transfer" }
    }
    private fun pauseTransfer() {
        transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
        transferUi = transferUi?.copy(detail = "Pausing at the last safe checkpoint…")
    }
    private fun cancelTransfer() {
        val job = pendingTransfer ?: return
        if (busy && transferControl == null) { message = "Connection check in progress. Try Cancel again shortly."; return }
        if (busy) {
            transferControl?.interrupt(TransferStop.CANCEL, closeAsync = true)
            transferUi = transferUi?.copy(detail = "Cancelling… Completed files will be kept.")
        } else {
            // Run cleanup on the same serial executor, with no second document writer.
            resumeTransfer(cancelOnly = true)
        }
    }
    private fun resumeTransfer(cancelOnly: Boolean = false) {
        if (pendingTransfer == null || busy || TransferControl.workerActive.get()) return
        TransferRuntime.message = ""
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notification_asked", false)) {
                prefs.edit().putBoolean("notification_asked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 482)
            }
            startForegroundService(Intent(this, TransferService::class.java).setAction(if (cancelOnly) "cancel" else "resume"))
        } catch (error: Exception) { message = error.message ?: "Could not start transfer service" }
    }
    private fun isPrivateIPv4(raw: String): Boolean {
        return try {
            if (!raw.matches(Regex("[0-9.]{7,15}"))) return false
            val a = InetAddress.getByName(raw)
            if (a !is Inet4Address) return false
            val octets = a.address.map { it.toInt() and 0xff }
            octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) || (octets[0] == 169 && octets[1] == 254)
        } catch (_: Exception) { false }
    }
    private fun scanPairQr() {
        if (busy) { message = "Pause the current transfer before scanning a QR"; return }
        val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan a Windows Wi-Fi QR or the Nerqo pairing QR")
            .setBeepEnabled(false).setCaptureActivity(PortraitCaptureActivity::class.java).setOrientationLocked(true)
        qrLauncher.launch(options)
    }
    private fun joinWifiQr(raw: String) {
        if (Build.VERSION.SDK_INT < 29) {
            message = "Open Wi-Fi settings to join the laptop hotspot, then scan its Nerqo QR."
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); return
        }
        if (pendingTransfer != null || busy) { message = "Pause and finish or cancel the saved transfer before changing Wi-Fi"; return }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            pendingWifiQr = raw
            requestPermissions(arrayOf(permission), 481); return
        }
        if (raw.length > 2048) { message = "Wi-Fi QR is too long"; return }
        val parsed = com.google.zxing.client.result.WifiResultParser().parse(
            com.google.zxing.Result(raw, null, null, com.google.zxing.BarcodeFormat.QR_CODE))
        if (parsed == null || parsed.ssid.isNullOrBlank() || parsed.ssid.toByteArray().size > 32) {
            message = "Invalid Wi-Fi QR"; return
        }
        val auth = parsed.networkEncryption?.uppercase() ?: "NOPASS"
        if (auth !in listOf("WPA", "WPA2", "WPA3", "SAE")) {
            message = "Use a WPA2/WPA3 secured hotspot. This QR cannot be joined here."; return
        }
        AlertDialog.Builder(this).setTitle("Connect to laptop hotspot?")
            .setMessage("Join ${parsed.ssid}? Android will ask you to approve the Wi-Fi connection. Afterwards scan the Nerqo pairing QR.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                try {
                    val builder = android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(parsed.ssid).setIsHiddenSsid(parsed.isHidden)
                    if (auth == "WPA3" || auth == "SAE") builder.setWpa3Passphrase(parsed.password ?: "")
                    else builder.setWpa2Passphrase(parsed.password ?: "")
                    connected = false
                    LocalNetwork.join(this, builder.build())
                    message = "Approve Android's Wi-Fi connection request."
                } catch (_: Exception) { message = "Cannot join this hotspot automatically. Use Wi-Fi settings." }
            }.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 481) {
            val raw = pendingWifiQr; pendingWifiQr = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && raw != null) joinWifiQr(raw)
            else message = "Wi-Fi permission was declined. You can connect from Wi-Fi settings."
        }
    }
    private fun pairFromQr(raw: String) {
        if (raw.length > 2048) { message = "QR code is too long"; return }
        val json = try { JSONObject(raw) } catch (_: Exception) { message = "Not a Nerqo pairing QR code"; return }
        if (json.optInt("nerqo") != 1 || json.optInt("port") != 44888) { message = "Unsupported Nerqo pairing code"; return }
        val fp = json.optString("fingerprint").lowercase()
        val pin = json.optString("pin")
        val name = json.optString("name", "Windows PC").take(80)
        val addresses = json.optJSONArray("addresses")
        if (!fp.matches(Regex("[0-9a-f]{64}")) || !pin.matches(Regex("[0-9]{6}")) || addresses == null) {
            message = "Invalid pairing QR"; return
        }
        val candidates = (0 until minOf(addresses.length(), 6)).mapNotNull { i ->
            val ip = addresses.optString(i)
            if (isPrivateIPv4(ip)) Device(name, ip) else null
        }.distinctBy { it.address }
        if (candidates.isEmpty()) { message = "No local Windows address in QR"; return }
        val saved = pendingTransfer
        if (saved != null && (saved.fingerprint != fp || candidates.none { it.address == saved.host && it.port == saved.port })) {
            message = "This saved transfer belongs to another computer or address. Cancel it before switching devices."; return
        }
        task {
            var lastFailure: Exception? = null
            for (candidate in candidates.filter { saved == null || it.address == saved.host }) {
                try {
                    bindLocalNetwork()
                    val presented = NativeClient.previewIdentity(candidate)
                    if (presented != fp) throw IOException("Certificate did not match the QR code")
                    NativeClient.trustIdentity(candidate, fp)
                    val newToken = NativeClient.pair(candidate, pin)
                    val newEntries = NativeClient.list(candidate, newToken)
                    prefs.edit().putString("host", candidate.address).putInt("port", candidate.port)
                        .putString("name", candidate.name)
                        .putString("cert_${candidate.address}:${candidate.port}", fp)
                        .putString("token_${candidate.address}", newToken).putString("saved_identity", fp).putString("saved_token", newToken).putBoolean("disconnected", false).apply()
                    runOnUiThread {
                        connected = true; device = candidate; token = newToken; entries = newEntries; nearby = listOf(candidate)
                        showPair = false; previewFingerprint = ""; fingerprintConfirmed = false
                        message = "Securely connected by QR to ${candidate.name}"
                    }
                    return@task
                } catch (e: Exception) { lastFailure = e }
            }
            throw IOException("QR pairing failed on this network. Make sure the phone and PC are on the same Wi-Fi and allow Nerqo through Windows Firewall. ${lastFailure?.message ?: ""}")
        }
    }
    private fun manualConnect() {
        val input = manualIp.trim().removePrefix("https://").removePrefix("http://")
        val parts = input.split(":")
        val port = if (parts.size == 2) parts[1].toIntOrNull() ?: -1 else 44888
        if (parts.size > 2 || !isPrivateIPv4(parts[0]) || port !in 1..65535) {
            message = "Use your laptop's private IP, e.g. 192.168.1.5"; return
        }
        select(Device("Windows PC", parts[0], port))
    }
    private fun wifiText(): String = try {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") val info = manager.connectionInfo
        val frequency = info?.frequency ?: -1
        val band = when (frequency) { in 2400..2500 -> "2.4 GHz"; in 4900..5900 -> "5 GHz"; in 5925..7125 -> "6 GHz"; else -> "Unknown band" }
        @Suppress("DEPRECATION") val speed = info?.linkSpeed ?: -1
        if (speed > 0) "$band  ·  ${speed} Mbps link" else band
    } catch (_: Exception) { "Band unavailable (enable Wi-Fi / location permission)" }
    private var preferFiveGHz by mutableStateOf(false)
    private fun hotspot() {
        if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 479)
            message = "Grant location permission for the local hotspot, then try again"; return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 480)
            message = "Grant nearby Wi-Fi permission, then start hotspot again"; return
        }
        try { LocalNetwork.hotspot(this, preferFiveGHz) }
        catch (e: Exception) { message = e.message ?: "Hotspot unavailable" }
    }
    override fun onDestroy() {
        pendingWifiQr = null
        executor.shutdownNow()
        super.onDestroy()
    }

    @Composable private fun NativeScreen() {
        SideEffect {
            val bars = if (darkMode) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) submitFiles(uris) }
        val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) submitFolder(uri) }
        val folderReceiverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val entry = pendingFolderDownload
            pendingFolderDownload = null
            if (uri != null && entry != null) receiveFolder(entry, uri)
        }
        val writer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val entry = pendingDownload
            pendingDownload = null
            if (uri != null && entry != null) download(entry, uri)
        }
        LaunchedEffect(device?.address, token) {
            while (true) {
                delay(7000)
                // A refresh also owns the serial worker and disables Resume.
                // Keep recovery controls available while a saved job exists.
                if (device != null && token != null && !busy && pendingTransfer == null) refresh(silent = true)
            }
        }
        NerqoTheme(darkMode) {
            NerqoScreen(
                onHistoryOpen = ::openHistory, onHistoryRetry = ::retryHistory, onHistoryClear = ::clearHistory,
                deviceName = device?.name, connected = connected, paired = connected && token != null, history = history,
                nearby = nearby, entries = entries, busy = busy, progress = progress,
                message = if (TransferRuntime.message.isNotBlank()) TransferRuntime.message else message, network = wifiText(), hotspotText = hotspotText,
                transfer = transferUi, onPause = ::pauseTransfer,
                onResume = { resumeTransfer() }, onCancel = ::cancelTransfer,
                shareText = shareText, manualIp = manualIp, darkMode = darkMode,
                onShareText = { shareText = it }, onManualIp = { manualIp = it },
                onTheme = { darkMode = it; prefs.edit().putBoolean("dark_mode", it).apply() },
                onScanQr = ::scanPairQr, onDiscover = ::scan, onSelectDevice = ::select,
                onManualConnect = ::manualConnect, onRefresh = { refresh() }, onDisconnect = ::disconnect, onReconnect = ::reconnect, onForget = ::forget, savedConnection = device != null,
                onFiles = { picker.launch(arrayOf("*/*")) }, onFolder = { folderPicker.launch(null) },
                onSendText = ::sendText, onPaste = {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    shareText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
                },
                onEntry = { item ->
                    when (item.kind) {
                        "file" -> { pendingDownload = item; writer.launch(item.content) }
                        "folder" -> { pendingFolderDownload = item; folderReceiverPicker.launch(null) }
                        else -> {
                            val clip = android.content.ClipData.newPlainText("Nerqo", item.content)
                            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, onHotspot = ::hotspot, preferFiveGHz = preferFiveGHz, onFiveGHz = { preferFiveGHz = it },
                onStopHotspot = { if (!busy) LocalNetwork.stopHotspot() },
                onWifiSettings = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                onUpdates = { AppUpdater.check(this) }
            )
            if (showPair) androidx.compose.material3.AlertDialog(
                onDismissRequest = { if (!busy) showPair = false },
                title = { Text("Verify your computer") },
                text = { Column {
                    Text("Match this security identity with Nerqo on Windows before entering the pairing code.")
                    Spacer(Modifier.height(16.dp))
                    Text(previewFingerprint.take(12).uppercase(), fontWeight = FontWeight.Bold, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = fingerprintConfirmed, onCheckedChange = { fingerprintConfirmed = it })
                        Text("Identity matches on both devices", fontSize = 13.sp)
                    }
                    OutlinedTextField(value = pinText, onValueChange = { pinText = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit pairing code") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                } },
                confirmButton = { Button(onClick = ::pair, enabled = !busy && fingerprintConfirmed && previewFingerprint.length == 64 && pinText.length == 6) { Text("Pair securely") } },
                dismissButton = { TextButton(onClick = { showPair = false }, enabled = !busy) { Text("Cancel") } }
            )
        }
    }
}
