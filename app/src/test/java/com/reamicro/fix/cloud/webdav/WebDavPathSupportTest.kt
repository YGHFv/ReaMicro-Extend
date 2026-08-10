package com.reamicro.fix.cloud.webdav

import java.net.URI
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebDAV 路径与主机名处理的行为锁定。
 *
 * 路径规整错一处，表现就是「目录进不去」或「上传到了错的层级」，而这类问题在真机上
 * 复现一次的成本远高于一条断言。
 */
class WebDavPathSupportTest {

    // ---- 路径规整 ----

    @Test
    fun `路径统一成以单斜杠开头、无重复斜杠`() {
        assertEquals("/a/b", normalizeWebDavPath("a/b"))
        assertEquals("/a/b", normalizeWebDavPath("/a/b/"))
        assertEquals("/a/b", normalizeWebDavPath("//a///b//"))
        assertEquals("/a/b", normalizeWebDavPath("  /a/b  "))
    }

    @Test
    fun `空路径与 root 都规整为根目录`() {
        assertEquals("/", normalizeWebDavPath(""))
        assertEquals("/", normalizeWebDavPath("   "))
        assertEquals("/", normalizeWebDavPath("root"))
        assertEquals("/", normalizeWebDavPath("/"))
    }

    @Test
    fun `拼接子路径时去掉双方多余的斜杠`() {
        assertEquals("/a/b", childWebDavPath("/a", "b"))
        assertEquals("/a/b", childWebDavPath("/a/", "/b/"))
        assertEquals("/a/b", childWebDavPath("a", "  b  "))
        assertEquals("/b", childWebDavPath("/", "b"))
    }

    @Test
    fun `父路径逐级回退，根目录的父仍是根目录`() {
        assertEquals("/a", parentWebDavPath("/a/b"))
        assertEquals("/", parentWebDavPath("/a"))
        assertEquals("/", parentWebDavPath("/"))
        assertEquals("/", parentWebDavPath(""))
    }

    @Test
    fun `路径参数把空值与 root 都转成根目录`() {
        assertEquals("/", webDavPathArg(""))
        assertEquals("/", webDavPathArg("root"))
        assertEquals("/a/b", webDavPathArg("//a/b/"))
    }

    // ---- 明文请求的主机名提取 ----

    @Test
    fun `从 URL 与 URI 对象取主机名`() {
        assertEquals("example.com", cleartextHostOf(URL("http://example.com/a/b")))
        assertEquals("example.com", cleartextHostOf(URI("http://example.com/a/b")))
    }

    @Test
    fun `从带协议的字符串取主机名`() {
        assertEquals("example.com", cleartextHostOf("http://example.com/a"))
        assertEquals("example.com", cleartextHostOf("https://example.com:8443/a"))
    }

    @Test
    fun `从裸主机字符串取主机名，去掉端口与路径`() {
        assertEquals("example.com", cleartextHostOf("example.com"))
        assertEquals("example.com", cleartextHostOf("example.com:8080"))
        assertEquals("example.com", cleartextHostOf("example.com/a/b"))
    }

    @Test
    fun `空值与空串返回空主机名`() {
        assertEquals("", cleartextHostOf(null))
        assertEquals("", cleartextHostOf(""))
        assertEquals("", cleartextHostOf("   "))
    }
}
