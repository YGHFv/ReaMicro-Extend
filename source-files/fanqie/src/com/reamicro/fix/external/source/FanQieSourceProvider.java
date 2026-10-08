package com.reamicro.fix.external.source;

import com.reamicro.fix.association.model.BookSearchResult;
import com.reamicro.fix.association.model.BookSource;
import com.reamicro.fix.association.provider.BookAssociationSearchProvider;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class FanQieSourceProvider implements BookAssociationSearchProvider {
    static final String REFERENCE_VERSION = "5.5.16";
    static final String[] HOSTS = {
        "http://219.154.201.122:5006", "https://api.langge.cf", "https://v2.czyl.cf",
        "https://20.langge.tk", "https://v4.czyl.cf", "https://v5.czyl.cf",
        "https://v7.czyl.cf", "https://v8.czyl.cf", "https://v9.czyl.cf", "https://v10.czyl.cf"
    };
    private static final BookSource SOURCE = new BookSource("fanqie", "番茄小说", null);
    private static final ExecutorService MIRRORS = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "ReaMicroFanQieMirror");
        thread.setDaemon(true);
        return thread;
    });
    interface Transport {
        String get(String url, int connectTimeout, int readTimeout) throws Exception;
    }
    private final String[] hosts;
    private final Transport transport;

    public FanQieSourceProvider() {
        this(HOSTS, SourceUtils::get);
    }
    FanQieSourceProvider(String[] hosts, Transport transport) {
        this.hosts = hosts.clone();
        this.transport = transport;
    }
    @Override public BookSource getSource() { return SOURCE; }

    @Override public List<BookSearchResult> search(String keyword, int limit) {
        String query = normalizeKeyword(keyword);
        if (query.isEmpty() || limit <= 0 || Thread.currentThread().isInterrupted()) return Collections.emptyList();
        int count = Math.min(limit, 50);
        List<BookSearchResult> results = searchMirrors(query, count);
        if (!results.isEmpty() || Thread.currentThread().isInterrupted()) return results;
        try {
            String url = "https://fanqienovel.com/api/author/search/search_book/v1?filter=127,127,127,127"
                + "&page_count=" + count + "&page_index=0&query_type=0&query_word=" + SourceUtils.encode(query);
            return parseOfficial(transport.get(url, 1200, 1800), count);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    static String normalizeKeyword(String keyword) {
        String query = keyword == null ? "" : keyword.trim();
        if (query.startsWith("x:") || query.startsWith("x：")) query = query.substring(2).trim();
        int at = query.lastIndexOf('@');
        if (at >= 0 && isFanqie(query.substring(at + 1))) query = query.substring(0, at).trim();
        return query;
    }

    static String searchUrl(String host, String query) {
        return host.replaceAll("/+$", "") + "/search?title=" + SourceUtils.encode(query)
            + "&tab=" + SourceUtils.encode("小说") + "&source=" + SourceUtils.encode("番茄")
            + "&page=1&disabled_sources=0";
    }

    private List<BookSearchResult> requestHost(String host, String query, int limit) {
        if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
        try {
            return parseResponse(transport.get(searchUrl(host, query), 1800, 6000), limit, host);
        } catch (Exception error) {

            System.err.println("ReaMicroFanQie search transport " + host + ": " + error.getClass().getSimpleName()
                + (String.valueOf(error.getMessage()).contains("Cleartext") ? " (host cleartext policy rejected HTTP)" : ""));
            return Collections.emptyList();
        }
    }

    private List<BookSearchResult> searchMirrors(String query, int limit) {
        if (hosts.length == 0) return Collections.emptyList();
        List<BookSearchResult> primary = requestHost(hosts[0], query, limit);
        if (!primary.isEmpty() || hosts.length == 1) return primary;
        CompletionService<List<BookSearchResult>> completion = new ExecutorCompletionService<>(MIRRORS);
        List<Future<List<BookSearchResult>>> pending = new ArrayList<>();
        try {
            for (int i = 1; i < hosts.length; i++) {
                final String host = hosts[i];
                pending.add(completion.submit(() -> requestHost(host, query, limit)));
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
            for (int remaining = pending.size(); remaining > 0; remaining--) {
                long wait = deadline - System.nanoTime();
                if (wait <= 0) break;
                Future<List<BookSearchResult>> next = completion.poll(wait, TimeUnit.NANOSECONDS);
                if (next == null) break;
                List<BookSearchResult> result = next.get();
                if (!result.isEmpty()) return result;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {

        } finally {
            for (Future<?> future : pending) future.cancel(true);
        }
        return Collections.emptyList();
    }

    static List<BookSearchResult> parseResponse(String body, int limit, String host) throws JSONException {
        if (limit <= 0) return Collections.emptyList();
        Object root = new JSONTokener(body.trim().replaceFirst("^\\uFEFF", "")).nextValue();
        JSONArray rows = root instanceof JSONArray ? (JSONArray) root : null;
        if (root instanceof JSONObject) {
            JSONObject object = (JSONObject) root;
            if (object.has("code") && !Arrays.asList("0", "200").contains(object.optString("code"))) {
                return Collections.emptyList();
            }
            rows = SourceUtils.array(object, "data", "list", "books", "book_list");
        }
        LinkedHashMap<String, BookSearchResult> found = new LinkedHashMap<>();
        if (rows == null) return new ArrayList<>();
        for (int i = 0; i < rows.length() && found.size() < Math.min(limit, 50); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONObject descriptor = descriptor(SourceUtils.first(row, "book_url", "bookUrl", "url", "detail_url"));
            String origin = SourceUtils.first(row, "source", "sources");
            if (origin.isEmpty()) origin = SourceUtils.first(descriptor, "sources", "source");
            String tab = SourceUtils.first(row, "tab", "media");
            if (tab.isEmpty()) tab = SourceUtils.first(descriptor, "tab");
            if ((!origin.isEmpty() && !isFanqie(origin)) || (!tab.isEmpty() && !"小说".equals(tab))) continue;
            String title = SourceUtils.clean(SourceUtils.first(row, "book_name", "bookName", "title", "name")
                .replaceAll("（别名：.*?）", ""));
            String id = SourceUtils.first(row, "book_id", "bookId", "id");
            if (id.isEmpty()) id = SourceUtils.first(descriptor, "book_id", "bookId");
            id = normalizeBookId(id);
            if (id.isEmpty() || title.isEmpty() || id.equalsIgnoreCase("vip") || id.equalsIgnoreCase("svip")
                || title.contains("打赏后获取")) continue;
            String detail = detailUrl(id, SourceUtils.first(row, "book_url", "bookUrl", "url", "detail_url"), host);
            List<String> tags = tags(row);
            BookSearchResult result = new BookSearchResult(
                title, SourceUtils.clean(SourceUtils.first(row, "author", "author_name", "authorName")), SOURCE,
                "番茄:" + id, coverUrl(SourceUtils.first(row, "thumb_url", "thumbUrl", "cover", "cover_url", "coverUrl"), host),
                detail, SourceUtils.clean(SourceUtils.first(row, "abstract", "book_abstract", "description", "intro")),
                SourceUtils.words(SourceUtils.first(row, "word_number", "word_count", "wordCount", "words")),
                SourceUtils.status(SourceUtils.first(row, "status", "creation_status", "creationStatus", "book_status")),
                SOURCE.getDisplayName(), tags
            );
            found.putIfAbsent(result.getStableId(), result);
        }
        return new ArrayList<>(found.values());
    }

    static JSONObject descriptor(String raw) {
        if (!raw.startsWith("data:;base64,") || raw.length() > 65536) return new JSONObject();
        try {
            String encoded = raw.substring("data:;base64,".length()).split(",", 2)[0];
            return new JSONObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static String normalizeBookId(String raw) {
        String id = raw == null ? "" : raw.trim();
        if (id.matches("[0-9]{5,24}")) return id;

        if (id.length() >= 8 && id.length() <= 64) {
            for (Base64.Decoder decoder : new Base64.Decoder[] {Base64.getDecoder(), Base64.getUrlDecoder()}) {
                try {
                    String decoded = new String(decoder.decode(id), StandardCharsets.UTF_8).trim();
                    if (decoded.matches("[0-9]{5,24}")) return decoded;
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return id;
    }
    static String detailUrl(String id, String raw, String host) {

        if (id.matches("[0-9]{5,24}")) return "https://fanqienovel.com/page/" + id;
        return SourceUtils.httpUrl(raw, host);
    }

    static String coverUrl(String raw, String host) {
        String value = SourceUtils.unescapeTransport(raw).trim();
        String path = value.replaceFirst("^/+", "");
        if (path.startsWith("novel-pic/") || path.startsWith("novel-images/") || path.startsWith("novel-static/")) {
            return "https://p3-novel.byteimg.com/origin/" + path;
        }
        return SourceUtils.httpUrl(value, host);
    }

    static List<String> tags(JSONObject row) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Object raw = row.opt("tags");
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.length(); i++) {
                Object tag = array.opt(i);
                addTag(result, tag instanceof JSONObject ? SourceUtils.first((JSONObject) tag, "name", "tag_name") : String.valueOf(tag));
            }
        } else if (raw instanceof String) {
            for (String tag : ((String) raw).split("[,，|、]")) addTag(result, tag);
        }
        String score = SourceUtils.first(row, "score");
        if (!score.isEmpty() && !"0".equals(score)) addTag(result, "评分 " + score);
        String update = SourceUtils.first(row, "last_chapter_update_time");
        if (!update.isEmpty()) addTag(result, "更新 " + update);
        String chapter = SourceUtils.first(row, "last_chapter_title");
        if (!chapter.isEmpty()) addTag(result, "最新章节 " + chapter);
        return new ArrayList<>(result);
    }
    private static void addTag(Set<String> tags, String value) {
        String clean = SourceUtils.clean(value);
        if (!clean.isEmpty() && !"null".equals(clean)) tags.add(clean);
    }
    private static boolean isFanqie(String value) {
        String clean = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.asList("番茄", "番茄小说", "fanqie", "fanqienovel").contains(clean);
    }

    static List<BookSearchResult> parseOfficial(String body, int limit) throws JSONException {
        JSONObject root = new JSONObject(body);
        JSONArray books = SourceUtils.array(root, "data", "search_book_data_list", "book_list", "books");
        JSONArray normalized = new JSONArray();
        if (books != null) for (int i = 0; i < books.length(); i++) {
            JSONObject row = books.optJSONObject(i);
            if (row == null) continue;
            JSONObject nested = row.optJSONObject("book_data");
            if (nested == null) nested = row.optJSONObject("book");
            normalized.put(nested == null ? row : nested);
        }
        return parseResponse(normalized.toString(), limit, "https://fanqienovel.com");
    }
}
