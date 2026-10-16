package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/** Local SAF documents use seekable descriptors; pipe providers restart safely. */
internal class SafDownloadTarget(private val context: Context, private val uri: Uri) : DownloadTarget {
    private val descriptor = try { context.contentResolver.openFileDescriptor(uri, "rw") } catch (_: Exception) { null }
    override val resumable: Boolean
    init {
        resumable = try {
            descriptor != null && ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use {
                it.channel.position(0); it.channel.size(); true
            }
        } catch (_: Exception) { false }
    }
    override fun length(): Long {
        if (!resumable || descriptor == null) return 0
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.channel.size() }
    }
    override fun read(): InputStream {
        if (descriptor == null || !resumable) throw IOException("This provider cannot read a saved prefix")
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).also { it.channel.position(0) }
    }
    override fun writeAt(offset: Long): OutputStream {
        if (resumable && descriptor != null) {
            return ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).also {
                it.channel.truncate(offset); it.channel.position(offset)
            }
        }
        if (offset != 0L) throw IOException("This storage provider must restart the download")
        return context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write selected destination")
    }
    override fun close() { descriptor?.close() }
}
