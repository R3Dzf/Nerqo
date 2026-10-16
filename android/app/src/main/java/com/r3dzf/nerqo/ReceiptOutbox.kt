package com.r3dzf.nerqo

import android.content.Context

/** Never persist bearer tokens here; receipts use the current, certificate-bound pairing. */
internal object ReceiptOutbox {
    @Synchronized fun enqueue(context: Context, job: TransferJob) {
        val prefs = context.getSharedPreferences("nerqo_receipts", Context.MODE_PRIVATE)
        check(prefs.edit().putString(job.id, job.json().toString()).commit()) { "Could not save delivery acknowledgement; Resume to retry" }
    }
    @Synchronized fun flush(context: Context) {
        val outbox = context.getSharedPreferences("nerqo_receipts", Context.MODE_PRIVATE)
        val prefs = context.getSharedPreferences("nerqo_native", Context.MODE_PRIVATE)
        for ((key, value) in outbox.all) {
            val job = (value as? String)?.let { TransferJob.decode(it) } ?: continue
            val entry = job.entry ?: continue
            val host = if (prefs.getString("saved_identity", null) == job.fingerprint) prefs.getString("host", job.host) ?: job.host else job.host
            val port = if (host != job.host) prefs.getInt("port", job.port) else job.port
            if (prefs.getString("cert_$host:$port", null) != job.fingerprint) continue
            val token = prefs.getString("token_$host", null) ?: continue
            val device = Device(job.deviceName, host, port)
            try {
                NativeClient.trustIdentity(device, job.fingerprint)
                NativeClient.confirmReception(device, token, entry, job.id)
                check(outbox.edit().remove(key).commit())
            } catch (_: Exception) { /* Retry on the next successful connection. Idempotent on Windows. */ }
        }
    }
}
