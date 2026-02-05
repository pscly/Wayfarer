package com.wayfarer.android.api

data class ApiErrorEnvelope(
    val code: String?,
    val message: String?,
    val traceId: String?,
)

object ApiErrorParser {
    private val codeRe = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
    private val messageRe = Regex("\"message\"\\s*:\\s*\"([^\"]*)\"")
    private val traceIdRe = Regex("\"trace_id\"\\s*:\\s*\"([^\"]*)\"")

    fun parse(bodyText: String?): ApiErrorEnvelope? {
        val raw = bodyText?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (!raw.startsWith("{")) return null

        fun extract(re: Regex): String? {
            val m = re.find(raw) ?: return null
            val v = m.groupValues.getOrNull(1)?.trim().orEmpty()
            return v.takeIf { it.isNotBlank() }
        }

        val env =
            ApiErrorEnvelope(
                code = extract(codeRe),
                message = extract(messageRe),
                traceId = extract(traceIdRe),
            )
        if (env.code == null && env.message == null && env.traceId == null) return null
        return env
    }
}
