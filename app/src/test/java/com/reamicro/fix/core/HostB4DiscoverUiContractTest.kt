package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 防止版本适配再次把完整发现页退化成简化 View，或丢失原生双栏入口。 */
class HostB4DiscoverUiContractTest {
    private fun main(): File = listOf(File("src/main"), File("app/src/main"))
        .first { it.isDirectory }

    private fun source(path: String): String = main().resolve("java/com/reamicro/fix/$path")
        .readText(Charsets.UTF_8)

    @Test
    fun `b4 discover retains native header actions and shared book layouts`() {
        val page = source("hook/ReaMicroSettingsHook.Discover.kt")
        assertFalse(page.contains("renderB4DiscoverContent(innerPaddings, composer)"))
        val header = page.substringAfter("internal fun ReaMicroSettingsHook.renderDiscoverTopBar")
            .substringBefore("private fun ReaMicroSettingsHook.renderDiscoverTopBarAction")
        assertTrue(header.contains("renderDiscoverConfigButton"))
        assertTrue(header.contains("renderDiscoverLayoutToggle"))
        assertTrue(header.contains("renderDiscoverKindRow"))
        assertTrue(page.contains("b2Ui.lazyRow(mod, list, inner)"))
        assertTrue(page.contains("HostAndroidViews(classLoader).render"))
        assertTrue(page.contains("discoverCoreTagLine(book, source)"))
        assertTrue(page.contains("DiscoverShelfStyle.COVER_RATIO"))
        assertTrue(page.contains("listOf(authorLine, coreLine)"))
        assertFalse(page.contains("listOf(latestLine, coreLine)"))
    }

    @Test
    fun `discover entry keeps arrows divider icon and matching weighted fillers`() {
        val entry = source("hook/DiscoverButtonRenderer.kt")
        val row = entry.substringAfter("internal fun ReaMicroSettingsHook.renderCommunityRow")
            .substringBefore("private fun ReaMicroSettingsHook.renderCellArrow")
        assertFalse(row.contains("B4DiscoverCell"))
        assertTrue(row.contains("renderVerticalDivider(composer)"))
        assertTrue(row.contains("renderDiscoverIcon(icon"))
        assertTrue(row.contains("CELL_LEFT_ARROW_END_PADDING_DP"))
        assertTrue(row.contains("renderDiscoverFiller(rowScope"))
        assertTrue(row.contains("CELL_RIGHT_ARROW_END_PADDING_DP"))
        assertTrue(entry.contains("b2Ui.weight(rowScope, modifierInstance())"))
        assertTrue(entry.contains("b2Ui.spacer(b2Ui.height(padded"))
    }

    @Test
    fun `retargeted arrows and community divider retain the native two column geometry`() {
        val entry = source("hook/DiscoverButtonRenderer.kt")
        assertTrue(entry.contains("cls(\"ReaMicroHost.NavigateNextIcon\").hostAbiMethod(\"i\")"))
        assertFalse(entry.contains("cls(\"ReaMicroHost.NavigateNextIcon\").getDeclaredMethod(\"i\")"))
        val divider = source("hook/DiscoverCardHook.kt")
            .substringAfter("private fun hookCommunityDivider()")
            .substringBefore("private fun composerOf")
        assertTrue(divider.contains("HostSymbols.profileId != \"2.3.3\""))
        assertTrue(divider.contains("!inCommunity() || communityInjected.get() != true"))
        assertTrue(divider.contains("param.args[0] = hook.modifierInstance()"))
        assertTrue(divider.contains("XposedBridge.deoptimize(target)"))
        val details = source("hook/FileEditCompletionHook.kt")
        assertTrue(details.contains("cls(\"ReaMicroHost.NavigateNextIcon\").hostAbiMethod(\"i\")"))
        assertTrue(details.contains("cls(\"ReaMicroHost.AliyunIcon\").hostAbiMethod(\"d\")"))
    }

    @Test
    fun `highlight card wraps the b4 entry with symmetric spacing`() {
        val entry = source("hook/ReaMicroSettingsHook.Highlight.kt")
            .substringAfter("internal fun ReaMicroSettingsHook.renderReaderHighlightScreenEntryCard")
            .substringBefore("internal fun ReaMicroSettingsHook.addReaderHighlightScreenEntryLazyItem")
        assertTrue(entry.contains("if (b4) udp(12) else 0f"))
        assertTrue(entry.contains("udp(12),"))
        assertTrue(entry.contains("readerHighlightColumn("))
        assertFalse(entry.contains("renderHostActionCard(rows, composer)"))
    }

