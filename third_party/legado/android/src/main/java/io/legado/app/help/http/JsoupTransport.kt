package io.legado.app.help.http

import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.helper.LegadoRequest

/**
 * Jsoup 1.16.2 Base.url(URL) normalizes through UrlBuilder and drops the supplied
 * URLStreamHandler. Reattach the upstream OkHttp handler at the public Request
 * getter, where Jsoup obtains the actual connection URL. All Jsoup request and
 * response processing remains the pinned library implementation.
 */
internal fun newSourceConnection(url: String): Connection =
    Jsoup.newSession().request(LegadoRequest()).url(url)
