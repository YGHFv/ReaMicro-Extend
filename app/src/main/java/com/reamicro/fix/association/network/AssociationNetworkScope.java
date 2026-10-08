package com.reamicro.fix.association.network;

import java.net.URI;
import java.net.URL;

public final class AssociationNetworkScope {
    private static final ThreadLocal<URI> REQUEST = new ThreadLocal<>();
    private AssociationNetworkScope() {}

    public static Scope begin(String url) {
        URI previous = REQUEST.get();
        URI request = null;
        try {
            URI candidate = URI.create(url);
            if (isHttpRequest(candidate)) request = candidate;
        } catch (IllegalArgumentException ignored) { }
        if (request == null) REQUEST.remove(); else REQUEST.set(request);
        return new Scope(previous);
    }

    private static boolean isHttpRequest(URI uri) {
        return "http".equalsIgnoreCase(uri.getScheme())
            && uri.getHost() != null && !uri.getHost().isEmpty()
            && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
            && uri.getRawUserInfo() == null
            && uri.getRawFragment() == null;
    }

    public static boolean allowsHost(String host) {
        URI request = REQUEST.get();
        return request != null && request.getHost().equalsIgnoreCase(host);
    }

    public static boolean allowsUrl(URL url) {
        URI request = REQUEST.get();
        try { return request != null && url != null && request.equals(url.toURI()); }
        catch (java.net.URISyntaxException malformed) { return false; }
    }

    public static final class Scope implements AutoCloseable {
        private final URI previous;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Scope(URI previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) throw new IllegalStateException("Request scope closed on another thread");
            closed = true;
            if (previous == null) REQUEST.remove(); else REQUEST.set(previous);
        }
    }
}
