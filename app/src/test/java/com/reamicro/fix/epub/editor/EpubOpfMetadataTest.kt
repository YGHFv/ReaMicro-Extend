package com.reamicro.fix.epub.editor

import org.junit.Assert.*
import org.junit.Test

class EpubOpfMetadataTest {
    private val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:title id="edition">第二版</dc:title><meta refines="#edition" property="title-type">edition</meta>
<dc:title id="main">正标题</dc:title><meta refines="#main" property="title-type">main</meta>
<dc:creator id="translator">译者</dc:creator><meta refines="#translator" property="role">trl</meta>
<dc:creator id="author">作者</dc:creator><meta refines="#author" property="role">aut</meta>
<dc:identifier id="uid">urn:uuid:test</dc:identifier>
</metadata>
<manifest><item id="x" href="x.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="x"/></spine></package>"""

    @Test fun readsRefinedTitleEditionAndAuthor() {
        val data = EpubOpfMetadata.read(opf)
        assertEquals("正标题", data["title"])
        assertEquals("第二版", data["subtitle"])
        assertEquals("作者", data["author"])
    }

    @Test fun editionEditLeavesMainTitleAndSpineUnchanged() {
        val next = EpubOpfMetadata.update(opf, "subtitle", "第三版 & 修订")
        val data = EpubOpfMetadata.read(next)
        assertEquals("第三版 & 修订", data["subtitle"])
        assertEquals("正标题", data["title"])
        assertTrue(next.contains("urn:uuid:test"))
        assertEquals(opf.substringAfter("</metadata>"), next.substringAfter("</metadata>"))
    }

    @Test fun missingFieldsAreAddedAndRoundTrip() {
        var next = EpubOpfMetadata.update(opf, "language", "zh-CN")
        next = EpubOpfMetadata.update(next, "date", "2026-09-29")
        next = EpubOpfMetadata.update(next, "publisher", "<出版社>")
        assertEquals("zh-CN", EpubOpfMetadata.read(next)["language"])
        assertEquals("2026-09-29", EpubOpfMetadata.read(next)["date"])
        assertEquals("<出版社>", EpubOpfMetadata.read(next)["publisher"])
    }

    @Test fun addingEditionDoesNotReplaceMainTitle() {
        val simple = opf.replace(Regex("""<dc:title id="edition">.*?</meta>"""), "")
        val next = EpubOpfMetadata.update(simple, "subtitle", "新版")
        assertEquals("新版", EpubOpfMetadata.read(next)["subtitle"])
        assertEquals("正标题", EpubOpfMetadata.read(next)["title"])
    }

    @Test fun translatorAndIdentifierAreNotChanged() {
        val next = EpubOpfMetadata.update(opf, "author", "新作者")
        assertTrue(next.contains(">译者</dc:creator>"))
        assertTrue(next.contains("urn:uuid:test"))
        assertEquals("新作者", EpubOpfMetadata.read(next)["author"])
        assertThrows(IllegalArgumentException::class.java) { EpubOpfMetadata.update(opf, "uuid", "bad") }
    }

    @Test fun externalEntitiesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EpubOpfMetadata.read("<!DOCTYPE package SYSTEM \"file:///secret\">$opf")
        }
    }
}
