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

internal class PairingExpiredException : IOException("Device pairing expired or was revoked")
internal class NativeHttpException(val httpStatus: Int, details: String) : IOException(details)

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
        TransferControl.current.get()?.attach(conn) { conn.disconnect() }
        return conn
    }
    private fun close(conn: HttpURLConnection) {
        TransferControl.current.get()?.detach(conn)
        conn.disconnect()
    }
    private fun response(conn: HttpURLConnection): JSONObject {
        try {
            TransferControl.check()
            val status = conn.responseCode
            if (status == 401) throw PairingExpiredException()
            if (status !in 200..299) {
                val message = conn.errorStream?.bufferedReader()?.use { it.readText().take(256) } ?: "HTTP $status"
                throw NativeHttpException(status, "Nerqo HTTP $status: $message")
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { close(conn) }
    }
    private fun postJson(endpoint: String, path: String, json: JSONObject, token: String? = null): JSONObject {
        val conn = connect(endpoint + path, "POST", token)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        try {
            TransferControl.check()
            conn.outputStream.use { it.write(bytes) }
            return response(conn)
        } finally { close(conn) }
    }
    fun status(device: Device): JSONObject = response(connect(device.endpoint + "/api/status", "GET"))
    fun pair(device: Device, pin: String): String = postJson(device.endpoint, "/api/pair", JSONObject()
        .put("pin", pin).put("name", android.os.Build.MODEL)).getString("token")
    fun disconnect(device: Device, token: String) {
        postJson(device.endpoint, "/api/disconnect", JSONObject(), token)
    }
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
    fun beginFolder(device: Device, token: String, name: String, resumeId: String? = null): String {
        val payload = JSONObject().put("name", name)
        if (resumeId != null) payload.put("resumeId", resumeId)
        return postJson(device.endpoint, "/api/folders/begin", payload, token).getString("id")
    }

    fun createFolderDirectory(device: Device, token: String, folderId: String, relative: String) {
        postJson(device.endpoint, "/api/folders/" + folderId + "/directory",
            JSONObject().put("path", relative), token)
    }

    private fun <T> readSource(context: Context, uri: Uri, action: (java.io.InputStream) -> T): T {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected source")
        input.use {
            val control = TransferControl.current.get()
            return if (control == null) action(input) else control.resource(input) { action(input) }
        }
    }
    fun sendFile(context: Context, device: Device, token: String, uri: Uri, allowResume: Boolean = true,
                 folderId: String? = null, relativePath: String? = null, sessionId: String? = null,
                 progress: (Long, Long) -> Unit) {
        val resolver = context.contentResolver
        var size = -1L
        var name = "shared-file"
        if (uri.scheme == "file") {
            val local = java.io.File(uri.path ?: throw IOException("Invalid local file URI"))
            if (!local.isFile) throw IOException("Folder archive no longer exists")
            size = local.length()
            name = local.name
        } else {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size < 0 || size > 2L * 1024 * 1024 * 1024 * 1024) throw IOException("Cannot determine file size")
        // Transfer identities survive interruptions for the same URI and same device.
        TransferControl.check()
        val key = (if (sessionId == null) "resume_" + device.address + "_" else "upload_" + sessionId + "_") +
            sha((uri.toString() + "|" + size + "|" + (folderId ?: "") + "|" +
                (relativePath ?: "")).toByteArray(Charsets.UTF_8))
        val prefs = context.getSharedPreferences("nerqo_native", Context.MODE_PRIVATE)
        val resume = if (allowResume) prefs.getString(key, null)?.removePrefix("complete:") else null
        val body = JSONObject().put("name", name).put("size", size)
        if (folderId != null && relativePath != null) {
            body.put("folderId", folderId)
            body.put("relativePath", relativePath)
        }
        if (resume != null) body.put("resumeId", resume)
        var transfer: JSONObject
        try { transfer = postJson(device.endpoint, "/api/transfers/begin", body, token) }
        catch (e: NativeHttpException) {
            // Retry from zero only if the server definitively rejects a stale
            // resume ID. A timeout does not prove the original upload is gone.
            if (e.httpStatus != 409) throw e
            prefs.edit().remove(key).apply()
            val fresh = JSONObject().put("name", name).put("size", size)
            if (folderId != null && relativePath != null) {
                fresh.put("folderId", folderId).put("relativePath", relativePath)
            }
            transfer = postJson(device.endpoint, "/api/transfers/begin", fresh, token)
        }
        if (transfer.optBoolean("complete", false)) {
            TransferControl.current.get()?.phase("Checking the already delivered file…")
            val expected = transfer.getString("sha256")
            val digest = MessageDigest.getInstance("SHA-256")
            readSource(context, uri) { input ->
                val checkBuffer = ByteArray(256 * 1024)
                while (true) {
                    TransferControl.check()
                    val n = input.read(checkBuffer)
                    if (n < 0) break
                    digest.update(checkBuffer, 0, n)
                }
            }
            if (shaDigest(digest) != expected)
                throw IOException("This file changed since the folder transfer. Start a new folder transfer.")
            if (sessionId == null) prefs.edit().remove(key).apply()
            else transfer.optString("id").takeIf { it.matches(Regex("[0-9a-f]{32}")) }?.let {
                prefs.edit().putString(key, "complete:" + it).apply()
            }
            progress(size, size)
            return
        }
        val id = transfer.getString("id")
        var offset = transfer.getLong("offset")
        if (offset !in 0..size) throw IOException("Invalid upload resume offset")
        if (allowResume) prefs.edit().putString(key, id).apply()
        val wholeFileDigest = MessageDigest.getInstance("SHA-256")
        readSource(context, uri) { input ->
            // Read and hash the already-transferred prefix too. A document
            // provider may not implement InputStream.skip(), and hashing the
            // prefix detects changed files after a suspended upload.
            var skipped = 0L
            if (offset > 0L) TransferControl.current.get()?.phase("Verifying the source before resuming…")
            val prefix = ByteArray(256 * 1024)
            while (skipped < offset) {
                TransferControl.check()
                val read = input.read(prefix, 0, minOf(prefix.size.toLong(), offset - skipped).toInt())
                if (read <= 0) throw IOException("Cannot resume: source file was shortened")
                wholeFileDigest.update(prefix, 0, read)
                skipped += read
            }
            val buffer = ByteArray(MAX_CHUNK)
            progress(offset, size)
            while (offset < size) {
                TransferControl.check()
                var used = 0
                val remaining = minOf(buffer.size.toLong(), size - offset).toInt()
                while (used < remaining) {
                    TransferControl.check()
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
                try {
                    TransferControl.check()
                    conn.outputStream.use { it.write(buffer, 0, used) }
                    val acknowledged = response(conn).getLong("offset")
                    if (acknowledged != offset + used) throw IOException("Unexpected upload acknowledgement")
                    offset = acknowledged
                } finally { close(conn) }
                wholeFileDigest.update(buffer, 0, used)
                progress(offset, size)
            }
            TransferControl.check()
            if (input.read() != -1) throw IOException("Source file grew during transfer. Start a new transfer.")
        }
        val expectedFileHash = shaDigest(wholeFileDigest)
        postJson(device.endpoint, "/api/transfers/$id/finish?sha256=" + expectedFileHash,
            JSONObject(), token)
        if (allowResume && sessionId == null) prefs.edit().remove(key).apply()
        else if (allowResume) prefs.edit().putString(key, "complete:" + id).apply()
    }
    fun outgoingFolderManifest(device: Device, token: String, id: String): JSONObject {
        require(id.matches(Regex("[0-9a-f]{32}")))
        return response(connect(device.endpoint + "/api/outgoing-folders/" + id + "/manifest", "GET", token))
    }

    private fun receiveTo(context: Context, device: Device, token: String, path: String,
                          size: Long, expectedSha: String, target: Uri, sessionId: String,
                          progress: (Long, Long) -> Unit) {
        val prefs = context.getSharedPreferences("nerqo_downloads", Context.MODE_PRIVATE)
        val key = "download_" + sessionId + "_" + sha((device.endpoint + "|" + path + "|" + target + "|" + expectedSha + "|" + size).toByteArray())
        val record = JSONObject().put("uri", target.toString()).put("offset", 0).put("verified", false)
        val saved = prefs.getString(key, null)?.let { JSONObject(it) }
        val previous = saved?.getLong("offset") ?: 0L
        // Record ownership before the first network request, so Cancel can
        // remove our destination even if the connection fails before 4 MiB.
        record.put("offset", previous).put("verified", saved?.optBoolean("verified", false) ?: false)
        if (!prefs.edit().putString(key, record.toString()).commit()) throw IOException("Could not save download session")
        val control = TransferControl.current.get() ?: TransferControl()
        SafDownloadTarget(context, target).use { destination ->
            ResumableDownload.copy(destination, size, expectedSha, previous, remote = { offset ->
                val conn = connect(device.endpoint + path, "GET", token)
                if (offset > 0L) conn.setRequestProperty("Range", "bytes=" + offset + "-")
                try {
                    control.checkpoint()
                    val status = conn.responseCode
                    if (status == 401) throw PairingExpiredException()
                    if (status !in listOf(200, 206)) throw NativeHttpException(status, "Download unavailable: HTTP " + status)
                    DownloadResponse(status, conn.getHeaderField("Content-Range"), conn.inputStream) { close(conn) }
                } catch (error: Exception) { close(conn); throw error }
            }, checkpoint = { offset ->
                record.put("offset", offset)
                if (!prefs.edit().putString(key, record.toString()).commit()) throw IOException("Could not save download checkpoint")
            }, progress = progress, control = control)
            record.put("offset", size).put("verified", true)
            if (!prefs.edit().putString(key, record.toString()).commit()) throw IOException("Could not save verified download receipt")
        }
    }
    fun saveOutgoingFolderFile(context: Context, device: Device, token: String, id: String,
                               relative: String, size: Long, expectedSha: String, target: Uri,
                               sessionId: String, progress: (Long, Long) -> Unit) {
        require(id.matches(Regex("[0-9a-f]{32}")))
        val encoded = java.net.URLEncoder.encode(relative, "UTF-8")
        receiveTo(context, device, token, "/api/outgoing-folders/" + id + "/file?path=" + encoded,
            size, expectedSha, target, sessionId, progress)
    }
    fun download(context: Context, device: Device, token: String, item: SharedEntry, target: Uri,
                 sessionId: String, progress: (Long, Long) -> Unit) {
        require(item.id.matches(Regex("[0-9a-f]{32}")))
        receiveTo(context, device, token, "/api/files/" + item.id, item.size, item.sha256, target, sessionId, progress)
    }
    fun cleanSession(context: Context, device: Device, token: String?, sessionId: String, cancel: Boolean) {
        val uploads = context.getSharedPreferences("nerqo_native", Context.MODE_PRIVATE)
        val edit = uploads.edit()
        uploads.all.filterKeys { it.startsWith("upload_" + sessionId + "_") }.forEach { (key, value) ->
            if (cancel && token != null && value is String && value.matches(Regex("[0-9a-f]{32}"))) {
                runCatching { response(connect(device.endpoint + "/api/transfers/" + value, "DELETE", token)) }
            }
            edit.remove(key)
        }
        uploads.all.keys.filter { it.startsWith("folder_session_" + sessionId + "_") }.forEach { edit.remove(it) }
        edit.apply()
        val downloads = context.getSharedPreferences("nerqo_downloads", Context.MODE_PRIVATE)
        val downloadEdit = downloads.edit()
        downloads.all.filterKeys { it.startsWith("download_" + sessionId + "_") }.forEach { (key, value) ->
            if (cancel && value is String) runCatching {
                val record = JSONObject(value)
                if (!record.optBoolean("verified", false)) {
                    val uri = Uri.parse(record.getString("uri"))
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete()
                }
            }
            downloadEdit.remove(key)
        }
        downloadEdit.apply()
    }
    private fun shaDigest(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
