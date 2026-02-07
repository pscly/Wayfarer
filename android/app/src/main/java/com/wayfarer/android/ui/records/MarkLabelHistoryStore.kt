package com.wayfarer.android.ui.records

import android.content.Context
import org.json.JSONArray

object MarkLabelHistoryStore {
    private const val PREF_NAME = "wayfarer_ui_prefs"
    private const val KEY_RECENT_LABELS = "recent_mark_labels_v1"

    fun read(context: Context, maxItems: Int = 12): List<String> {
        val raw = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_LABELS, null)
            ?.trim()
            .orEmpty()

        if (raw.isBlank()) return emptyList()

        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotBlank()) out.add(v)
        }
        return out.take(maxItems)
    }

    fun add(context: Context, label: String, maxItems: Int = 12) {
        val normalized = label.trim()
        if (normalized.isBlank()) return

        val existing = read(context, maxItems = maxItems)
        val merged = ArrayList<String>(maxItems)
        merged.add(normalized)
        for (s in existing) {
            if (s.equals(normalized, ignoreCase = true)) continue
            if (merged.size >= maxItems) break
            merged.add(s)
        }

        val arr = JSONArray()
        for (s in merged) arr.put(s)
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT_LABELS, arr.toString())
            .apply()
    }
}

