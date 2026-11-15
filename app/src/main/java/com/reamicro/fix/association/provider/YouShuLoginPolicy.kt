package com.reamicro.fix.association.provider

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

enum class YouShuLoginStatus {
    VALID,
    EXPIRED,
    UNKNOWN,
}

/** A search failure is not evidence that the website has ended the session. */
internal object YouShuLoginPolicy {
    fun classify(html: String?): YouShuLoginStatus {
        if (html.isNullOrBlank()) return YouShuLoginStatus.UNKNOWN
        val lower = html.lowercase()
        if (CHALLENGE_MARKERS.any(lower::contains)) return YouShuLoginStatus.UNKNOWN

        // A persistent login widget can still be present beside the signed-in account menu.
        if (LOGOUT_LINK.containsMatchIn(html) ||
            (ACCOUNT_LINK.containsMatchIn(html) && html.contains("退出"))) {
            return YouShuLoginStatus.VALID
        }

        val hasBookResult = BOOK_LINK.containsMatchIn(html)
        val hasSearchLoginGate = lower.contains("login.php") && lower.contains("jumpurl=") &&
            (lower.contains("search.php") || lower.contains("/search/all/")) &&
            (html.contains("请先登录") || html.contains("登录后") || html.contains("用户登录"))
        val hasPrimaryPasswordForm = FORMS.findAll(html).any { form ->
            val attributes = form.groupValues[1]
            val body = form.groupValues[2]
            PASSWORD_FIELD.containsMatchIn(body) &&
                !AUXILIARY_LOGIN_FORM.containsMatchIn(attributes) &&
                (LOGIN_ACTION.containsMatchIn(attributes) ||
                    (LOGIN_FORM_NAME.containsMatchIn(attributes) && html.contains("用户登录")))
        }
        if (!hasBookResult && (hasSearchLoginGate || hasPrimaryPasswordForm)) {
            return YouShuLoginStatus.EXPIRED
        }
        if (hasBookResult) return YouShuLoginStatus.VALID
        return YouShuLoginStatus.UNKNOWN
    }

    fun combine(results: List<YouShuLoginStatus>): YouShuLoginStatus = when {
        YouShuLoginStatus.VALID in results -> YouShuLoginStatus.VALID
        results.isEmpty() || YouShuLoginStatus.UNKNOWN in results -> YouShuLoginStatus.UNKNOWN
        else -> YouShuLoginStatus.EXPIRED
    }

    private val CHALLENGE_MARKERS = listOf(
        "just a moment", "cf-chl-", "challenge-platform", "checking your browser",
        "verify you are human", "id=\"challenge-form\"", "id='challenge-form'",
    )
    private val LOGOUT_LINK = Regex(
        """\b(?:href|action)\s*=\s*["'][^"']*(?:logout(?:\.php)?|[?&]action=logout)[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    private val ACCOUNT_LINK = Regex(
        """\bhref\s*=\s*["'][^"']*(?:userdetail\.php|user\.php|/user/|/profile/)[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    private val BOOK_LINK = Regex(
        """\bhref\s*=\s*["'][^"']*(?:/book/|articleinfo\.php|bookinfo\.php)[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    private val FORMS = Regex("""<form\b([^>]*)>([\s\S]*?)</form\s*>""", RegexOption.IGNORE_CASE)
    private val PASSWORD_FIELD = Regex(
        """\b(?:type|name)\s*=\s*(?:["']password["']|password(?=[\s>]))""",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_ACTION = Regex(
        """\baction\s*=\s*["'][^"']*login\.php[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    private val LOGIN_FORM_NAME = Regex(
        """\b(?:name|id)\s*=\s*["']frmlogin["']""", RegexOption.IGNORE_CASE,
    )
    private val AUXILIARY_LOGIN_FORM = Regex(
        """\b(?:name|id)\s*=\s*["'](?:t_frmlogin|fixed-login-box)["']""",
        RegexOption.IGNORE_CASE,
    )
}

internal object YouShuCookiePolicy {
    const val BASE_URL = "https://www.youshu.me"
    const val MOBILE_BASE_URL = "https://m.youshu.me"

    fun supports(url: String): Boolean = runCatching {
        val parsed = URI(url)
        parsed.scheme.equals("https", ignoreCase = true) && parsed.rawUserInfo == null &&
            (parsed.host.equals("m.youshu.me", ignoreCase = true) ||
                parsed.host.equals("www.youshu.me", ignoreCase = true))
    }.getOrDefault(false)

    fun hasLoginCookie(header: String): Boolean = header.split(';').any { item ->
        val separator = item.indexOf('=')
        separator > 0 && item.substring(0, separator).trim().equals("jieqiUserInfo", ignoreCase = true) &&
            item.substring(separator + 1).isNotBlank()
    }

    /** The precompiled provider needs one candidate to decide whether to attempt HTTP first. */
    fun candidateHeader(readCookie: (String) -> String): String {
        val candidates = CANDIDATE_URLS.map(readCookie)
        return candidates.firstOrNull(::hasLoginCookie)
            ?: candidates.firstOrNull(String::isNotBlank).orEmpty()
    }

    /** Rebind only an explicitly requested YouShu session; all other request headers are retained. */
    fun requestHeaders(
        url: String,
        headers: Map<String, String>,
        readCookie: (String) -> String,
    ): Map<String, String> {
        val result = headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }.toMutableMap()
        if (supports(url)) readCookie(url).takeIf(String::isNotBlank)?.let { result["Cookie"] = it }
        return result
    }

    private val CANDIDATE_URLS = listOf(
        "$MOBILE_BASE_URL/search/all/test/1.html",
        "$MOBILE_BASE_URL/modules/article/search.php",
        "$MOBILE_BASE_URL/",
        "$BASE_URL/search/all/test/1.html",
        "$BASE_URL/modules/article/search.php",
        "$BASE_URL/",
    )
}

/** Sealing the result also fences a JavaScript callback arriving after timeout or cancellation. */
internal class YouShuFetchCompletion {
    private data class Result(val html: String?)
    private val result = AtomicReference<Result?>()
    val completed: Boolean get() = result.get() != null
    val html: String? get() = result.get()?.html
    fun finish(html: String?): Boolean = result.compareAndSet(null, Result(html))
}
