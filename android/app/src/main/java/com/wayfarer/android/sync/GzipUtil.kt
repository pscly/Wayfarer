package com.wayfarer.android.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Small gzip helpers for sync payloads.
 *
 * Android-free (JVM stdlib only), designed for unit testing.
 */
object GzipUtil {
    fun gzipUtf8(text: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(text.toByteArray(Charsets.UTF_8))
        }
        return baos.toByteArray()
    }

    fun gunzipToUtf8(bytes: ByteArray): String {
        val bais = ByteArrayInputStream(bytes)
        GZIPInputStream(bais).use { gzip ->
            return gzip.readBytes().toString(Charsets.UTF_8)
        }
    }
}
