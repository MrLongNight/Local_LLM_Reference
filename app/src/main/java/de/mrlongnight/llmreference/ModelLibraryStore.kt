package de.mrlongnight.llmreference

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ModelLibraryEntry(
    val displayName: String,
    val sourceUri: String,
    val sizeBytes: Long?,
    val sha256: String? = null,
)

class ModelLibraryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("model_library", Context.MODE_PRIVATE)

    fun entries(): List<ModelLibraryEntry> {
        val array = runCatching { JSONArray(prefs.getString("entries", "[]") ?: "[]") }
            .getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                add(ModelLibraryEntry(
                    displayName = name,
                    sourceUri = uri,
                    sizeBytes = item.optLong("size", -1L).takeIf { it >= 0L },
                    sha256 = item.optString("sha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) },
                ))
            }
        }.distinctBy { it.sourceUri }
    }

    fun add(entry: ModelLibraryEntry) {
        val list = entries().filterNot { it.sourceUri == entry.sourceUri }.toMutableList()
        list.add(0, entry)
        persist(list)
        prefs.edit().putString("last_uri", entry.sourceUri).apply()
    }

    fun setLast(uri: String) {
        if (entries().any { it.sourceUri == uri }) prefs.edit().putString("last_uri", uri).apply()
    }

    fun last(): ModelLibraryEntry? {
        val uri = prefs.getString("last_uri", null) ?: return null
        return entries().firstOrNull { it.sourceUri == uri }
    }

    fun remove(uri: String) {
        val remaining = entries().filterNot { it.sourceUri == uri }
        persist(remaining)
        if (prefs.getString("last_uri", null) == uri) {
            prefs.edit().putString("last_uri", remaining.firstOrNull()?.sourceUri).apply()
        }
    }

    private fun persist(entries: List<ModelLibraryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject()
                .put("name", entry.displayName)
                .put("uri", entry.sourceUri)
                .put("size", entry.sizeBytes ?: -1L)
                .put("sha256", entry.sha256 ?: "")
            )
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }
}
