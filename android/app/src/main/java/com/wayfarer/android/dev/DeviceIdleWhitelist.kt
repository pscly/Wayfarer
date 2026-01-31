package com.wayfarer.android.dev

/**
 * Dev-only helper for attempting to add this app to Doze/device-idle whitelist.
 *
 * This is best-effort: root may be missing, denied, or time out.
 */
object DeviceIdleWhitelist {
    fun buildWhitelistCommand(packageName: String): String {
        // Must be EXACT per plan acceptance.
        return "dumpsys deviceidle whitelist +$packageName"
    }

    /**
     * Attempts to whitelist [packageName] using root. Returns a user-displayable status string.
     */
    fun tryWhitelist(packageName: String, shell: RootShell = RootShell()): String {
        return runCatching {
            if (packageName.isBlank()) {
                return@runCatching "ERR: packageName is blank"
            }

            val cmd = buildWhitelistCommand(packageName)
            val result = shell.runSuCommand(cmd)

            if (result.success) {
                val msg = result.output.ifBlank { "(no output)" }
                "OK: $msg"
            } else {
                val detail = listOfNotNull(
                    result.error,
                    result.output.takeIf { it.isNotBlank() },
                    result.exitCode?.let { "exit=$it" },
                ).joinToString("; ").ifBlank { "unknown failure" }
                "ERR: $detail"
            }
        }.getOrElse { e ->
            "ERR: ${e.message ?: e.toString()}"
        }
    }
}
