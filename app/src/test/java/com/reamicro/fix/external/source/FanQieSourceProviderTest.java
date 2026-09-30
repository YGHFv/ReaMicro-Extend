package com.reamicro.fix.external.source;

import com.reamicro.fix.association.model.BookSearchResult;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FanQieSourceProviderTest {
    private static final String HOST = "https://api.langge.cf";
    private static final String ID = "7123456789012345678";
    private JSONObject book() throws Exception {
        return new JSONObject().put("book_id", ID).put("book_name", "测试小说（别名：旧名字）")
            .put("source", "番茄").put("tab", "小说").put("author", "测试作者")
            .put("thumb_url", "//cdn.example.com/cover.jpg").put("abstract", "<p>简介&nbsp;内容</p>")
            .put("word_number", 125000).put("status", "已完结").put("score", "9.8")
            .put("tags", new JSONArray().put("都市").put("悬疑"))
            .put("last_chapter_update_time", "2026-09-29").put("last_chapter_title", "最终章");
    }
    private String response(JSONObject... rows) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject row : rows) array.put(row);
        return new JSONObject().put("data", array).toString();
    }

    @Test public void parses5516MetadataWithoutLosingLongIds() throws Exception {
        BookSearchResult result = FanQieSourceProvider.parseResponse(response(book()), 20, HOST).get(0);
        assertEquals("测试小说", result.getTitle());
        assertEquals("番茄:" + ID, result.getSourceBookId());
        assertEquals("https://fanqienovel.com/page/" + ID, result.getDetailUrl());
        assertEquals("简介 内容", result.getIntro());
        assertEquals("12.5万", result.getWords());
        assertEquals("完结", result.getStatus());
        assertEquals("https://cdn.example.com/cover.jpg", result.getCoverUrl());
        assertTrue(result.getTags().containsAll(Arrays.asList("都市", "悬疑", "评分 9.8", "更新 2026-09-29", "最新章节 最终章")));
    }

    @Test public void decodesQingtianDescriptorButDoesNotExposeDataUrlAsWebLink() throws Exception {
        JSONObject descriptor = new JSONObject().put("book_id", ID).put("sources", "番茄")
            .put("tab", "小说").put("url", "/toc?id=" + ID);
        String encoded = Base64.getEncoder().encodeToString(descriptor.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject row = book();
        row.remove("book_id");
        row.remove("source");
        row.put("bookUrl", "data:;base64," + encoded + ",{\"type\":\"qingtian\"}");
        BookSearchResult result = FanQieSourceProvider.parseResponse(response(row), 20, HOST).get(0);
        assertEquals("番茄:" + ID, result.getSourceBookId());
        assertEquals("https://fanqienovel.com/page/" + ID, result.getDetailUrl());
        assertFalse(result.getDetailUrl().contains("base64"));
    }

    @Test public void filtersPromotionsOtherSourcesAndNonNovelMedia() throws Exception {
        String body = response(book().put("source", "VIP"), book().put("book_id", "svip"),
            book().put("source", "起点"), book().put("tab", "听书"),
            book().put("book_name", "打赏后获取"), book());
        assertEquals(1, FanQieSourceProvider.parseResponse(body, 20, HOST).size());
    }

    @Test public void deduplicatesBeforeApplyingLimit() throws Exception {
        String body = response(book(), book(), book().put("book_id", "7123456789012345679"));
        assertEquals(2, FanQieSourceProvider.parseResponse(body, 2, HOST).size());
        assertEquals(1, FanQieSourceProvider.parseResponse(body, 1, HOST).size());
        assertTrue(FanQieSourceProvider.parseResponse(body, 0, HOST).isEmpty());
    }

    @Test public void missingIdAndMalformedDescriptorNeverBecomeFakeBooks() throws Exception {
        JSONObject row = book();
        row.remove("book_id");
        row.put("book_url", "data:;base64,not-json");
        assertTrue(FanQieSourceProvider.parseResponse(response(row), 20, HOST).isEmpty());
    }

    @Test public void acceptsBareAndWrappedArraysAndRejectsAuthErrors() throws Exception {
        JSONArray rows = new JSONArray().put(book());
        assertEquals(1, FanQieSourceProvider.parseResponse(rows.toString(), 10, HOST).size());
        String wrapped = new JSONObject().put("code", 200).put("data", new JSONObject().put("list", rows)).toString();
        assertEquals(1, FanQieSourceProvider.parseResponse(wrapped, 10, HOST).size());
        String denied = new JSONObject().put("code", 401).put("data", rows).toString();
        assertTrue(FanQieSourceProvider.parseResponse(denied, 10, HOST).isEmpty());
    }

    @Test public void encodesSearchQueryAndHonors5516NovelShortcut() throws Exception {
        String query = FanQieSourceProvider.normalizeKeyword("x：测试 & + ?#@番茄");
        assertEquals("测试 & + ?#", query);
        String url = FanQieSourceProvider.searchUrl(HOST, query);
        assertTrue(url.contains("title=" + URLEncoder.encode(query, "UTF-8")));
        assertTrue(url.contains("tab=" + URLEncoder.encode("小说", "UTF-8")));
        assertTrue(url.contains("source=" + URLEncoder.encode("番茄", "UTF-8")));
        assertTrue(url.endsWith("&page=1&disabled_sources=0"));
        assertEquals("5.5.16", FanQieSourceProvider.REFERENCE_VERSION);
    }

    @Test public void normalizesCoversAndRejectsNonWebSchemes() {
        assertEquals("https://p3-novel.byteimg.com/origin/novel-pic/cover",
            FanQieSourceProvider.coverUrl("novel-pic/cover", HOST));
        assertEquals("", SourceUtils.httpUrl("javascript:alert(1)", HOST));
        assertEquals("", SourceUtils.httpUrl("data:;base64,abc", HOST));
        assertEquals("https://api.langge.cf/images/a.jpg", SourceUtils.httpUrl("/images/a.jpg", HOST));
    }

    @Test public void acceptsBothArrayAndStringTags() throws Exception {
        JSONObject array = new JSONObject().put("tags", new JSONArray().put("都市").put(new JSONObject().put("name", "悬疑")).put("都市"));
        assertEquals(Arrays.asList("都市", "悬疑"), FanQieSourceProvider.tags(array));
        assertEquals(Arrays.asList("都市", "悬疑"), FanQieSourceProvider.tags(new JSONObject().put("tags", "都市，悬疑")));
    }

    @Test public void fallsBackToOfficialWithoutFabricatingCredentials() throws Exception {
        JSONObject official = new JSONObject().put("data", new JSONObject().put("search_book_data_list",
            new JSONArray().put(new JSONObject().put("book_data", book()))));
        List<String> requests = new ArrayList<>();
        FanQieSourceProvider provider = new FanQieSourceProvider(new String[]{HOST}, (url, connect, read) -> {
            requests.add(url);
            return url.startsWith(HOST) ? "{\"code\":401,\"message\":\"login required\"}" : official.toString();
        });
        assertEquals(1, provider.search("测试", 10).size());
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).startsWith("https://fanqienovel.com/"));
    }


    @Test public void decodesActual5516OpaqueIdAndEscapedCover() throws Exception {
        JSONObject row = book().put("book_id", "NjU2OTk5NzgzOTQzNzQwMTEwMQ")
            .put("thumb_url", "https://cdn.example.com/a?x=1" + (char)92 + "u0026y=2");
        BookSearchResult result = FanQieSourceProvider.parseResponse(response(row), 20, HOST).get(0);
        assertEquals("番茄:6569997839437401101", result.getSourceBookId());
        assertEquals("https://fanqienovel.com/page/6569997839437401101", result.getDetailUrl());
        assertEquals("https://cdn.example.com/a?x=1&y=2", result.getCoverUrl());
    }
    @Test public void arbitraryBase64TextIsNotInventedAsAnId() {
        assertEquals("dGVzdA", FanQieSourceProvider.normalizeBookId("dGVzdA"));
        assertEquals(ID, FanQieSourceProvider.normalizeBookId(ID));
    }
}
