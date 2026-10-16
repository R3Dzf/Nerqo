package com.r3dzf.nerqo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class HistoryEntry(val id: String, val title: String, val peer: String,
    val direction: String, val status: String, val updated: Long, val detail: String)

internal object TransferHistory {
    @Synchronized fun load(context: Context): List<HistoryEntry> = runCatching {
        val data = JSONArray(context.getSharedPreferences("nerqo_history", Context.MODE_PRIVATE).getString("entries", "[]"))
        (0 until data.length()).map { i -> val x = data.getJSONObject(i)
            HistoryEntry(x.getString("id"), x.getString("title"), x.getString("peer"),
                x.getString("direction"), x.getString("status"), x.getLong("updated"), x.optString("detail"))
        }.sortedByDescending { it.updated }.take(500)
    }.getOrDefault(emptyList())

    @Synchronized fun record(context: Context, job: TransferJob, status: String, detail: String = ""): Boolean {
        val entries = load(context).filter { it.id != job.id }.toMutableList()
        entries.add(0, HistoryEntry(job.id, job.title, job.deviceName,
            if (job.kind.startsWith("upload")) "Sent" else "Received", status, System.currentTimeMillis(), detail.take(200)))
        val data = JSONArray()
        entries.take(500).forEach { data.put(JSONObject().put("id", it.id).put("title", it.title)
            .put("peer", it.peer).put("direction", it.direction).put("status", it.status)
            .put("updated", it.updated).put("detail", it.detail)) }
        return context.getSharedPreferences("nerqo_history", Context.MODE_PRIVATE).edit().putString("entries", data.toString()).commit()
    }
}
