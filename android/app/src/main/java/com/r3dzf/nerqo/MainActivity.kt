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
    private var requestedWifi: android.net.Network? = null
    private var wifiRequest: ConnectivityManager.NetworkCallback? = null
    private var pendingWifiQr: String? = null
    private var sessionGeneration = 0
    private var polling = false
    private val executor = Executors.newSingleThreadExecutor()
    private var connected by mutableStateOf(false)
    private var darkMode by mutableStateOf(false)
    private var device by mutableStateOf<Device?>(null)
    private var token by mutableStateOf<String?>(null)
    private var nearby by mutableStateOf<List<Device>>(emptyList())
    private var entries by mutableStateOf<List<SharedEntry>>(emptyList())
    private var message by mutableStateOf("Find your Windows device to start")
    private var busy by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var pendingTransfer by mutableStateOf<TransferJob?>(null)
    private var transferUi by mutableStateOf<TransferUi?>(null)
    private var transferControl: TransferControl? = null
    private var shareText by mutableStateOf("")
    private var manualIp by mutableStateOf("")
    private var pinText by mutableStateOf("")
    private var showPair by mutableStateOf(false)
    private var previewFingerprint by mutableStateOf("")
    private var fingerprintConfirmed by mutableStateOf(false)
    private var hotspotText by mutableStateOf("")
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var pendingDownload: SharedEntry? = null
    private var pendingFolderDownload: SharedEntry? = null
    private val prefs by lazy { getSharedPreferences("nerqo_native", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = prefs.getBoolean("dark_mode", false)
        pendingTransfer = TransferJob.load(this)
        pendingTransfer?.let { transferUi = TransferUi(it.title, paused = true, detail = "Interrupted transfer · Ready to resume") }
        val host = prefs.getString("host", "") ?: ""
        val storedPort = prefs.getInt("port", 44888)
        if (isPrivateIPv4(host)) {
            device = Device(prefs.getString("name", "Windows PC") ?: "Windows PC", host, storedPort)
            token = prefs.getString("token_$host", null)
            val storedCertificate = prefs.getString("cert_${host}:$storedPort", null)
            if (storedCertificate != null) NativeClient.trustIdentity(device!!, storedCertificate)
            else token = null
        }
        bindLocalNetwork()
        receiveShare(intent)
        setContent { NativeScreen() }
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
        executor.execute {
            try { bindLocalNetwork(); action() }
            catch (e: Exception) { runOnUiThread { if (e is IOException) connected = false; message = e.message ?: "Connection failed" } }
            finally { runOnUiThread { busy = false; progress = 0f } }
        }
    }
    private fun bindLocalNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (hotspotReservation != null) { cm.bindProcessToNetwork(null); return }
            requestedWifi?.let { cm.bindProcessToNetwork(it); return }
            val wifi = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            // Some offline Wi-Fi connections are not Android's default network.
            // Bind only while this transfer app is active; restore on exit.
            cm.bindProcessToNetwork(wifi)
        } catch (_: Exception) { }
    }
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
        if (pendingTransfer != null) { message = "Resume or cancel the transfer before changing computers"; return }
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
            if (stored != null && stored == discovered) {
                NativeClient.trustIdentity(found, stored)
                val access = prefs.getString("token_${found.address}", null)
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
                .putString("token_${current.address}", saved).apply()
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
                runOnUiThread {
                    if (generation == sessionGeneration && !isDestroyed) {
                        connected = true
                        if (entries != fresh) entries = fresh
                        if (!silent) message = "Connected to ${d.name}"
                    }
                }
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
        transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
        connected = false; token = null; entries = emptyList(); showPair = false
        prefs.edit().remove("token_${d.address}").apply()
        message = "Disconnected. Any unfinished transfer is saved. Wi-Fi stays connected."
        executor.execute {
            // Best effort server revocation; local disconnect always succeeds, even offline.
            runCatching { bindLocalNetwork(); NativeClient.disconnect(d, access) }
        }
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
            transferUi = TransferUi(job.title, paused = true, detail = "Ready to start")
            resumeTransfer()
        }
        catch (error: Exception) { message = error.message ?: "Could not save transfer" }
    }
    private fun submitFiles(uris: List<Uri>) {
        if (uris.isNotEmpty()) createTransfer("uploadFiles", "Send ${uris.size} file(s) to Windows", uris)
    }
    private fun submitFolder(treeUri: Uri) = createTransfer("uploadFolder", "Send folder to Windows", listOf(treeUri))
    private fun download(entry: SharedEntry, target: Uri) = createTransfer("downloadFile", "Save " + entry.content, target = target, entry = entry)
    private fun receiveFolder(entry: SharedEntry, target: Uri) = createTransfer("downloadFolder", "Save folder: " + entry.content, target = target, entry = entry)

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
        val job = pendingTransfer ?: return
        if (busy || isDestroyed) return
        if (!TransferControl.workerActive.compareAndSet(false, true)) {
            message = "The previous transfer is still closing. Try Resume again shortly."; return
        }
        val control = TransferControl { label ->
            runOnUiThread { if (!isDestroyed && pendingTransfer?.id == job.id) transferUi = (transferUi ?: TransferUi(job.title)).copy(detail = label) }
        }
        transferControl = control
        busy = true
        transferUi = TransferUi(job.title, detail = if (cancelOnly) "Cancelling…" else "Checking saved transfer…")
        executor.execute {
            val destination = Device(job.deviceName, job.host, job.port)
            val access = prefs.getString("token_${job.host}", null)
            try {
                bindLocalNetwork()
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
                    runOnUiThread { if (!isDestroyed && pendingTransfer?.id == job.id) {
                        progress = fraction; transferUi = TransferUi(job.title, detail = detail, fraction = fraction)
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
                TransferJob.clear(this)
                runCatching { NativeClient.cleanSession(this, destination, access, job.id, cancel = false) }
                runOnUiThread { if (!isDestroyed) { pendingTransfer = null; transferUi = null; message = "Transfer completed and SHA-256 verified" } }
            } catch (error: Exception) {
                if (cancelOnly || control.stop == TransferStop.CANCEL || error is TransferCancelled) {
                    NativeClient.cleanSession(this, destination, access, job.id, cancel = true)
                    TransferJob.clear(this)
                    runOnUiThread { if (!isDestroyed) { pendingTransfer = null; transferUi = null; message = "Transfer cancelled. Completed files were kept." } }
                } else {
                    runOnUiThread { if (!isDestroyed) {
                        transferUi = (transferUi ?: TransferUi(job.title)).copy(paused = true, detail = if (control.stop == TransferStop.PAUSE || error is TransferPaused) "Paused · Resume from the saved checkpoint" else "Interrupted · " + (error.message ?: "Try again"))
                        message = "Transfer saved. You can resume it or cancel."
                    } }
                }
            } finally {
                TransferControl.workerActive.set(false)
                runOnUiThread { if (!isDestroyed) { busy = false; transferControl = null; progress = 0f } }
            }
        }
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
                    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    wifiRequest?.let { cm.unregisterNetworkCallback(it) }
                    requestedWifi = null
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: android.net.Network) {
                            requestedWifi = network; cm.bindProcessToNetwork(network)
                            runOnUiThread { if (!isDestroyed) message = "Hotspot joined. Now scan the Nerqo pairing QR on Windows." }
                        }
                        override fun onUnavailable() {
                            runOnUiThread { if (!isDestroyed) message = "Wi-Fi connection was declined or unavailable. Retry or use Wi-Fi settings." }
                        }
                        override fun onLost(network: android.net.Network) {
                            if (requestedWifi == network) {
                                requestedWifi = null; cm.bindProcessToNetwork(null)
                                runOnUiThread { if (!isDestroyed) { connected = false; message = "Hotspot connection lost. Rejoin the network." } }
                            }
                        }
                    }
                    wifiRequest = callback
                    val request = android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(builder.build()).build()
                    cm.requestNetwork(request, callback)
                    message = "Approve Android's Wi-Fi connection request."
                } catch (_: Exception) { message = "Cannot join this hotspot automatically. Use Wi-Fi settings." }
            }.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
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
            for (candidate in candidates) {
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
                        .putString("token_${candidate.address}", newToken).apply()
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
    private fun hotspot() {
        if (Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 479)
            message = "Grant location permission for the local hotspot, then try again"; return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES), 480)
            message = "Grant nearby Wi-Fi permission, then start hotspot again"; return
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            hotspotReservation?.close()
            val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    if (Build.VERSION.SDK_INT >= 30) {
                        val cfg = reservation.softApConfiguration
                        hotspotText = "Connect your laptop to ${cfg.ssid}  |  Password: ${cfg.passphrase ?: "See Wi-Fi settings"}"
                    } else {
                        @Suppress("DEPRECATION") val cfg = reservation.wifiConfiguration
                        hotspotText = "Connect laptop to ${cfg?.SSID ?: "the Android hotspot"}  |  Password: ${cfg?.preSharedKey ?: "See Wi-Fi settings"}"
                    }
                    message = "Offline hotspot active. Band is chosen by Android/device."
                }
                override fun onStopped() { hotspotText = ""; message = "Hotspot stopped" }
                override fun onFailed(reason: Int) { hotspotText = ""; message = "Hotspot unavailable ($reason). Use system hotspot settings." }
            }
            // Use Android's generated WPA2/WPA3 credentials. Never create an open AP
            // just to request a frequency band unsupported by the public API.
            wifi.startLocalOnlyHotspot(callback, null)
        } catch (e: Exception) {
            message = "Hotspot requires device support: ${e.message}"
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
    override fun onDestroy() {
        transferControl?.interrupt(TransferStop.PAUSE, closeAsync = true)
        hotspotReservation?.close()
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
        pendingWifiQr = null
        wifiRequest?.let { runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } }
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
                deviceName = device?.name, connected = connected, paired = token != null,
                nearby = nearby, entries = entries, busy = busy, progress = progress,
                message = message, network = wifiText(), hotspotText = hotspotText,
                transfer = transferUi, onPause = ::pauseTransfer,
                onResume = { resumeTransfer() }, onCancel = ::cancelTransfer,
                shareText = shareText, manualIp = manualIp, darkMode = darkMode,
                onShareText = { shareText = it }, onManualIp = { manualIp = it },
                onTheme = { darkMode = it; prefs.edit().putBoolean("dark_mode", it).apply() },
                onScanQr = ::scanPairQr, onDiscover = ::scan, onSelectDevice = ::select,
                onManualConnect = ::manualConnect, onRefresh = { refresh() }, onDisconnect = ::disconnect,
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
                }, onHotspot = ::hotspot,
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
