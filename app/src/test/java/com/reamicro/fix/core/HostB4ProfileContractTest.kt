package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定 B4 已取证的边界，防止生成器再次按相同短名错配页表或合并门面。 */
class HostB4ProfileContractTest {
    private fun assets(): File = listOf(
        File("src/main/assets"),
        File("app/src/main/assets"),
    ).firstOrNull { it.isDirectory } ?: error("模块资产目录不存在")

    private fun profile(): HostProfile.Parsed {
        val file = File(assets(), "host-profiles/2.3.3-b4.profile")
        return HostProfile.parse(file.readText(Charsets.UTF_8), file.path)
    }

    @Test
    fun `reader draw accessors follow actual b4 node and canvas bytecode`() {
        val parsed = profile()
        assertEquals("n", parsed.fieldOf("androidx.compose.ui.Modifier\$Node", "isAttached"))
        assertEquals("f", parsed.fieldOf("androidx.compose.ui.text.MultiParagraph", "lineCount"))
        assertEquals("b", parsed.methodOf("androidx.compose.ui.text.TextLayoutResult", "getBoundingBox"))
        assertEquals("i", parsed.methodOf("androidx.compose.ui.text.TextLayoutResult", "getLineStart"))
        assertEquals("l0", parsed.methodOf("androidx.compose.ui.graphics.drawscope.DrawScope", "getDrawContext"))
        assertEquals("t", parsed.methodOf("androidx.compose.ui.graphics.drawscope.DrawContext", "getCanvas"))
        assertEquals("c", parsed.methodOf("androidx.compose.ui.graphics.AndroidCanvas_androidKt", "getNativeCanvas"))
        assertEquals("o", parsed.methodOf("androidx.compose.ui.node.DrawModifierNodeKt", "invalidateDraw"))
        assertEquals("a", parsed.methodOf("androidx.compose.foundation.layout.RowKt", "rowMeasurePolicy"))
        assertEquals("e79", parsed.classOf("androidx.compose.foundation.layout.RowKt"))
        assertEquals("a", parsed.methodOf(
            "app.zhendong.reamicro.arch.components.JustifyText_androidKt", "JustifyText",
        ))
        assertEquals("a", parsed.methodOf("androidx.compose.foundation.text.BasicTextKt", "BasicTextAnnotated"))
    }

    @Test
    fun `highlight entry binds inside the highlight page before its dialogue preset`() {
        val parsed = profile()
        val screen = "app.zhendong.reamicro.ui.reader.compose.ReaderHighlightScreenKt"
        assertEquals("i", parsed.methodOf(screen, "PresetDialogueRow"))
        assertEquals("je8", parsed.classOf("ReaMicroHost.ReaderHighlightPagerContent"))
        assertEquals("y", parsed.methodOf("ReaMicroHost.ReaderHighlightPagerContent", "invoke"))
        val sources = assets().parentFile.resolve("java/com/reamicro/fix/hook/ReaderHook.HostHooks.kt")
            .readText(Charsets.UTF_8)
        val b4Entry = sources.substringAfter("internal fun ReaderHook.hookReaderHighlightScreenEntry()")
            .substringAfter("if (HostSymbols.profileId == \"2.3.3-b4\")")
            .substringBefore("return@install")
        assertTrue(b4Entry.contains("method.hostNameMatches(\"PresetDialogueRow\")"))
        assertFalse(b4Entry.contains("HighlightPageContentTabs"))
        assertTrue(b4Entry.contains("param.args?.getOrNull(5)"))
    }

    @Test
    fun `b4 fingerprint and class names are dex names not jadx display aliases`() {
        val parsed = profile()
        assertEquals("2.3.3-b4", parsed.fingerprint.id)
        assertEquals(44_845_737L, parsed.fingerprint.sizeHint)
        assertTrue(parsed.classes.size >= 342)
        assertFalse(parsed.classes.values.any { it.startsWith("defpackage.") })
        assertEquals("ym8", parsed.classOf("app.zhendong.reamicro.ui.reader.ReaderUiIntent"))
        assertEquals("job", parsed.classOf("app.zhendong.reamicro.arch.UiIntent"))
        assertEquals("lia", parsed.classOf("androidx.compose.runtime.State"))
        assertEquals("wm6", parsed.classOf("androidx.compose.runtime.MutableState"))
        assertEquals("add", parsed.classOf("ReaMicroHost.StateFactory"))
        assertEquals("e", HostNativeUiAbi.method("2.3.3-b4", "StateFactory", "d"))
        assertEquals("getValue", parsed.methodOf("androidx.compose.runtime.State", "getValue"))
        assertEquals("setValue", parsed.methodOf("androidx.compose.runtime.MutableState", "setValue"))
    }

