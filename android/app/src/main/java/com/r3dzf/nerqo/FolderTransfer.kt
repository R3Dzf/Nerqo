package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.security.MessageDigest

/** Direct, resumable Android SAF tree transfer without staging an archive. */
internal object FolderTransfer {
    private const val MAX_ENTRIES = 100000
    private const val MAX_DEPTH = 64

    private fun safeName(name: String?): String {
        val part = name ?: throw IOException("Unnamed folder entry")
        if (part.isBlank() || part == "." || part == ".." || part.length > 190 ||
            part != part.trimEnd(' ', '.') ||
            part.any { it == '/' || it == '\\' || it.code < 32 || it == ':' } ||
            Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$",
                RegexOption.IGNORE_CASE).matches(part))
            throw IOException("Unsafe folder or file name: " + part)
        return part
    }

    fun send(context: Context, device: Device, token: String, tree: Uri,
             progress: (Int, String, Long, Long) -> Unit) {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw IOException("Could not read selected Android folder")
        if (!root.isDirectory) throw IOException("Select a folder")
        val rootName = safeName(root.name)
        val preferences = context.getSharedPreferences("nerqo_native", Context.MODE_PRIVATE)
        val hash = MessageDigest.getInstance("SHA-256").digest(
            (device.address + ":" + device.port + ":" + tree.toString()).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val key = "folder_transfer_" + hash
        val previous = preferences.getString(key, null)
        val folderId = if (previous == null) {
            NativeClient.beginFolder(device, token, rootName)
        } else {
            try { NativeClient.beginFolder(device, token, rootName, previous) }
            catch (e: NativeHttpException) {
                // Recreate the folder only after an explicit server-side 409.
                // A Wi-Fi timeout should never silently create duplicate folders.
                if (e.httpStatus != 409) throw e
                NativeClient.beginFolder(device, token, rootName)
            }
        }
        preferences.edit().putString(key, folderId).apply()
        var entries = 0
        fun walk(folder: DocumentFile, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH) throw IOException("Folder exceeds maximum nesting")
            for (child in folder.listFiles()) {
                if (++entries > MAX_ENTRIES) throw IOException("Too many folder entries")
                val path = if (prefix.isEmpty()) safeName(child.name)
                    else prefix + "/" + safeName(child.name)
                if (path.length > 2048) throw IOException("Folder path is too long")
                if (child.isDirectory) {
                    NativeClient.createFolderDirectory(device, token, folderId, path)
                    progress(entries, path, 0, 0)
                    walk(child, path, depth + 1)
                } else if (child.isFile) {
                    NativeClient.sendFile(context, device, token, child.uri,
                        folderId = folderId, relativePath = path) { done, total ->
                        progress(entries, path, done, total)
                    }
                }
            }
        }
        walk(root, "", 0)
        preferences.edit().remove(key).apply()
    }
}
