package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import java.util.concurrent.ConcurrentHashMap
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal data class Device(val name: String, val address: String, val port: Int = 44888) {
    val endpoint: String get() = "https://$address:$port"
}
internal data class SharedEntry(
    val id: String, val kind: String, val sender: String, val content: String,
    val size: Long, val sha256: String
)

internal object NativeClient {
    private const val MAX_CHUNK = 4 * 1024 * 1024
    private val identities = ConcurrentHashMap<String, String>()
    fun trustIdentity(device: Device, fingerprint: String) {
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "Invalid certificate fingerprint" }
        identities["${device.address}:${device.port}"] = fingerprint
    }
    /** Untrusted certificate probe: sends NO HTTP data, PIN or bearer token. User must compare Windows code. */
    fun previewIdentity(device: Device): String {
        val probe = SSLContext.getInstance("TLS")
        probe.init(null, arrayOf(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }), SecureRandom())
        val socket = probe.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = 6000
            it.connect(InetSocketAddress(device.address, device.port), 6000)
            it.startHandshake()
            val certificate = it.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw IOException("Windows did not present a certificate")
            return sha(certificate.encoded)
        }
    }
    private fun sslForPinnedIdentity(fingerprint: String): SSLContext {
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                throw CertificateException("Client certificates not used")
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || sha(chain[0].encoded) != fingerprint)
                    throw CertificateException("Windows identity changed. Re-pair and compare identity code.")
                chain[0].checkValidity()
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), SecureRandom()) }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    fun discover(): List<Device> {
        val found = mutableMapOf<String, Device>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 800
            val msg = "NERQO_DISCOVER_V1".toByteArray(Charsets.US_ASCII)
            val broadcasts = mutableSetOf("255.255.255.255")
            try {
                for (nic in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!nic.isUp || nic.isLoopback) continue
                    for (addr in nic.interfaceAddresses) {
                        addr.broadcast?.hostAddress?.let { broadcasts.add(it) }
                    }
                }
            } catch (_: Exception) { }
            for (address in broadcasts) {
                try { socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(address), 44889)) }
                catch (_: Exception) { }
            }
            val until = System.currentTimeMillis() + 2700
            while (System.currentTimeMillis() < until) {
                try {
                    val buffer = ByteArray(2048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val details = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (details.optString("app") != "Nerqo") continue
                    val host = packet.address.hostAddress ?: continue
                    val port = details.optInt("port", 44888)
                    if (port !in 1..65535) continue
                    found[host] = Device(details.optString("name", "Windows PC"), host, port)
                } catch (_: java.net.SocketTimeoutException) { }
                  catch (_: Exception) { }
            }
        }
        return found.values.toList()
    }
    private fun connect(url: String, method: String, token: String? = null): HttpURLConnection {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw IOException("Unencrypted transfer forbidden")
        val fingerprint = identities["${parsed.host}:${parsed.port}"]
            ?: throw IOException("Verify the Windows security code first")
        val conn = parsed.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = sslForPinnedIdentity(fingerprint).socketFactory
        // The pinned SHA-256 fingerprint authenticates the device even with a local IP address.
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            try { sha(session.peerCertificates[0].encoded) == fingerprint } catch (_: Exception) { false }
        }
        conn.requestMethod = method
        conn.connectTimeout = 7000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }
    private fun response(conn: HttpURLConnection): JSONObject {
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                val message = conn.errorStream?.bufferedReader()?.use { it.readText().take(256) } ?: "HTTP $status"
                throw IOException("Nerqo: $message")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
    private fun postJson(endpoint: String, path: String, json: JSONObject, token: String? = null): JSONObject {
        val conn = connect(endpoint + path, "POST", token)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return response(conn)
    }
    fun status(device: Device): JSONObject = response(connect(device.endpoint + "/api/status", "GET"))
    fun pair(device: Device, pin: String): String = postJson(device.endpoint, "/api/pair", JSONObject()
        .put("pin", pin).put("name", android.os.Build.MODEL)).getString("token")
    fun list(device: Device, token: String): List<SharedEntry> {
        val entries = response(connect(device.endpoint + "/api/items", "GET", token)).getJSONArray("items")
        return (0 until entries.length()).map { i ->
            val data = entries.getJSONObject(i)
            SharedEntry(data.getString("id"), data.getString("kind"), data.getString("sender"),
                data.getString("content"), data.optLong("size"), data.optString("sha256"))
        }
    }
    fun sendNote(device: Device, token: String, message: String) {
        val type = if (message.trim().startsWith("https://") || message.trim().startsWith("http://")) "link" else "text"
        postJson(device.endpoint, "/api/items", JSONObject().put("kind", type).put("content", message), token)
    }
    fun sendFile(context: Context, device: Device, token: String, uri: Uri, progress: (Long, Long) -> Unit) {
        val resolver = context.contentResolver
        var size = -1L
        var name = "shared-file"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size < 0 || size > 2L * 1024 * 1024 * 1024 * 1024) throw IOException("Cannot determine file size")
        // Transfer identities survive interruptions for the same URI and same device.
        val key = "resume_${device.address}_${sha(uri.toString().toByteArray())}"
        val prefs = context.getSharedPreferences("nerqo_native", Context.MODE_PRIVATE)
        val resume = prefs.getString(key, null)
        val body = JSONObject().put("name", name).put("size", size)
        if (resume != null) body.put("resumeId", resume)
        var transfer: JSONObject
        try { transfer = postJson(device.endpoint, "/api/transfers/begin", body, token) }
        catch (_: IOException) {
            prefs.edit().remove(key).apply()
            transfer = postJson(device.endpoint, "/api/transfers/begin", JSONObject().put("name", name).put("size", size), token)
        }
        val id = transfer.getString("id")
        var offset = transfer.getLong("offset")
        prefs.edit().putString(key, id).apply()
        resolver.openInputStream(uri)?.use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) throw IOException("Cannot resume file at expected offset")
                skipped += n
            }
            val buffer = ByteArray(MAX_CHUNK)
            while (offset < size) {
                var used = 0
                val remaining = minOf(buffer.size.toLong(), size - offset).toInt()
                while (used < remaining) {
                    val n = input.read(buffer, used, remaining - used)
                    if (n < 0) throw IOException("File changed during transfer")
                    used += n
                }
                val conn = connect(device.endpoint + "/api/transfers/$id/chunk", "PUT", token)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(used)
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("X-Offset", offset.toString())
                conn.setRequestProperty("X-SHA256", sha(buffer.copyOf(used)))
                conn.outputStream.use { it.write(buffer, 0, used) }
                offset = response(conn).getLong("offset")
                progress(offset, size)
            }
        } ?: throw IOException("Cannot open selected file")
        postJson(device.endpoint, "/api/transfers/$id/finish", JSONObject(), token)
        prefs.edit().remove(key).apply()
    }
    fun download(context: Context, device: Device, token: String, item: SharedEntry, target: Uri, progress: (Long, Long) -> Unit) {
        val conn = connect(device.endpoint + "/api/files/${item.id}", "GET", token)
        try {
            if (conn.responseCode != 200) throw IOException("Download not available: HTTP ${conn.responseCode}")
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openOutputStream(target, "w")?.use { output ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        progress(received, item.size)
                    }
                    output.flush()
                    if (received != item.size || shaDigest(digest) != item.sha256)
                        throw IOException("Downloaded file failed SHA-256 verification")
                }
            } ?: throw IOException("No permission to save selected file")
        } finally { conn.disconnect() }
    }
    private fun shaDigest(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
