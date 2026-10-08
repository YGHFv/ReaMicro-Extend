package com.reamicro.fix.external.source;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.*;

final class SourceUtils {
    private SourceUtils() {}

    private static AutoCloseable beginAssociationRequest(String url) {
        try {
            Class<?> scope = Class.forName("com.reamicro.fix.association.network.AssociationNetworkScope");
            return (AutoCloseable) scope.getMethod("begin", String.class).invoke(null, url);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    static String get(String url, int connectTimeout, int readTimeout) throws IOException {
        for (int hop = 0; hop <= 3; hop++) {
            AutoCloseable scope = beginAssociationRequest(url);
            try {
                return getWithinScope(url, connectTimeout, readTimeout);
            } catch (Redirect next) {
                if (hop == 3) throw new IOException("Too many search redirects");
                url = next.target;
            } finally {
                if (scope != null) try { scope.close(); } catch (Exception ignored) { }
            }
        }
        throw new IOException("Too many search redirects");
    }
    private static final class Redirect extends IOException {
        final String target;
        Redirect(String target) { this.target = target; }
    }
    static String redirectTarget(String original, String location) throws IOException {
        try {
            if (location == null || location.trim().isEmpty()) throw new IOException("Missing redirect location");
            URI from = URI.create(original), target = from.resolve(location);
            String scheme = target.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || target.getHost() == null || target.getRawUserInfo() != null || target.getRawFragment() != null)
                throw new IOException("Unsafe redirect target");
            if ("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(scheme))
                throw new IOException("Refusing HTTPS downgrade");
            return target.toString();
        } catch (IllegalArgumentException malformed) { throw new IOException("Malformed redirect target", malformed); }
    }
    private static String getWithinScope(String url, int connectTimeout, int readTimeout) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36 Edg/138.0.0.0");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            URI uri = URI.create(url);
            connection.setRequestProperty("Referer", uri.getScheme() + "://" + uri.getRawAuthority() + "/");

            int status = connection.getResponseCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                throw new Redirect(redirectTarget(url, connection.getHeaderField("Location")));
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder result = new StringBuilder();
                char[] buffer = new char[4096];
                int size;
                while ((size = reader.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    result.append(buffer, 0, size);
                    if (result.length() > 2_000_000) throw new IOException("Search response too large");
                }
                return result.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL || value instanceof JSONObject || value instanceof JSONArray) continue;
            String text = String.valueOf(value).trim();
            if (!text.isEmpty() && !"null".equals(text)) return text;
        }
        return "";
    }

    static JSONArray array(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof JSONArray) return (JSONArray) value;
            if (value instanceof JSONObject) {
                JSONArray result = array((JSONObject) value, keys);
                if (result != null) return result;
            }
        }
        return null;
    }

    static String unescapeTransport(String value) {
        if (value == null) return "";
        String slash = Character.toString((char) 92);
        return value.replace(slash + "u0026", "&").replace(slash + "u003d", "=")
            .replace(slash + "u003D", "=").replace(slash + "u002f", "/").replace(slash + "u002F", "/");
    }
    static String clean(String value) {
        if (value == null) return "";
        return unescapeTransport(value).replaceAll("(?is)<script[^>]*>.*?</script>", "")
            .replaceAll("(?is)<style[^>]*>.*?</style>", "")
            .replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&" + "quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replaceAll("\\s+", " ").trim();
    }

    static String httpUrl(String raw, String host) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = unescapeTransport(raw).trim().replace("&amp;", "&");
        if (value.startsWith("data:") || value.contains(",{")) return "";
        try {
            URI resolved = URI.create(host.endsWith("/") ? host : host + "/").resolve(value);
            if (!"http".equalsIgnoreCase(resolved.getScheme()) && !"https".equalsIgnoreCase(resolved.getScheme())) return "";
            if (resolved.getHost() == null || resolved.getUserInfo() != null) return "";
            return resolved.toString();
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    static String words(String raw) {
        String text = clean(raw).replace(",", "").replaceAll("\\s+", "").replaceAll("字+$", "");
        if (text.isEmpty() || text.contains("万") || text.contains("亿")) return text;
        try {
            double count = Double.parseDouble(text);
            if (!Double.isFinite(count) || count < 0) return "";
            if (count >= 100_000_000) return decimal(count / 100_000_000) + "亿";
            if (count >= 10_000) return decimal(count / 10_000) + "万";
            return Long.toString((long) count);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "");
    }

    static String status(String raw) {
        String text = clean(raw);
        if ("0".equals(text) || text.contains("连载") || text.contains("连載") || "serial".equalsIgnoreCase(text)) return "连载";
        if ("1".equals(text) || "2".equals(text) || text.contains("完结") || text.contains("已完") || "end".equalsIgnoreCase(text)) return "完结";
        return text;
    }
}
