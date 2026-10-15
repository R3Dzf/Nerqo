package com.r3dzf.nerqo

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    private val executor = Executors.newSingleThreadExecutor()
    private var device by mutableStateOf<Device?>(null)
    private var token by mutableStateOf<String?>(null)
    private var nearby by mutableStateOf<List<Device>>(emptyList())
    private var entries by mutableStateOf<List<SharedEntry>>(emptyList())
    private var message by mutableStateOf("Find your Windows device to start")
    private var busy by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var shareText by mutableStateOf("")
    private var manualIp by mutableStateOf("")
    private var pinText by mutableStateOf("")
    private var showPair by mutableStateOf(false)
    private var hotspotText by mutableStateOf("")
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var pendingDownload: SharedEntry? = null
    private val prefs by lazy { getSharedPreferences("nerqo_native", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = prefs.getString("host", "") ?: ""
        val storedPort = prefs.getInt("port", 44888)
        if (isPrivateIPv4(host)) {
            device = Device(prefs.getString("name", "Windows PC") ?: "Windows PC", host, storedPort)
            token = prefs.getString("token_$host", null)
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
            try { action() }
            catch (e: Exception) { runOnUiThread { message = e.message ?: "Connection failed" } }
            finally { runOnUiThread { busy = false } }
        }
    }
    private fun bindLocalNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            // Some offline Wi-Fi connections are not Android's default network.
            // Bind only while this transfer app is active; restore on exit.
            if (wifi != null) cm.bindProcessToNetwork(wifi)
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
        device = found; token = prefs.getString("token_${found.address}", null)
        prefs.edit().putString("host", found.address).putInt("port", found.port).putString("name", found.name).apply()
        if (token != null) refresh() else showPair = true
    }
    private fun pair() {
        val current = device ?: return
        if (!pinText.matches(Regex("[0-9]{6}"))) { message = "Enter the 6-digit code displayed on Windows"; return }
        task {
            val saved = NativeClient.pair(current, pinText)
            prefs.edit().putString("token_${current.address}", saved).apply()
            runOnUiThread { token = saved; showPair = false; pinText = ""; message = "Paired with ${current.name}" }
            val fresh = NativeClient.list(current, saved)
            runOnUiThread { entries = fresh }
        }
    }
    private fun refresh() {
        val d = device ?: return
        val access = token ?: run { showPair = true; return }
        task {
            val fresh = NativeClient.list(d, access)
            runOnUiThread { entries = fresh; message = "Connected to ${d.name}" }
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
    private fun submitFiles(uris: List<Uri>) {
        val d = device
        val access = token
        if (d == null || access == null) { message = "Pair with Windows first, then choose files"; return }
        task {
            for (uri in uris) NativeClient.sendFile(this, d, access, uri) { done, total ->
                runOnUiThread { progress = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f); message = "Uploading ${"%.0f".format(progress * 100)}%" }
            }
            val fresh = NativeClient.list(d, access)
            runOnUiThread { entries = fresh; message = "Files sent successfully"; progress = 0f }
        }
    }
    private fun download(entry: SharedEntry, target: Uri) {
        val d = device ?: return
        val access = token ?: return
        task {
            NativeClient.download(this, d, access, entry, target) { done, total ->
                runOnUiThread { progress = if (total <= 0) 1f else (done.toFloat() / total).coerceIn(0f, 1f); message = "Downloading ${"%.0f".format(progress * 100)}%" }
            }
            runOnUiThread { progress = 0f; message = "Saved ${entry.content}" }
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
    private fun manualConnect() {
        val input = manualIp.trim().removePrefix("http://")
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
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    val config = SoftApConfiguration.Builder().setBand(SoftApConfiguration.BAND_5GHZ).build()
                    wifi.startLocalOnlyHotspotWithConfiguration(config, mainExecutor, callback)
                    return
                } catch (_: Exception) { /* Fallback to OS-selected band. */ }
            }
            wifi.startLocalOnlyHotspot(callback, null)
        } catch (e: Exception) {
            message = "Hotspot requires device support: ${e.message}"
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
    override fun onDestroy() {
        hotspotReservation?.close()
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    @Composable private fun NativeScreen() {
        val bg = Color(0xFF101B2C)
        val panel = Color(0xFF1D2C41)
        val accent = Color(0xFFA6ECD9)
        val muted = Color(0xFFB6C9D9)
        val palette = darkColorScheme(primary = accent, onPrimary = Color(0xFF153B32), surface = panel, background = bg, onBackground = Color.White, onSurface = Color.White)
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) submitFiles(uris) }
        val writer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val item = pendingDownload
            if (uri != null && item != null) download(item, uri)
            pendingDownload = null
        }
        LaunchedEffect(device?.address, token) {
            while (true) {
                delay(7000)
                if (device != null && token != null && !busy) refresh()
            }
        }
        if (showPair) AlertDialog(onDismissRequest = { showPair = false }, title = { Text("Pair with Windows") },
            text = { Column { Text("Enter the code shown inside the Nerqo Windows app."); Spacer(Modifier.height(12.dp)); OutlinedTextField(value = pinText, onValueChange = { pinText = it.take(6) }, label = { Text("6-digit pairing code") }, singleLine = true) } },
            confirmButton = { Button(onClick = ::pair, enabled = !busy) { Text("Pair securely") } }, dismissButton = { TextButton(onClick = { showPair = false }) { Text("Cancel") } })
        MaterialTheme(colorScheme = palette) {
            Surface(modifier = Modifier.fillMaxSize(), color = bg) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = accent, shape = RoundedCornerShape(12.dp)) { Text("↔", color = Color(0xFF153B32), fontSize = 27.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column { Text("Nerqo.", fontSize = 28.sp, fontWeight = FontWeight.Bold); Text("Move freely. Stay local.", color = muted, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(26.dp))
                    Text("OFFLINE NETWORK", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(wifiText(), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("For the highest speed, prefer a 5 GHz or 6 GHz connection. Your router/hotspot and both devices determine the band.", color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text(if (device == null || token == null) "Connect to your laptop" else "Connected to ${device?.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(message, color = muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp, bottom = 14.dp))
                            Button(onClick = ::scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("⌁   Find nearby Windows device") }
                            for (found in nearby) {
                                OutlinedButton(onClick = { select(found) }, modifier = Modifier.fillMaxWidth()) { Text("▣  ${found.name}  •  ${found.address}", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = manualIp, onValueChange = { manualIp = it }, label = { Text("Or enter local IP") }, placeholder = { Text("192.168.1.5") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            TextButton(onClick = ::manualConnect) { Text("Connect with IP →") }
                            if (device != null && token != null) OutlinedButton(onClick = ::refresh, enabled = !busy) { Text("↻  Refresh received items") }
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Send to Windows", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy && token != null) { Text("↑  Choose files") }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(value = shareText, onValueChange = { shareText = it }, label = { Text("Text or link") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                            Button(onClick = ::sendText, enabled = !busy && token != null && shareText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send text / link") }
                            if (busy && progress > 0f) { Spacer(Modifier.height(9.dp)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("Shared with you", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            if (entries.isEmpty()) Text("Your transfers and links will appear here.", color = muted, fontSize = 13.sp)
                            entries.sortedByDescending { it.id }.take(100).forEach { item ->
                                HorizontalDivider(color = Color(0xFF42566B))
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text(item.content, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text("${item.kind}  ·  ${item.sender}", color = muted, fontSize = 12.sp) }
                                    TextButton(onClick = {
                                        if (item.kind == "file") { pendingDownload = item; writer.launch(item.content) }
                                        else { val clip = android.content.ClipData.newPlainText("Nerqo", item.content); (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip); Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show() }
                                    }) { Text(if (item.kind == "file") "Save" else "Copy") }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(15.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = panel), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Text("No router?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("Create an offline local hotspot, then join it on Windows. On some phones choose 5 GHz in Android hotspot settings.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                            OutlinedButton(onClick = ::hotspot) { Text("Create offline hotspot") }
                            if (hotspotText.isNotBlank()) Text(hotspotText, color = accent, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null)
                        AppUpdater.check(this@MainActivity)
                    }) { Text("Check Android updates ↗") }
                    Text("v0.4 native preview  ·  Trusted private LAN only", color = muted, fontSize = 11.sp)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
