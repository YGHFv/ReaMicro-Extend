package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定 OPF、选文气泡和个人页卡片的 B4 参数边界，避免适配再丢失原生行为。 */
class HostB4RegressionContractTest {
    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun source(name: String): String = main().resolve("java/com/reamicro/fix/hook/$name")
        .readText(Charsets.UTF_8)

    private fun profile(): HostProfile.Parsed = HostProfile.parse(
        main().resolve("assets/host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8), "b4 regressions",
    )

    @Test
    fun `b4 opf uses the verified relocated static parser`() {
        val parsed = profile()
        assertEquals("trd", parsed.methodOwners["org.epub.structure.opf.Opf#obtain"])
        assertEquals("k", parsed.methodOf("org.epub.structure.opf.Opf", "obtain"))
        val opf = source("WebDavDriveHook.OnlineDownload.kt")
            .substringAfter("internal fun WebDavDriveHook.obtainOnlineCompletionOpf(bookDir: Any)")
            .substringBefore("val companion =")
        assertTrue(opf.contains("classLoader.hostStaticMethod(OPF_CLASS, \"obtain\", pathClass)"))
        assertTrue(opf.contains("opfClass.isAssignableFrom(obtain.returnType)"))
        assertFalse(opf.contains("findStaticMethodOwner"))
    }

    @Test
    fun `selection replaces only bubble content and reserves the arrow side`() {
        val parsed = profile()
        assertEquals("ce0", parsed.classOf("ReaMicroHost.SelectionMenuFloatingContent"))
        assertEquals("ae0", parsed.classOf("ReaMicroHost.SelectionMenuInlineContent"))
        val hooks = source("ReaderHook.HostHooks.kt")
            .substringAfter("internal fun ReaderHook.hookNativeSelectionMenuContent")
            .substringBefore("val contentMethods =")
        assertTrue(hooks.contains("content.getDeclaredField(\"b\").getBoolean(closure)"))
        assertTrue(hooks.contains("maxPerRow, arrowOnTop)"))
        assertFalse(hooks.contains("param.args?.getOrNull(5)"))
        val menu = source("ReaderHook.Selection.kt")
            .substringAfter("private fun ReaderHook.renderB4WrappedSelectionMenu(")
            .substringBefore("private fun ReaderHook.renderCompactWrappedSelectionMenu(")
        assertTrue(menu.contains("bubbleArrowOnTop == true) 10f else 0f"))
        assertTrue(menu.contains("bubbleArrowOnTop == false) 10f else 0f"))
        assertTrue(menu.contains("val contentModifier = nativeUi.padding("))
        assertTrue(menu.contains("nativeUi.width(nativeUi.modifier(), nativeUi.dp(contentWidth))"))
        assertTrue(menu.contains("val rows = actions.chunked(maxPerRow.coerceAtLeast(1))"))
        assertTrue(menu.contains("nativeUi.row("))
        assertTrue(menu.contains("nativeUi.dp(maxPerRow.coerceAtLeast(1) * cellWidth)"))
        assertTrue(menu.contains("nativeUi.column("))
        assertFalse(menu.contains("FLOW_ROW_METHOD"))
        assertFalse(menu.contains("nativeUi.padding(nativeUi.modifier(), 8f"))
    }

    @Test
    fun `b4 wrapped selection enables native measured width without replacing placement`() {
        val hooks = source("ReaderHook.HostHooks.kt")
            .substringAfter("internal fun ReaderHook.hookNativeSelectionMenu()")
            .substringBefore("internal fun ReaderHook.hookNativeSelectionMenuContent")
        assertTrue(hooks.contains("method.parameterTypes[3] == Boolean::class.javaPrimitiveType"))
        assertTrue(hooks.contains("settingsProvider().canUseCompactReaderSelectionMenu ||"))
        assertTrue(hooks.contains("HostSymbols.abiProfileId == \"2.3.3-b4\" && selectionMenuMaxItemsPerRow(next.size) != null"))
        assertTrue(hooks.contains("param.args[3] = true"))
        assertTrue(hooks.contains("and 0x1C00.inv()"))
        assertFalse(hooks.contains("param.args[4] ="))
    }

    @Test
    fun `b4 normal selection tightens cells but retains legacy and compact metrics`() {
        val menu = source("ReaderHook.Selection.kt")
            .substringAfter("private fun ReaderHook.selectionMenuCellWidthDp")
            .substringBefore("private fun ReaderHook.selectionMenuCellModifier")
        assertTrue(menu.contains("HostSymbols.abiProfileId == \"2.3.3-b4\" && !settingsProvider().canUseCompactReaderSelectionMenu"))
        assertTrue(menu.contains("(maxTitleWidth + 18).coerceIn(44, 100)"))
        assertTrue(menu.contains("(maxTitleWidth + 42).coerceIn(72, 112)"))
    }

    @Test
    fun `discover category row never uses unsupported negative padding`() {
        val discover = source("ReaMicroSettingsHook.Discover.kt")
            .substringAfter("private fun ReaMicroSettingsHook.renderDiscoverKindRow")
            .substringBefore("private fun ReaMicroSettingsHook.renderDiscoverKindExpandButton")
        assertTrue(discover.contains("paddingSides(discoverFillMaxWidth(modifierInstance()), 20, 0, 8, 0)"))
        assertFalse(discover.contains(", -4)"))
    }

    @Test
    fun `b4 edit icon uses the relocated zero argument outlined edit getter`() {
        val parsed = profile()
        val owner = "androidx.compose.material.icons.outlined.EditKt"
        assertEquals("yfd", parsed.methodOwners["$owner#getEdit"])
        assertEquals("d", parsed.methodOf(owner, "getEdit"))
        val icon = source("ReaderHook.Support.kt")
            .substringAfter("internal fun ReaderHook.editImageVector()")
            .substringBefore("internal fun ReaderHook.nativeFunction0")
        assertTrue(icon.contains("HostSymbols.abiProfileId == \"2.3.3-b4\""))
        assertTrue(icon.contains("classLoader.hostStaticMethod(EDIT_ICON_CLASS, \"getEdit\")"))
        assertTrue(icon.contains("typeMatches(method.returnType, HostClasses.Compose.IMAGE_VECTOR)"))
        assertTrue(icon.contains("method.invoke(null)"))
        assertTrue(icon.contains("invoke(null, outlined)"))
    }

    @Test
    fun `b4 background restores the existing scoped divider hook before returning`() {
        val parsed = profile()
        assertEquals("p7d", parsed.classOf("app.zhendong.reamicro.arch.components.DividerKt"))
        assertEquals("c", parsed.methodOf("app.zhendong.reamicro.arch.components.DividerKt", "SimpleDivider-iJQMabo"))
        val source = source("ProfileBackgroundHook.kt")
        val b4 = source.substringAfter("private fun installB4ProfileBackground()")
            .substringBefore("private fun installProfileScreenHook()")
        assertTrue(b4.contains("installProfileDividerHook()"))
        val divider = source.substringAfter("private fun installProfileDividerHook()")
            .substringBefore("private fun installProfileScreenLambdaHook()")
        assertTrue(divider.contains("m.hostNameMatches(SIMPLE_DIVIDER_METHOD) && m.parameterTypes.size == 5"))
        assertTrue(divider.contains("if (inProfileLambda.get() != true) return"))
        assertTrue(divider.contains("if (!settingsProvider().canShowProfileBackground) return"))
        assertTrue(divider.contains("param.result = null"))
        assertTrue(divider.contains("XposedBridge.deoptimize(target)"))
    }

    @Test
    fun `profile cards select native glass and invalidate their actual changed groups`() {
        val parsed = profile()
        assertEquals("lx7", parsed.classOf("ReaMicroHost.ProfileContent"))
        assertEquals("xbd", parsed.classOf("ReaMicroHost.ProfilePrivilegeCard"))
        assertEquals("et0", parsed.classOf("ReaMicroHost.ShaderBrush"))
        val hook = source("ProfileBackgroundHook.kt")
            .substringAfter("private fun installB4ProfileBackground()")
            .substringBefore("private fun installProfileScreenHook()")
        assertTrue(hook.contains("community to (2 to 6)"))
        assertTrue(hook.contains("privilege to (9 to 27)"))
        assertTrue(hook.contains("param.args[index] = true"))
        assertTrue(hook.contains("and (7 shl shift).inv()"))
        assertTrue(hook.contains("canUseProfileImageBackground(settingsProvider())"))
    }
}
