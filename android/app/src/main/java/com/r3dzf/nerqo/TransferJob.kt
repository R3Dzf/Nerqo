package com.r3dzf.nerqo

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class TransferJob(
    val id: String = UUID.randomUUID().toString(), val kind: String, val title: String,
    val host: String, val port: Int, val deviceName: String, val fingerprint: String,
    val uris: List<String> = emptyList(), val target: String = "", val entry: SharedEntry? = null,
    var nextFile: Int = 0, val checkpointHost: String = host, val checkpointPort: Int = port
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("title", title)
        .put("host", host).put("port", port).put("deviceName", deviceName).put("fingerprint", fingerprint)
        .put("checkpointHost", checkpointHost).put("checkpointPort", checkpointPort)
        .put("uris", JSONArray(uris)).put("target", target).put("nextFile", nextFile).apply {
            entry?.let { put("entry", JSONObject().put("id", it.id).put("kind", it.kind).put("sender", it.sender)
                .put("content", it.content).put("size", it.size).put("sha256", it.sha256)) }
        }
    fun save(context: Context) {
        check(context.getSharedPreferences("nerqo_jobs", Context.MODE_PRIVATE).edit().putString("pending", json().toString()).commit()) {
            "Could not save transfer session"
        }
    }
    companion object {
        fun load(context: Context): TransferJob? {
            val text = context.getSharedPreferences("nerqo_jobs", Context.MODE_PRIVATE).getString("pending", null) ?: return null
            return decode(text)
        }
        fun decode(text: String): TransferJob? = runCatching {
                val obj = JSONObject(text)
                val uriList = obj.getJSONArray("uris")
                val entry = obj.optJSONObject("entry")?.let {
                    SharedEntry(it.getString("id"), it.getString("kind"), it.getString("sender"), it.getString("content"), it.getLong("size"), it.getString("sha256"))
                }
                TransferJob(obj.getString("id"), obj.getString("kind"), obj.getString("title"), obj.getString("host"),
                    obj.getInt("port"), obj.getString("deviceName"), obj.getString("fingerprint"),
                    (0 until uriList.length()).map { uriList.getString(it) }, obj.getString("target"), entry, obj.optInt("nextFile"), obj.optString("checkpointHost", obj.getString("host")), obj.optInt("checkpointPort", obj.getInt("port")))
            }.getOrNull()
        fun clear(context: Context) {
            check(context.getSharedPreferences("nerqo_jobs", Context.MODE_PRIVATE).edit().remove("pending").commit())
        }
    }
}
internal data class TransferUi(val title: String, val paused: Boolean = false, val detail: String = "Preparing…",
                               val fraction: Float = 0f)
