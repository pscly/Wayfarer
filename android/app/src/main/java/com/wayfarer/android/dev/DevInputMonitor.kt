package com.wayfarer.android.dev

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Dev-only best-effort `/dev/input/event*` monitor/probe.
 *
 * Design goals:
 * - Must be explicit: nothing starts automatically.
 * - Must be stoppable and idempotent.
 * - Must never throw to callers; always returns a short status string.
 */
class DevInputMonitor(
    private val pollIntervalMs: Long = 1_000,
    private val probeReadTimeoutMs: Long = 500,
) {
    @Volatile
    private var running: Boolean = false

    @Volatile
    private var worker: Thread? = null

    private val lastStatusRef: AtomicReference<String> = AtomicReference("IDLE")

    fun status(): String = lastStatusRef.get()

    fun start(): String {
        return runCatching {
            if (running) {
                return@runCatching "OK: already running"
            }
            running = true

            val t = Thread {
                while (running) {
                    val status = probeOnceInternal()
                    lastStatusRef.set(status)
                    try {
                        Thread.sleep(pollIntervalMs)
                    } catch (_: InterruptedException) {
                        // Stop requested.
                    }
                }
            }
            t.isDaemon = true
            t.name = "DevInputMonitor"
            worker = t
            t.start()
            "OK: started"
        }.getOrElse { e ->
            running = false
            worker = null
            "ERR: ${e.message ?: e.toString()}"
        }
    }

    fun stop(): String {
        return runCatching {
            if (!running) {
                worker = null
                return@runCatching "OK: not running"
            }

            running = false
            val t = worker
            worker = null
            t?.interrupt()
            t?.join(1_000)
            "OK: stopped"
        }.getOrElse { e ->
            running = false
            worker = null
            "ERR: ${e.message ?: e.toString()}"
        }
    }

    /**
     * One-shot probe for UI buttons.
     */
    fun probeOnce(): String {
        return probeOnceInternal().also { lastStatusRef.set(it) }
    }

    private fun probeOnceInternal(): String {
        return runCatching {
            val dir = File(DEV_INPUT_DIR)
            if (!dir.exists()) {
                return@runCatching "ERR: $DEV_INPUT_DIR missing"
            }
            if (!dir.isDirectory) {
                return@runCatching "ERR: $DEV_INPUT_DIR is not a directory"
            }

            val events = dir.listFiles { _, name -> name.startsWith(EVENT_PREFIX) }?.sortedBy { it.name }
            if (events == null) {
                return@runCatching "ERR: cannot list $DEV_INPUT_DIR"
            }
            if (events.isEmpty()) {
                return@runCatching "ERR: no ${EVENT_PREFIX}* found"
            }

            for (candidate in events) {
                val attempt = tryReadSmallChunk(candidate, timeoutMs = probeReadTimeoutMs)
                if (attempt.success) {
                    return@runCatching "OK: ${candidate.name} read=${attempt.bytesRead}"
                }
            }

            // If none were readable, return the first error for a stable message.
            val firstErr = tryReadSmallChunk(events.first(), timeoutMs = probeReadTimeoutMs).error
            "ERR: ${firstErr ?: "permission denied"}"
        }.getOrElse { e ->
            "ERR: ${e.message ?: e.toString()}"
        }
    }

    private data class ReadAttempt(
        val success: Boolean,
        val bytesRead: Int,
        val error: String?,
    )

    private fun tryReadSmallChunk(file: File, timeoutMs: Long): ReadAttempt {
        return runCatching {
            val streamRef = AtomicReference<FileInputStream?>(null)
            val outRef = AtomicReference<ReadAttempt?>(null)

            val t = Thread {
                val attempt = runCatching {
                    FileInputStream(file).use { fis ->
                        streamRef.set(fis)
                        val buf = ByteArray(READ_SIZE)
                        val n = fis.read(buf)
                        if (n <= 0) {
                            ReadAttempt(success = true, bytesRead = 0, error = null)
                        } else {
                            ReadAttempt(success = true, bytesRead = n, error = null)
                        }
                    }
                }.getOrElse { e ->
                    ReadAttempt(success = false, bytesRead = 0, error = e.message ?: e.toString())
                }
                outRef.set(attempt)
            }
            t.isDaemon = true
            t.name = "DevInputProbe"
            t.start()

            t.join(timeoutMs)
            val done = outRef.get()
            if (done != null) {
                return@runCatching done
            }

            // Timed out: close stream to unblock the read if possible.
            runCatching { streamRef.get()?.close() }
            t.join(200)
            ReadAttempt(success = false, bytesRead = 0, error = "timeout reading ${file.name}")
        }.getOrElse { e ->
            ReadAttempt(success = false, bytesRead = 0, error = e.message ?: e.toString())
        }
    }

    companion object {
        private const val DEV_INPUT_DIR: String = "/dev/input"
        private const val EVENT_PREFIX: String = "event"
        private const val READ_SIZE: Int = 16
    }
}
