package com.wayfarer.android.dev

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Best-effort root runner. Must never throw to callers.
 *
 * This is intentionally minimal and Android-free so it can be used from unit-tested
 * code paths without requiring instrumentation.
 */
class RootShell(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val runtime: Runtime = Runtime.getRuntime(),
) {
    data class Result(
        val success: Boolean,
        val command: String,
        val exitCode: Int?,
        val output: String,
        val error: String?,
    )

    fun runSuCommand(command: String): Result {
        return runCatching {
            val process = runtime.exec(arrayOf("su", "-c", command))

            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            // Drain streams to avoid blocking on full buffers.
            val outThread = Thread {
                runCatching { process.inputStream.use { it.copyTo(stdout) } }
            }
            val errThread = Thread {
                runCatching { process.errorStream.use { it.copyTo(stderr) } }
            }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                process.waitFor(200, TimeUnit.MILLISECONDS)
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(200, TimeUnit.MILLISECONDS)
                }
            }

            // Give the drain threads a moment to finish; they are daemons.
            outThread.join(200)
            errThread.join(200)

            val exit = if (finished) process.exitValue() else null
            val outStr = stdout.toString(Charsets.UTF_8.name()).trim()
            val errStr = stderr.toString(Charsets.UTF_8.name()).trim().ifBlank { null }

            Result(
                success = (finished && exit == 0),
                command = command,
                exitCode = exit,
                output = outStr,
                error = errStr,
            )
        }.getOrElse { e ->
            Result(
                success = false,
                command = command,
                exitCode = null,
                output = "",
                error = e.message ?: e.toString(),
            )
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 3_000
    }
}
