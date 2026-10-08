package com.reamicro.fix.cloud.local

internal class LocalTaskCredentialException : IllegalStateException("credential_failed")
internal fun transformLocalTaskCredential(value: String?, transform: (String) -> String): String {
    if (value.isNullOrBlank()) return ""
    return try {
        transform(value).also { if (it.isBlank()) throw LocalTaskCredentialException() }
    } catch (_: Exception) {
        throw LocalTaskCredentialException()
    }
}
