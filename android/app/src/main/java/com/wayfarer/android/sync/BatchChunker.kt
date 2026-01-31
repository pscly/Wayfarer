package com.wayfarer.android.sync

/**
 * Small helper for selecting the next upload batch.
 *
 * Keeps order and caps batch size (default 100) per sync spec.
 */
object BatchChunker {
    fun <T> takeBatch(items: List<T>, maxItems: Int = 100): List<T> {
        if (items.isEmpty()) return emptyList()
        if (maxItems <= 0) return emptyList()
        return items.take(maxItems)
    }
}
