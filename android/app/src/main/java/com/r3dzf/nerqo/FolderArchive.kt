package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.Deflater

/** Offline SAF folder staging. A ZIP keeps the directory hierarchy intact. */
object FolderArchive {
    private const val MAX_ENTRIES = 100000
    private const val MAX_DEPTH = 64
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024 * 1024

    private fun safeName(raw: String?): String {
        val name = raw ?: throw IOException("Folder entry has no name")
        if (name.isBlank() || name == "." || name == ".." || name.length > 190
            || name.any { it == '/' || it == '\\' || it.code < 32 })
            throw IOException("Invalid folder entry name")
        return name
    }

    fun stage(context: Context, tree: Uri, progress: (Int) -> Unit = {}): File {
        val root = DocumentFile.fromTreeUri(context, tree)
            ?: throw IOException("Could not open selected folder")
        if (!root.isDirectory) throw IOException("Select a folder, not a file")
        val rootName = safeName(root.name)
        val cache = File(File(context.cacheDir, "nerqo-folders"), UUID.randomUUID().toString())
        if (!cache.mkdirs()) throw IOException("Could not prepare folder archive")
        val result = File(cache, rootName + ".zip")
        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(256 * 1024)
        try {
            ZipOutputStream(FileOutputStream(result).buffered()).use { zip ->
                zip.setLevel(Deflater.BEST_SPEED)
                fun visit(dir: DocumentFile, prefix: String, depth: Int) {
                    if (depth > MAX_DEPTH) throw IOException("Folder is nested too deeply")
                    if (++entries > MAX_ENTRIES) throw IOException("Too many files or folders")
                    progress(entries)
                    zip.putNextEntry(ZipEntry(prefix + "/"))
                    zip.closeEntry()
                    for (child in dir.listFiles()) {
                        val path = prefix + "/" + safeName(child.name)
                        if (child.isDirectory) {
                            visit(child, path, depth + 1)
                        } else if (child.isFile) {
                            if (++entries > MAX_ENTRIES) throw IOException("Too many files or folders")
                            progress(entries)
                            val declaredSize = child.length()
                            if (declaredSize < 0 || declaredSize > MAX_BYTES - totalBytes)
                                throw IOException("Folder is larger than the 2 TB transfer limit")
                            zip.putNextEntry(ZipEntry(path))
                            val input = context.contentResolver.openInputStream(child.uri)
                                ?: throw IOException("Cannot read a folder file")
                            input.use { source ->
                                while (true) {
                                    val n = source.read(buffer)
                                    if (n < 0) break
                                    totalBytes += n
                                    if (totalBytes > MAX_BYTES) throw IOException("Folder too large")
                                    zip.write(buffer, 0, n)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                visit(root, rootName, 0)
            }
            if (result.length() > MAX_BYTES) throw IOException("ZIP exceeds the transfer size limit")
            return result
        } catch (e: Exception) {
            result.delete()
            cache.delete()
            throw e
        }
    }
}