    @Test
    fun `reader maps follow aligned field accesses not b3 member names`() {
        val parsed = profile()
        val reader = "app.zhendong.reamicro.ui.reader.ReaderViewModel"
        assertEquals("xo8", parsed.classOf(reader))
        assertEquals("y", parsed.methodOf(reader, "getVirtualPage"))
        assertEquals("q", parsed.methodOf(reader, "ensureVirtualPageMapped"))
        assertEquals("I", parsed.methodOf(reader, "jumpChapterByStep"))
        assertEquals("L", parsed.methodOf(reader, "recenterVirtualWindow"))
        assertEquals("M", parsed.methodOf(reader, "recenterVirtualWindow\$readLocalSpine"))
        assertEquals("l2a", parsed.classOf("kotlinx.coroutines.flow.SharedFlowImpl"))
        assertEquals("m", parsed.methodOf("kotlinx.coroutines.flow.SharedFlowImpl", "tryEmit"))
        val fields = mapOf(
            "_initialPagerPage" to "t0",
            "_pagerCorrection" to "z0",
            "virtualPages" to "l0",
            "virtualToReal" to "i0",
            "realToVirtual" to "j0",
            "virtualPagesRevision\$delegate" to "m0",
            "virtualPageLoadMutex" to "p0",
            "spinePagesCache" to "g0",
            "searchableTextBySpine" to "h0",
            "loadedSpines" to "k0",
            "minLoadedSpine" to "n0",
            "maxLoadedSpine" to "o0",
            "pagerMinPage" to "v0",
            "pagerMaxPage" to "x0",
        )
        fields.forEach { (logical, actual) ->
            assertEquals(logical, actual, parsed.fieldOf(reader, logical))
        }
    }

    @Test
    fun `b4 selection snapshot endpoints resolve through verified field mappings`() {
        val parsed = profile()
        val snapshot = "org.epub.ui.SelectionSnapshot"
        val position = "org.epub.ui.SelectionPosition"
        val range = "org.epub.html.EpubCFIRange"
        assertEquals("js9", parsed.classOf(snapshot))
        assertEquals("a", parsed.fieldOf(snapshot, "anchor"))
        assertEquals("b", parsed.fieldOf(snapshot, "focus"))
        assertEquals("c", parsed.fieldOf(snapshot, "roleId"))
        assertEquals("a", parsed.fieldOf(position, "nodeId"))
        assertEquals("b", parsed.fieldOf(position, "cfi"))
        assertEquals("c", parsed.fieldOf(position, "offset"))
        assertEquals("c", parsed.fieldOf(range, "anchor"))
        assertEquals("b", parsed.fieldOf(range, "endExclusive"))
        assertEquals("a", parsed.fieldOf(range, "start"))
        assertEquals("c", parsed.methodOf("org.epub.ui.EpubSelectionController", "clearSelection"))
    }

    @Test
    fun `merged facades resolve to the owner of the actual business method`() {
        val parsed = profile()
        assertEquals("t2e", parsed.classOf("app.zhendong.reamicro.ui.profile.components.CommunityKt"))
        assertEquals("k5e", parsed.classOf("app.zhendong.reamicro.ui.setting.AboutScreenKt"))
        assertEquals("moc", parsed.classOf("app.zhendong.reamicro.ui.setting.SettingsScreenKt"))
        assertEquals("l2e", parsed.classOf("app.zhendong.reamicro.ui.storage.c115.Y115NetDiskAccountScreenKt"))
        assertEquals("m3e", parsed.classOf("kotlin.ResultKt"))
        assertEquals("g", parsed.methodOf("kotlin.ResultKt", "throwOnFailure"))
        assertEquals("gd1", parsed.methodOwners["okio.Paths#get"])
        assertEquals("B0", parsed.methodOf("okio.Paths", "get"))
        assertEquals("zub", parsed.classOf("app.zhendong.reamicro.arch.fs.UserStorage"))
        assertEquals("b", parsed.methodOf("app.zhendong.reamicro.arch.fs.UserStorage", "userBooksDir"))
        assertEquals("c", parsed.fieldOf("app.zhendong.reamicro.arch.EpubFileManager", "INSTANCE"))
        assertEquals("f4e", parsed.classOf("androidx.lifecycle.ViewModelKt"))
        assertEquals("d", parsed.methodOf("androidx.lifecycle.ViewModelKt", "getViewModelScope"))
        assertEquals("jtd", parsed.methodOwners["kotlinx.coroutines.BuildersKt#withContext"])
        assertEquals(
            "tpc",
            parsed.methodOwners["app.zhendong.reamicro.arch.icons.colored.AndroidOsKt#getAndroidOs"],
        )
        assertEquals(
            "b",
            parsed.methodOf("app.zhendong.reamicro.arch.icons.colored.AndroidOsKt", "getAndroidOs"),
        )
        assertEquals("b51", parsed.classOf("app.zhendong.reamicro.ui.reader.components.ScrollPagerKt"))
        assertEquals("ygd", parsed.classOf("app.zhendong.reamicro.ui.reader.components.StaticPagerKt"))
    }

