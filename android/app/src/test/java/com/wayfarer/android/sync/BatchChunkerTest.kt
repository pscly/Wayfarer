package com.wayfarer.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchChunkerTest {
    @Test
    fun emptyList_returnsEmpty() {
        val out = BatchChunker.takeBatch(emptyList<Int>())
        assertTrue(out.isEmpty())
    }

    @Test
    fun listSmallerThanMax_returnsAllItemsPreservingOrder() {
        val items = (1..3).toList()
        val out = BatchChunker.takeBatch(items, maxItems = 100)
        assertEquals(items, out)
    }

    @Test
    fun listLargerThanMax_returnsOnlyFirst100PreservingOrder() {
        val items = (1..150).toList()
        val out = BatchChunker.takeBatch(items) // default max=100

        assertEquals(100, out.size)
        assertEquals((1..100).toList(), out)
    }

    @Test
    fun maxItemsEquals100_isEnforced() {
        val items = (0..200).toList()
        val out = BatchChunker.takeBatch(items, maxItems = 100)
        assertEquals(100, out.size)
    }
}
