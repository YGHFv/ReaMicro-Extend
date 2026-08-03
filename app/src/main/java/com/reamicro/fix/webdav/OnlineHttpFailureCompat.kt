package com.reamicro.fix.webdav

internal class OnlineSourceHttpException(
    val statusCode: Int,
    val detail: String,
    val retryAfterMs: Long? = null,
) : IllegalStateException(
    "HTTP $statusCode${detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}",
)

internal enum class OnlineHttpRetryKind {
    NONE,
    TRANSIENT,
    CIRCUIT_OPEN,
}

internal fun onlineHttpRetryKind(error: Throwable): OnlineHttpRetryKind {
    val http = error as? OnlineSourceHttpException
    val code = http?.statusCode
    val message = error.message.orEmpty()
    if (code == 503 && message.contains("circuit is open", ignoreCase = true)) {
        return OnlineHttpRetryKind.CIRCUIT_OPEN
    }
    return if (code in setOf(429, 500, 502, 503, 504)) {
        OnlineHttpRetryKind.TRANSIENT
    } else {
        OnlineHttpRetryKind.NONE
    }
}

internal fun isPermanentOnlineChapterFailure(error: Throwable): Boolean =
    error.message.orEmpty().contains("unsupported crypt_status=NaN", ignoreCase = true)

internal fun onlineHttpRetryDelayMs(
    error: Throwable,
    transientAttempt: Int,
): Long {
    val explicit = (error as? OnlineSourceHttpException)?.retryAfterMs
    if (explicit != null && explicit > 0L) return explicit.coerceAtMost(120_000L)
    return when (onlineHttpRetryKind(error)) {
        OnlineHttpRetryKind.CIRCUIT_OPEN ->
            (30_000L * transientAttempt.coerceAtLeast(1)).coerceAtMost(120_000L)
        OnlineHttpRetryKind.TRANSIENT ->
            (6_000L * transientAttempt.coerceAtLeast(1)).coerceAtMost(60_000L)
        OnlineHttpRetryKind.NONE -> 0L
    }
}
