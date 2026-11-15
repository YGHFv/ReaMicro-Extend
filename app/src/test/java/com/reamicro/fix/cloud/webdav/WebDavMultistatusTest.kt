package com.reamicro.fix.cloud.webdav

import org.junit.Assert.*
import org.junit.Test

class WebDavMultistatusTest {
    @Test fun failedPropertiesDoNotTurnFilesIntoDirectories() {
        val resources = WebDavMultistatus.parse("""
            <d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/book.epub</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
            <d:getcontentlength>999</d:getcontentlength></d:prop>
            <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            <d:propstat><d:prop><d:displayname>测试书籍</d:displayname>
            <d:getcontentlength>123</d:getcontentlength><d:resourcetype/></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
        """.trimIndent())
        assertEquals(1, resources.size)
        assertFalse(resources.single().isDirectory)
        assertEquals(123L, resources.single().size)
        assertEquals("测试书籍", resources.single().displayName)
    }

    @Test fun namespaceAndResponseStatusAreRespected() {
        val resources = WebDavMultistatus.parse("""
            <multistatus xmlns="DAV:" xmlns:x="urn:other">
            <response><href>/missing</href><status>HTTP/1.1 404 Not Found</status>
            <propstat><prop><resourcetype><collection/></resourcetype></prop>
            <status>HTTP/1.1 200 OK</status></propstat></response>
            <response><href>/books</href><propstat><prop>
            <x:displayname>错误名称</x:displayname><displayname>图书</displayname>
            <resourcetype><collection/></resourcetype></prop>
            <status>HTTP/2 200</status></propstat></response></multistatus>
        """.trimIndent())
        assertEquals(1, resources.size)
        assertEquals("图书", resources.single().displayName)
        assertTrue(resources.single().isDirectory)
    }

    @Test fun externalEntitiesAndNonDavDocumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavMultistatus.parse("""<!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]><x/>""")
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavMultistatus.parse("<multistatus/>") }
    }
}
