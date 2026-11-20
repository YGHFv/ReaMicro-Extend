package com.reamicro.fix.cloud.local

internal class LocalTaskCredentialException : IllegalStateException("登录凭据无法解密，请重新导入该账号凭证")
internal fun transformLocalTaskCredential(value: String?, transform: (String) -> String): String {
    if (value.isNullOrBlank()) return ""
    return try {
        transform(value).also { if (it.isBlank()) throw LocalTaskCredentialException() }
    } catch (_: Exception) {
        throw LocalTaskCredentialException()
    }
}
