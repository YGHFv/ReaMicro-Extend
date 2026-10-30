package com.reamicro.fix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProfileTest {
    private val sample = """
        profile=2.3.3-b2
        package=${HostProfile.HOST_PACKAGE}
        sizeHint=44805028
        requiredClass=app.zhendong.reamicro.MainActivity
        absentClass=org.epub.html.node.ContentDom
        class=org.epub.html.node.ContentDom=bv1
        class=app.zhendong.reamicro.ui.reader.ReaderViewModel=bo8
        method=app.zhendong.reamicro.ui.reader.ReaderViewModel#updateUiState=a
        field=app.zhendong.reamicro.ui.reader.ReaderViewModel#searchMarks=b
    """.trimIndent()

    private val known = setOf(
        "app.zhendong.reamicro.MainActivity",
        "bv1",
        "bo8",
    )

    private fun exists(name: String) = name in known

    @Test
    fun `parses classes methods and fields`() {
        val parsed = HostProfile.parse(sample)
        assertEquals("2.3.3-b2", parsed.fingerprint.id)
        assertEquals("bv1", parsed.classOf("org.epub.html.node.ContentDom"))
        assertEquals("a", parsed.methodOf("app.zhendong.reamicro.ui.reader.ReaderViewModel", "updateUiState"))
        assertEquals("b", parsed.fieldOf("app.zhendong.reamicro.ui.reader.ReaderViewModel", "searchMarks"))
        assertEquals(
            "app.zhendong.reamicro.ui.reader.ReaderViewModel",
            parsed.logicalClassOf("bo8"),
        )
    }

    @Test
    fun `matches only when fingerprint holds`() {
        val parsed = HostProfile.parse(sample)
        assertTrue(HostProfile.matches(parsed, HostProfile.HOST_PACKAGE, 44_805_028L, ::exists))
        // NPatch 免 Root 包会改写包名，但宿主 APK 内容不变，仍应命中正式版 profile。
        assertTrue(
            HostProfile.matches(
                parsed,
                "${HostProfile.HOST_PACKAGE}.fix",
                44_805_028L,
                ::exists,
            ),
        )
        assertFalse(HostProfile.matches(parsed, "other.package", 44_805_028L, ::exists))
        assertFalse(HostProfile.matches(parsed, "app.zhendong.reamicro-other", 44_805_028L, ::exists))
        // 体积超出容差：说明是别的构建，不能误用该 profile
        assertFalse(HostProfile.matches(parsed, HostProfile.HOST_PACKAGE, 67_654_280L, ::exists))
    }

    @Test
    fun `rejects profile when absent marker class exists`() {
        val parsed = HostProfile.parse(sample)
        val unobfuscated = setOf("app.zhendong.reamicro.MainActivity", "org.epub.html.node.ContentDom")
        assertFalse(HostProfile.matches(parsed, HostProfile.HOST_PACKAGE, 44_805_028L) { it in unobfuscated })
    }

    @Test
    fun `identity resolver returns logical names untouched`() {
        HostSymbols.reset()
        assertTrue(HostSymbols.isIdentity)
        assertEquals(
            "org.epub.html.node.ContentDom",
            HostSymbols.resolveClass("org.epub.html.node.ContentDom"),
        )
        assertEquals("updateUiState", HostSymbols.resolveMethodName("any.Class", "updateUiState"))
        assertEquals("searchMarks", HostSymbols.resolveFieldName("any.Class", "searchMarks"))
        assertNull(HostProfile.parse(sample).classOf("not.in.profile"))
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val parsed = HostProfile.parse("# 说明\n\n$sample\n# 结尾")
        assertEquals("2.3.3-b2", parsed.fingerprint.id)
    }

    @Test
    fun `erased interface alias does not steal reverse class identity`() {
        val parsed = HostProfile.parse(
            sample + "\nclass=host.Model=q56\ntypeAlias=host.Receiver=q56",
        )
        assertEquals("q56", parsed.classOf("host.Receiver"))
        assertEquals("host.Model", parsed.logicalClassOf("q56"))
        assertEquals(2, parsed.classes.count { it.value == "q56" } + parsed.typeAliases.size)
    }

    @Test
    fun `syntax errors are rejected`() {
        val bad = listOf(
            "sizeHint=abc\nrequiredClass=x",
            "profile=p\nclass=missing-pair",
            "profile=p\nmethod=noclass\nrequiredClass=x",
            "profile=p",
        )
        bad.forEach { text ->
            assertTrue("应拒绝：$text", runCatching { HostProfile.parse(text) }.isFailure)
        }
    }

    @Test
    fun `repo bundled profiles stay parseable`() {
        val dir = java.io.File("src/main/assets/host-profiles")
        val files = dir.listFiles { file: java.io.File -> file.name.endsWith(".profile") } ?: emptyArray()
        files.forEach { file ->
            val parsed = HostProfile.parse(file.readText(Charsets.UTF_8), file.name)
            assertTrue("${file.name} 缺少映射", HostProfile.specificity(parsed) > 0)
        }
    }
}