    @Test
    fun `b4 continuation bridge references its own host protocol`() {
        val parsed = profile()
        assertEquals("dy1", parsed.classOf("kotlin.coroutines.Continuation"))
        assertEquals("nz1", parsed.classOf("kotlin.coroutines.CoroutineContext"))
        assertEquals("bqa", parsed.classOf("kotlin.coroutines.jvm.internal.SuspendLambda"))
        assertEquals("kp3", parsed.classOf("kotlin.jvm.functions.Function2"))
        val dex = File(assets(), "host-bridges/2.3.3-b4.dex").readBytes()
        assertTrue(dex.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)))
        val strings = dex.toString(Charsets.ISO_8859_1)
        assertTrue(strings.contains("Lbqa;"))
        assertTrue(strings.contains("Ldy1;"))
        assertFalse(strings.contains("Lupa;"))
    }

    @Test
    fun `compose callbacks include renamed members of classes whose names did not change`() {
        val parsed = profile()
        assertEquals("d", parsed.methodOf("kotlin.jvm.functions.Function0", "invoke"))
        assertEquals("C", parsed.methodOf("kotlin.jvm.functions.Function2", "invoke"))
        assertEquals("r", parsed.methodOf("kotlin.jvm.functions.Function3", "invoke"))
        assertEquals("io1", parsed.classOf("ReaMicroHost.ComposableLambda"))
        assertEquals("p39", parsed.classOf("app.zhendong.reamicro.Route\$About"))
        assertEquals("y5e", parsed.classOf("ReaMicroHost.LazyDsl"))
    }

    @Test
    fun `home cloud tap follows the verified home event and receiver contract`() {
        val parsed = profile()
        assertEquals("qud", parsed.classOf("ReaMicroHost.HomeSearchFooter"))
        assertEquals("j5", parsed.classOf("ReaMicroHost.HomeSearchList"))
        assertEquals("uc4", parsed.classOf("ReaMicroHost.HomeCloudTap"))
        assertEquals("zc4", parsed.classOf("ReaMicroHost.HomeEvent"))
        assertEquals("z56", parsed.classOf("app.zhendong.reamicro.arch.IntentReceiver"))
        assertEquals("rd1", parsed.classOf("app.zhendong.reamicro.data.storage.CloudBook"))
        assertEquals("d", parsed.fieldOf("app.zhendong.reamicro.data.storage.CloudBook", "url"))
        assertEquals("g", parsed.fieldOf("app.zhendong.reamicro.data.storage.CloudBook", "path"))
    }

    @Test
    fun `reader ui status aliases remain explicit without stealing reverse identity`() {
        val parsed = profile()
        val status = "app.zhendong.reamicro.ui.reader.UiStatus"
        assertEquals("zob", parsed.classOf(status))
        mapOf("Reader" to "wob", "Menu" to "vob", "Catalog" to "rob").forEach { (name, actual) ->
            val logical = "$status\$$name"
            assertEquals(actual, parsed.classOf(logical))
            assertEquals("b", parsed.fieldOf(logical, "INSTANCE"))
            assertTrue(logical in parsed.aliasesOf(actual))
            assertEquals(null, parsed.logicalClassOf(actual))
        }
    }

    @Test
    fun `obsolete external manual edit is disabled explicitly only on b4`() {
        assertTrue(ExternalFeatureCompatibility.disabledReason("manual-edit", emptySet(), "2.3.3-b4") != null)
        assertTrue(ExternalFeatureCompatibility.disabledReason("legacy", setOf("manual_edit"), "2.3.3-b4") != null)
        assertEquals(null, ExternalFeatureCompatibility.disabledReason("manual-edit", emptySet(), "2.3.3-b2"))
        assertEquals(null, ExternalFeatureCompatibility.disabledReason("other", emptySet(), "2.3.3-b4"))
    }

    @Test
    fun `repository dependencies follow constructor types rather than shifted source fields`() {
        val parsed = profile()
        val read = "app.zhendong.reamicro.repository.ReadRepository"
        val dependencies = mapOf(
            "database" to "g",
            "bookDao" to "h",
            "bookItemRefDao" to "i",
            "bookChapterDao" to "j",
            "bookReadingDao" to "k",
            "readingSessionDao" to "l",
            "markDao" to "m",
            "api" to "n",
            "bookRepository" to "o",
            "userStorage" to "p",
        )
        dependencies.forEach { (logical, actual) ->
            assertEquals(logical, actual, parsed.fieldOf(read, logical))
        }
        val cloud = "app.zhendong.reamicro.repository.storage.CloudStorageRepository"
        assertEquals("j", parsed.fieldOf(cloud, "yun115"))
        assertEquals("k", parsed.fieldOf(cloud, "workerManager"))
        val bookshelf = "app.zhendong.reamicro.repository.BookshelfRepository"
        assertEquals("k", parsed.fieldOf(bookshelf, "session"))
        assertEquals("l", parsed.fieldOf(bookshelf, "userStorage"))
    }
}
