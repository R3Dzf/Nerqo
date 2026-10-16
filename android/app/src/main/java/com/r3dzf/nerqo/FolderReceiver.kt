package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import org.json.JSONObject
import java.security.MessageDigest

/** Save Windows-selected folders natively into an Android SAF tree, no ZIP. */
internal object FolderReceiver {
    private fun safePart(name: String): String {
        if (name.isBlank() || name == "." || name == ".." || name.length > 190 ||
            name != name.trimEnd(' ', '.') ||
            name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 } ||
            Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$",
                  RegexOption.IGNORE_CASE).matches(name))
            throw IOException("Unsafe remote file name: " + name)
        return name
    }

    private fun components(relative: String): List<String> {
        if (relative.length > 2048) throw IOException("Remote folder member name is too long")
        val parts = relative.split("/")
        if (parts.isEmpty() || parts.size > 64) throw IOException("Invalid nested folder path")
        return parts.map(::safePart)
    }

    private fun directory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing != null) {
            if (!existing.isDirectory) throw IOException("A file blocks a required directory")
            return existing
        }
        return parent.createDirectory(name) ?: throw IOException("Cannot create folder: " + name)
    }

    fun receive(context: Context, device: Device, token: String, id: String,
                destinationTree: Uri, displayName: String, sessionId: String,
                progress: (Int, String, Long, Long) -> Unit) {
        val selected = DocumentFile.fromTreeUri(context, destinationTree)
            ?: throw IOException("Cannot open destination")
        if (!selected.isDirectory) throw IOException("Select a destination folder")
        val manifest = NativeClient.outgoingFolderManifest(device, token, id)
        val name = safePart(manifest.getString("name"))
        if (name != displayName) throw IOException("Shared folder identity changed")
        val root = directory(selected, name + " (Nerqo-" + id.take(8) + ")")
        val allDirs = manifest.getJSONArray("directories")
        val entries = manifest.getJSONArray("files")
        if (allDirs.length() + entries.length() > 100000) throw IOException("Too many folder entries")
        fun resolveDirectory(path: List<String>): DocumentFile {
            var cursor = root
            for (part in path) cursor = directory(cursor, part)
            return cursor
        }
        for (i in 0 until allDirs.length()) resolveDirectory(components(allDirs.getString(i)))
        // Validate every member before any file download and calculate an overall byte total.
        var total = 0L
        val seen = mutableSetOf<String>()
        for (i in 0 until entries.length()) {
            TransferControl.check()
            val member = entries.getJSONObject(i)
            components(member.getString("path"))
            if (!seen.add(member.getString("path"))) throw IOException("Duplicate folder member")
            val length = member.getLong("size")
            if (length < 0 || length > 2L * 1024 * 1024 * 1024 * 1024 - total)
                throw IOException("Remote folder is too large")
            if (!member.getString("sha256").matches(Regex("[0-9a-f]{64}"))) throw IOException("Invalid member checksum")
            total += length
        }
        val prefs = context.getSharedPreferences("nerqo_folder_receipts", Context.MODE_PRIVATE)
        val receiptScope = device.endpoint + "|" + id + "|" + root.uri
        fun receiptKey(path: String) = MessageDigest.getInstance("SHA-256")
            .digest((receiptScope + "|" + path).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        var completed = 0L
        for (i in 0 until entries.length()) {
            TransferControl.check()
            val file = entries.getJSONObject(i)
            val relative = file.getString("path")
            val parts = components(relative)
            val expectedSize = file.getLong("size")
            val hash = file.getString("sha256")
            val receiptKey = receiptKey(relative)
            val parent = resolveDirectory(parts.dropLast(1))
            val destinationName = parts.last()
            val existing = parent.findFile(destinationName)
            if (existing != null) {
                // Only skip a file finalized by this exact transfer into this destination.
                // Never overwrite an unrelated user file, or trust a receipt without hashing.
                TransferControl.current.get()?.phase("Verifying saved folder member: " + relative)
                val owned = prefs.getString(receiptKey, null) == hash + "|" + existing.uri
                val matches = owned && existing.isFile && context.contentResolver.openInputStream(existing.uri)?.use { input ->
                    val control = TransferControl.current.get()
                    if (control == null) TransferIntegrity.matches(input, expectedSize, hash)
                    else control.resource(input) { TransferIntegrity.matches(input, expectedSize, hash) }
                } == true
                if (!matches) throw IOException("Destination already contains a different or unverified file: " + relative)
                completed += expectedSize
                progress(i + 1, "Verified " + relative, completed, total)
                continue
            }
            val partialKey = "partial_" + receiptKey
            val savedPartial = prefs.getString(partialKey, null)?.let { JSONObject(it) }
            val previous = savedPartial?.takeIf { it.optString("sha256") == hash && it.optLong("size", -1) == expectedSize }
                ?.let { parent.findFile(it.getString("name"))?.takeIf { document -> document.uri.toString() == it.getString("uri") } }
            val staging = previous ?: parent.createFile("application/octet-stream", destinationName + ".nerqo-part")
                ?: throw IOException("Cannot create " + relative)
            if (!prefs.edit().putString(partialKey, JSONObject().put("uri", staging.uri.toString()).put("name", staging.name)
                    .put("sha256", hash).put("size", expectedSize).toString()).commit())
                throw IOException("Cannot save partial folder member identity")
            try {
                NativeClient.saveOutgoingFolderFile(context, device, token, id, relative, expectedSize,
                    hash, staging.uri, sessionId) { done, _ ->
                    progress(i + 1, relative, completed + done, total)
                }
                if (!staging.renameTo(destinationName))
                    throw IOException("Storage provider could not finalize " + relative)
                if (!prefs.edit().putString(receiptKey, hash + "|" + staging.uri).remove(partialKey).commit())
                    throw IOException("Could not save the folder retry receipt")
                completed += expectedSize
                progress(i + 1, relative, completed, total)
            } catch (error: Exception) {
                // Keep our checkpointed partial for pause, process death or a network retry.
                // Unverified documents are removed only after an explicit Cancel.
                throw error
            }
        }
    }
}
