package com.r3dzf.nerqo

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/** Test APK only. Real Android SAF descriptors, directories and rename semantics. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val root: File get() = File(context!!.cacheDir, "transport-fixtures").also { it.mkdirs() }
    private fun file(id: String): File {
        val item = if (id == "root") root else File(root, id.removePrefix("root/"))
        if (item.canonicalFile != root.canonicalFile && !item.canonicalPath.startsWith(root.canonicalPath + File.separator))
            throw FileNotFoundException("Invalid fixture path")
        return item
    }
    private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).invariantSeparatorsPath
    override fun onCreate(): Boolean {
        context!!.grantUriPermission("com.r3dzf.nerqo", android.net.Uri.parse("content://com.r3dzf.nerqo.test.documents"),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        return true
    }
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) {
            Root.COLUMN_ROOT_ID -> "fixture"; Root.COLUMN_DOCUMENT_ID -> "root"; Root.COLUMN_TITLE -> "Nerqo test storage"
            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE; else -> null
        } }.toTypedArray()) }
    }
    private fun add(cursor: MatrixCursor, file: File) {
        cursor.addRow(cursor.columnNames.map { column -> when(column) {
            Document.COLUMN_DOCUMENT_ID -> id(file)
            Document.COLUMN_DISPLAY_NAME -> file.name
            Document.COLUMN_SIZE -> file.length()
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            Document.COLUMN_MIME_TYPE -> if(file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or (if(file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
            else -> null
        } }.toTypedArray())
    }
    private fun columns(projection: Array<out String>?) = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(columns(projection)).apply {
        val item = file(documentId); if(item.exists()) add(this, item)
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(columns(projection)).apply {
        file(parentDocumentId).listFiles()?.forEach { add(this, it) }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val item = file(documentId); item.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(item, ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        val item = File(parent, displayName)
        file(id(item)) // validate canonical containment
        if(item.exists()) throw FileNotFoundException("Fixture already exists")
        if(mimeType == Document.MIME_TYPE_DIR) item.mkdir() else item.createNewFile()
        return id(item)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val item = file(documentId)
        val target = File(item.parentFile, displayName)
        file(id(target))
        if(target.exists() || !item.renameTo(target)) throw FileNotFoundException("Cannot rename fixture")
        return id(target)
    }
    override fun deleteDocument(documentId: String) { if (!file(documentId).deleteRecursively()) throw FileNotFoundException("Cannot delete fixture") }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator)
}