    @Test
    fun `b4 icon and layout aliases bind the verified dex owners`() {
        val profile = HostProfile.parse(main().resolve("assets/host-profiles/2.3.3-b4.profile")
            .readText(Charsets.UTF_8), "b4 discover UI")
        assertEquals("qfd", profile.classOf("ReaMicroHost.Spacer"))
        assertEquals("mod", profile.classOf("ReaMicroHost.NavigateNextIcon"))
        assertEquals("pvb", profile.classOf("ReaMicroHost.AspectRatio"))
        assertEquals("ie7", profile.classOf("ReaMicroHost.PaddingValues"))
        assertEquals("d", profile.methodOf("ReaMicroHost.PaddingValues", "calculateTopPadding"))
        assertEquals("a", profile.methodOf("ReaMicroHost.PaddingValues", "calculateBottomPadding"))
        assertEquals("km4", profile.classOf("ReaMicroHost.VectorBuilder"))
        assertEquals("dr1", profile.classOf("ReaMicroHost.VectorPathBuilder"))
        assertEquals("b", HostNativeUiAbi.method("2.3.3-b4", "LazyDsl", "b"))
        assertEquals("r", profile.methodOf("kotlin.jvm.functions.Function3", "invoke"))
        val ui = source("core/B2NativeUi.kt")
        assertTrue(ui.contains("invokeLayoutContent(content, field(\"ColumnScope\", \"a\"), composer)"))
        assertTrue(ui.contains("loader.hostClass(\"kotlin.jvm.functions.Function3\").hostMethod("))
        assertFalse(ui.contains("getDeclaredMethod(\"b\", type(\"Modifier\")"))
        val vector = source("hook/discover/B4DiscoverVector.kt")
        assertTrue(vector.contains("evenOddFill"))
        assertTrue(vector.contains("node.type == 'A'"))
        assertTrue(vector.contains("check(values[2] == 0f"))
    }

    @Test
    fun `module search replaces only the reader menu theme control`() {
        val entry = source("hook/B4ReaderSearchEntryBridge.kt")
        assertFalse(entry.contains("param.args[5] ="))
        assertFalse(entry.contains("READER_CATALOG_CLASS"))
        assertTrue(entry.contains("reader.bindReaderThemeClick(original)"))
        assertTrue(entry.contains("getByte(closure) != 3.toByte()"))
        val binding = source("hook/ReaderHook.Support.kt")
            .substringAfter("internal fun ReaderHook.isReaderBottomBarDarkLightToggleClick")
            .substringBefore("internal fun ReaderHook.isReaderBottomBarBackClick")
        assertTrue(binding.contains("getByte(onClick) == 2.toByte()"))
        assertTrue(binding.contains("currentSessionRef?.get()"))
    }

    @Test
    fun `sync metadata remains on the right and export card retains its outer inset`() {
        val details = source("hook/FileEditCompletionHook.kt")
        val row = details.substringAfter("private fun renderB4SyncActionRow(")
            .substringBefore("private fun renderNativeDetailsCard")
        assertTrue(row.contains("nativeUi.weight(scope, nativeUi.modifier())"))
        assertTrue(row.contains("nativeText(value"))
        assertTrue(row.contains("storage?.takeIf"))
        assertTrue(row.contains("storageIcon?.let"))
        val export = source("hook/LocalExportHook.kt")
        assertTrue(export.contains("ui.dp(16), 0f, ui.dp(16), ui.dp(16)"))
        assertTrue(export.contains("ui.column("))
    }

    @Test
    fun `detail edit and sync arrows share the native publisher icon defaults`() {
        val details = source("hook/FileEditCompletionHook.kt")
        val row = details.substringAfter("private fun renderB4SyncActionRow(")
            .substringBefore("private fun renderNativeDetailsCard")
        assertTrue(row.contains("renderB4DetailsArrow(scheme, inner)"))
        assertTrue(row.contains("nativeUi.dp(16), nativeUi.dp(15), nativeUi.dp(16), nativeUi.dp(15)"))
        assertTrue(row.contains("null, arrow, null, null,"))
        assertTrue(row.contains("nativeLong(scheme, \"getSurfaceContainerHighest\"), composer, 48, 4"))
        assertFalse(row.contains("nativeUi.dp(20)"))
        assertTrue(details.contains("secondaryValue != null || nativeTrailingGlyph == \"\\u276F\""))
    }
}
