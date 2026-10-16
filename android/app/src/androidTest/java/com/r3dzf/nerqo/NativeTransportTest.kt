package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NativeTransportTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = Device("CI Windows fixture", "10.0.2.2")
    private val token = "nerqo-emulator-fixture-token"
    private val authority = "com.r3dzf.nerqo.test.documents"
    private val data = ByteArray(9 * 1024 * 1024) { it.toByte() } + "nerqo".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val fingerprint: String get() = InstrumentationRegistry.getArguments().getString("fingerprint")!!
    private fun uri(name: String) = DocumentsContract.buildDocumentUri(authority, "root/$name")
    private fun session() = UUID.randomUUID().toString()
    @Before fun trustKnownFixture() {
        // The debug-only provider shares the instrumented app's UID. Its SAF
        // cursors/descriptors/rename operations are real; no shell grants or
        // cross-package Activity lifecycle are part of this transport test.
        val root = DocumentsContract.buildDocumentUri(authority, "root")
        context.contentResolver.query(root, null, null, null, null)?.use {
            assertTrue(it.moveToFirst())
        } ?: error("Test storage did not start")
        NativeClient.trustIdentity(device, fingerprint)
    }
    private fun pauseAtFourMiB(control: TransferControl, done: Long) {
        if(done >= 4L * 1024 * 1024) control.interrupt(TransferStop.PAUSE)
    }
    @Test fun test00HistorySurvivesReloadWithoutDuplicatingResumedJobs() {
        val job = TransferJob(kind = "downloadFile", title = "History persistence fixture", host = device.address,
            port = device.port, deviceName = device.name, fingerprint = "0".repeat(64))
        assertTrue(TransferHistory.record(context, job, "Running"))
        assertTrue(TransferHistory.record(context, job, "Paused"))
        assertTrue(TransferHistory.record(context, job, "Running"))
        assertTrue(TransferHistory.record(context, job, "Completed"))
        val loaded = TransferHistory.load(context).filter { it.id == job.id }
        assertEquals(1, loaded.size)
        assertEquals("Completed", loaded.single().status)
        assertEquals("Received", loaded.single().direction)
    }
    @Test fun test00CheckpointScopeSurvivesMovingToNewAddress() {
        val original = TransferJob(kind = "downloadFile", title = "address change", host = "192.168.10.2", port = 44888, deviceName = device.name, fingerprint = fingerprint)
        val moved = original.copy(host = "192.168.20.2")
        val restored = TransferJob.decode(moved.json().toString())!!
        assertEquals("192.168.20.2", restored.host)
        assertEquals(original.host, restored.checkpointHost)
        val control = TransferControl().apply { endpointScope = "https://${restored.checkpointHost}:${restored.checkpointPort}" }
        control.run { assertEquals("https://192.168.10.2:44888", NativeClient.checkpointEndpoint(Device(device.name, restored.host))) }
    }
    @Test fun test00ForegroundServiceIsPrivateDataSync() {
        val info = context.packageManager.getServiceInfo(android.content.ComponentName(context, TransferService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }
    @Test fun test00ReceiptOutboxSurvivesMissingTokenAndAcknowledgesOnceConnected() {
        val entry = SharedEntry("1".repeat(32), "file", device.name, "receipt.bin", data.size.toLong(), hash(data))
        val job = TransferJob(kind = "downloadFile", title = "receipt", host = device.address, port = device.port, deviceName = device.name, fingerprint = fingerprint, entry = entry)
        val prefs = context.getSharedPreferences("nerqo_native", 0)
        prefs.edit().putString("cert_${device.address}:${device.port}", fingerprint).remove("token_${device.address}").commit()
        ReceiptOutbox.enqueue(context, job); ReceiptOutbox.enqueue(context, job)
        ReceiptOutbox.flush(context)
        assertTrue(context.getSharedPreferences("nerqo_receipts", 0).contains(job.id))
        prefs.edit().putString("token_${device.address}", token).commit()
        ReceiptOutbox.flush(context)
        assertFalse(context.getSharedPreferences("nerqo_receipts", 0).contains(job.id))
    }
    @Test fun test00ScannerIsPortraitAndPrivate() {
        val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, PortraitCaptureActivity::class.java), 0)
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, info.screenOrientation)
        assertFalse(info.exported)
    }
    @Test fun test01SafDownloadResumesAfterClosingDescriptorsAndNewControl() {
        val target = uri("download.bin")
        val id = session()
        val entry = SharedEntry("1".repeat(32), "file", device.name, "download.bin", data.size.toLong(), hash(data))
        val control = TransferControl()
        assertThrows(IOException::class.java) {
            control.run { NativeClient.download(context, device, token, entry, target, id) { done, _ -> pauseAtFourMiB(control, done) } }
        }
        assertEquals(TransferStop.PAUSE, control.stop)
        assertTrue(context.getSharedPreferences("nerqo_downloads", 0).all.values.any { it.toString().contains("4194304") })
        TransferControl().run { NativeClient.download(context, device, token, entry, target, id) { _, _ -> } }
        assertTrue(context.contentResolver.openInputStream(target)!!.use { TransferIntegrity.matches(it, data.size.toLong(), hash(data)) })
        assertTrue(NativeClient.status(device).getJSONArray("ranges").toString().contains("4194304"))
        NativeClient.cleanSession(context, device, token, id, cancel = false)
    }
    @Test fun test02FolderResumesPartialAndPreservesEmptyDirectory() {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val id = session()
        val control = TransferControl()
        assertThrows(IOException::class.java) {
            control.run { FolderReceiver.receive(context, device, token, "2".repeat(32), tree, "fixture-folder", id) { _, _, done, _ -> pauseAtFourMiB(control, done) } }
        }
        TransferControl().run { FolderReceiver.receive(context, device, token, "2".repeat(32), tree, "fixture-folder", id) { _, _, _, _ -> } }
        val root = DocumentFile.fromTreeUri(context, tree)!!.findFile("fixture-folder (Nerqo-22222222)")!!
        val large = root.findFile("nested")!!.findFile("large.bin")!!
        assertTrue(context.contentResolver.openInputStream(large.uri)!!.use { TransferIntegrity.matches(it, data.size.toLong(), hash(data)) })
        assertTrue(root.findFile("empty")!!.isDirectory)
        assertEquals("hello", context.contentResolver.openInputStream(root.findFile("small.txt")!!.uri)!!.bufferedReader().use { it.readText() })
        NativeClient.cleanSession(context, device, token, id, cancel = true)
        assertTrue("Cancel must preserve verified folder members", large.exists())
    }
    @Test fun test03UploadPauseAndLostFinishResponsePublishOnlyOnce() {
        val source = uri("upload.bin")
        context.contentResolver.openOutputStream(source, "wt")!!.use { it.write(data) }
        val id = session()
        val control = TransferControl()
        assertThrows(IOException::class.java) {
            control.run { NativeClient.sendFile(context, device, token, source, sessionId = id) { done, _ -> pauseAtFourMiB(control, done) } }
        }
        // The TLS fixture commits this finish, then deliberately closes its reply.
        assertThrows(IOException::class.java) {
            TransferControl().run { NativeClient.sendFile(context, device, token, source, sessionId = id) { _, _ -> } }
        }
        TransferControl().run { NativeClient.sendFile(context, device, token, source, sessionId = id) { _, _ -> } }
        assertEquals(1, NativeClient.status(device).getInt("published"))
        assertEquals("[0,4194304,8388608]", NativeClient.status(device).getJSONArray("uploadChunks").toString())
        NativeClient.cleanSession(context, device, token, id, cancel = false)
    }
    @Test fun test04CancelRemovesUnverifiedOwnedPartial() {
        // Include a stop before the first byte/checkpoint, as well as mid-file.
        for (stopAfter in listOf(0L, 4L * 1024 * 1024)) {
            val target = uri("cancel.bin")
            val id = session()
            val entry = SharedEntry("1".repeat(32), "file", device.name, "cancel.bin", data.size.toLong(), hash(data))
            val control = TransferControl()
            assertThrows(IOException::class.java) {
                control.run { NativeClient.download(context, device, token, entry, target, id) { done, _ ->
                    if (done >= stopAfter) control.interrupt(TransferStop.PAUSE)
                } }
            }
            NativeClient.cleanSession(context, device, token, id, cancel = true)
            assertFalse(DocumentFile.fromSingleUri(context, target)!!.exists())
        }
    }
    @Test fun test99LeaveRealPausedTransferForUiResumeTest() {
        val content = ByteArray(64 * 1024 * 1024) { it.toByte() } + "ui".toByteArray()
        val entry = SharedEntry("3".repeat(32), "file", device.name, "Native-resume-fixture.bin", content.size.toLong(), hash(content))
        val target = uri("ui-resume.bin")
        val job = TransferJob(kind = "downloadFile", title = "Save native resume fixture", host = device.address,
            port = device.port, deviceName = device.name, fingerprint = fingerprint, target = target.toString(), entry = entry)
        val control = TransferControl()
        assertThrows(IOException::class.java) {
            control.run { NativeClient.download(context, device, token, entry, target, job.id) { done, _ -> pauseAtFourMiB(control, done) } }
        }
        // Stage a second, independent upload for the external foreground-service UI check.
        val source = uri("ui-upload.bin")
        context.contentResolver.openOutputStream(source, "wt")!!.use { it.write(content) }
        val uploadJob = TransferJob(kind = "uploadFiles", title = "Background upload fixture", host = device.address,
            port = device.port, deviceName = device.name, fingerprint = fingerprint, uris = listOf(source.toString()))
        java.io.File(context.filesDir, "ui-upload-pending.xml").writeText(
            "<map><string name=\"pending\">" + android.text.TextUtils.htmlEncode(uploadJob.json().toString()) + "</string></map>")
        job.save(context)
        context.getSharedPreferences("nerqo_native", 0).edit().putString("host", device.address).putInt("port", device.port)
            .putString("name", device.name).putString("token_${device.address}", token)
            .putString("cert_${device.address}:${device.port}", fingerprint).putBoolean("dark_mode", false).commit()
    }
}
