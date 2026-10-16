package com.r3dzf.nerqo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class HistoryEntry(val id: String, val title: String, val peer: String,
    val direction: String, val status: String, val updated: Long, val detail: String, val job: String = "")

internal object TransferHistory {
    private fun read(context: Context): List<HistoryEntry> {
        val data = JSONArray(context.getSharedPreferences("nerqo_history", Context.MODE_PRIVATE).getString("entries", "[]"))
        return (0 until data.length()).map { i -> val x = data.getJSONObject(i)
            HistoryEntry(x.getString("id"), x.getString("title"), x.getString("peer"),
                x.getString("direction"), x.getString("status"), x.getLong("updated"), x.optString("detail"), x.optString("job"))
        }.sortedByDescending { it.updated }.take(500)
    }
    @Synchronized fun load(context: Context): List<HistoryEntry> = runCatching { read(context) }.getOrDefault(emptyList())

    @Synchronized fun clear(context: Context): Boolean = context.getSharedPreferences("nerqo_history", Context.MODE_PRIVATE).edit().remove("entries").commit()

    @Synchronized fun record(context: Context, job: TransferJob, status: String, detail: String = ""): Boolean {
        val prior = runCatching { read(context) }.getOrElse { return false }
        val entries = prior.filter { it.id != job.id }.toMutableList()
        entries.add(0, HistoryEntry(job.id, job.title, job.deviceName,
            if (job.kind.startsWith("upload")) "Sent" else "Received", status, System.currentTimeMillis(), detail.take(200), job.json().toString()))
        val data = JSONArray()
        entries.take(500).forEach { data.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("peer", it.peer).put("direction", it.direction).put("status", it.status)
            .put("updated", it.updated).put("detail", it.detail).put("job", it.job)) }
        return context.getSharedPreferences("nerqo_history", Context.MODE_PRIVATE).edit().putString("entries", data.toString()).commit()
    }
}
