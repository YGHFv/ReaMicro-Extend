package org.jsoup.helper;

import io.legado.app.help.http.HttpHelperKt;
import io.legado.app.help.http.ObsoleteUrlFactory;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * The upstream Jsoup Request constructor is package-private. Keep its defaults
 * and normalization, supplying only the parser-owned URL transport at read time.
 */
public final class LegadoRequest extends HttpConnection.Request {
    public LegadoRequest() {
        super();
    }

    @Override
    public URL url() {
        URL normalized = super.url();
        try {
            return new URL(
                null,
                normalized.toExternalForm(),
                new ObsoleteUrlFactory(HttpHelperKt.getOkHttpClient())
                    .createURLStreamHandler(normalized.getProtocol())
            );
        } catch (MalformedURLException error) {
            throw new IllegalArgumentException("Invalid normalized request URL", error);
        }
    }
}
