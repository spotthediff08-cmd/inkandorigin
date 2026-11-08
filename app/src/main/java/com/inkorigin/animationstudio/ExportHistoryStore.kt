package com.inkorigin.animationstudio

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ExportHistoryEntry(val uri: Uri, val fileName: String, val createdAt: Long)

object ExportHistoryStore {
    private fun file(context: Context) = File(context.filesDir, "export_history.json")

    fun list(context: Context): List<ExportHistoryEntry> = runCatching {
        val array = JSONArray(file(context).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val value = item.optString("uri")
            if (value.isBlank()) null else ExportHistoryEntry(
                Uri.parse(value),
                item.optString("fileName", "Exported video"),
                item.optLong("createdAt", 0L)
            )
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    fun add(context: Context, uri: Uri) {
        val existing = list(context).filterNot { it.uri == uri }.toMutableList()
        existing.add(0, ExportHistoryEntry(uri, uri.lastPathSegment ?: "AnimationStudio.mp4", System.currentTimeMillis()))
        val array = JSONArray()
        existing.take(20).forEach { entry ->
            array.put(JSONObject().put("uri", entry.uri.toString()).put("fileName", entry.fileName).put("createdAt", entry.createdAt))
        }
        file(context).writeText(array.toString())
    }
}
