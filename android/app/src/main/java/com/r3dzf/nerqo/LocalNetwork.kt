package com.r3dzf.nerqo

import android.content.Context
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom

/** Application-owned Wi-Fi requests survive Activity recreation and screen closure. */
internal object LocalNetwork {
    var requested: Network? = null
    var request: ConnectivityManager.NetworkCallback? = null
    var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    var hotspotText by mutableStateOf("")
    private val main = Handler(Looper.getMainLooper())
    private fun message(text: String) { main.post { TransferRuntime.message = text } }
    fun isPrivateIPv4(raw: String): Boolean = try {
        if (!raw.matches(Regex("[0-9.]{7,15}"))) false else {
            val ip = InetAddress.getByName(raw)
            ip is Inet4Address && (ip.isSiteLocalAddress || ip.isLinkLocalAddress)
        }
    } catch (_: Exception) { false }
    fun bind(context: Context) {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val wifi = if (reservation != null) null else requested ?: cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        cm.bindProcessToNetwork(wifi)
    }
    fun join(context: Context, specifier: WifiNetworkSpecifier) {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        stopHotspot()
        request?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requested = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requested = network; cm.bindProcessToNetwork(network)
                message("Hotspot joined. Scan the Nerqo pairing QR on Windows.")
            }
            override fun onUnavailable() { message("Wi-Fi declined or unavailable. Retry or open Wi-Fi settings.") }
            override fun onLost(network: Network) {
                if (requested == network) { requested = null; cm.bindProcessToNetwork(null)
                    TransferRuntime.control?.interrupt(TransferStop.PAUSE, closeAsync = true)
                    message("Hotspot connection lost. Transfer checkpoint saved; rejoin and Resume.") }
            }
        }
        request = callback
        cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build(), callback)
    }
    fun stopHotspot() { reservation?.close(); reservation = null; hotspotText = "" }
    fun hotspot(context: Context, fiveGHz: Boolean) {
        val app = context.applicationContext
        val wifi = app.getSystemService(WifiManager::class.java)
        check(!TransferRuntime.running) { "Pause transfers before changing the network" }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        request?.let { runCatching { cm.unregisterNetworkCallback(it) } }; request = null; requested = null
        stopHotspot()
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                reservation = value; cm.bindProcessToNetwork(null)
                @Suppress("DEPRECATION")
                val ssid = if (Build.VERSION.SDK_INT >= 30) value.softApConfiguration.ssid else value.wifiConfiguration?.SSID
                @Suppress("DEPRECATION")
                val pass = if (Build.VERSION.SDK_INT >= 30) value.softApConfiguration.passphrase else value.wifiConfiguration?.preSharedKey
                hotspotText = "Wi-Fi: $ssid\nPassword: ${pass ?: "See Wi-Fi settings"}"
                message(if (fiveGHz && Build.VERSION.SDK_INT >= 36) "5 GHz hotspot configuration accepted. Confirm the laptop's connected band." else "Offline hotspot active. Android chooses the band.")
            }
            override fun onStopped() { reservation = null; hotspotText = ""; message("Hotspot stopped") }
            override fun onFailed(reason: Int) { reservation = null; hotspotText = ""; message("Hotspot unavailable ($reason). Try Auto band or system hotspot settings.") }
        }
        if (fiveGHz) {
            check(Build.VERSION.SDK_INT >= 36 && wifi.is5GHzBandSupported) { "In-app 5 GHz hotspot needs Android 16 and compatible hardware. Select 5 GHz in system hotspot settings on older phones." }
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val pass = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            val config = SoftApConfiguration.Builder().setSsid("Nerqo-${pass.take(6)}")
                .setPassphrase(pass, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK).setBand(SoftApConfiguration.BAND_5GHZ).build()
            wifi.startLocalOnlyHotspotWithConfiguration(config, app.mainExecutor, callback)
        } else wifi.startLocalOnlyHotspot(callback, main)
    }
}
