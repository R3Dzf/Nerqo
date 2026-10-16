package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import org.json.JSONObject

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
                destinationTree: Uri, displayName: String,
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
        var total = 0L
        for (i in 0 until entries.length()) {
            val file = entries.getJSONObject(i)
            val relative = file.getString("path")
            val parts = components(relative)
            val expectedSize = file.getLong("size")
            if (expectedSize < 0 || expectedSize > 2L * 1024 * 1024 * 1024 * 1024 - total)
                throw IOException("Remote folder is too large")
            total += expectedSize
            val parent = resolveDirectory(parts.dropLast(1))
            val destinationName = parts.last()
            if (parent.findFile(destinationName) != null)
                throw IOException("Destination already contains " + relative + "; choose an empty folder")
            val staging = parent.createFile("application/octet-stream",
                destinationName + ".nerqo-part")
                ?: throw IOException("Cannot create " + relative)
            try {
                NativeClient.saveOutgoingFolderFile(context, device, token, id, relative, expectedSize,
                    file.getString("sha256"), staging.uri) { done, size ->
                    progress(i + 1, relative, done, size)
                }
                if (!staging.renameTo(destinationName))
                    throw IOException("Storage provider could not finalize " + relative)
            } catch (error: Exception) {
                staging.delete()
                throw error
            }
        }
    }
}
