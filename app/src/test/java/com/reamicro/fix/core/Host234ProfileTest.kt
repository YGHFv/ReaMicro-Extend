package com.reamicro.fix.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Host234ProfileTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
    private val profile = HostProfile.parse(assets.resolve("host-profiles/2.3.4-b1.profile").readText(Charsets.UTF_8))

    @Test
    fun fingerprintDistinguishesHostsWithTheSameVersionCode() {
        assertEquals("2.3.4-b1", profile.fingerprint.id)
        assertEquals(45_272_365L, profile.fingerprint.sizeHint)
        val classes = profile.fingerprint.requiredClasses.toSet()
        assertTrue(HostProfile.matches(profile, HostProfile.HOST_PACKAGE, 45_272_365L) { it in classes })
        assertFalse(HostProfile.matches(profile, HostProfile.HOST_PACKAGE, 44_894_945L) { it in classes - "u09" })
        assertFalse(HostProfile.matches(profile, HostProfile.HOST_PACKAGE, 45_272_365L) { it in classes - "x83" })
        assertEquals("cr8", HostProfile.parse(assets.resolve("host-profiles/2.3.3.profile").readText(Charsets.UTF_8))
            .classOf("app.zhendong.reamicro.ui.reader.ReaderViewModel"))
    }

    @Test
    fun splitCfiBuilderDoesNotResolveToTheHttpRequest() {
        val builder = "org.epub.html.EpubCFI\$Builder"
        assertEquals("o8d", profile.classOf(builder))
        assertEquals("cx9", profile.classOf("ReaMicroHost.HttpRequest"))
        assertEquals("h", profile.methodOf(builder, "build"))
        assertEquals("l", profile.methodOf(builder, "newBuilder"))
        listOf("spine", "itemref", "body", "steps", "offset", "sideBias").forEachIndexed { index, field ->
            assertEquals(('a'.code + index).toChar().toString(), profile.fieldOf(builder, field))
        }
    }

    @Test
    fun svgAndFontClosuresUseTheirVerifiedOwners() {
        assertEquals("ri4", profile.classOf("ReaMicroHost.HideLibrary"))
        assertEquals("a4", profile.classOf("ReaMicroHost.SpacingAlignment"))
        assertEquals("adc", profile.classOf("androidx.compose.ui.graphics.ColorKt"))
        assertEquals("ou9", profile.classOf("ReaMicroHost.SvgDocument"))
        assertEquals("uu9", profile.classOf("ReaMicroHost.SvgBox"))
        assertEquals("b", profile.fieldOf("ReaMicroHost.SvgDecoded", "document"))
        assertEquals("wq6", profile.classOf("ReaMicroHost.BuiltInFontItems"))
        assertEquals("yxd", profile.methodOwners["ReaMicroHost.BuiltInFontRow#render"])
        assertEquals("c", profile.fieldOf("org.epub.ui.BodyKt", "LocalEpubMarks"))
    }

    @Test
    fun legacyManualEditCannotBypassTheNewHostCompatibilityGate() {
        assertNotNull(ExternalFeatureCompatibility.disabledReason(
            "manual-edit", setOf("manual_edit", "manual_edit_host_233"), "2.3.4-b1",
        ))
        assertNotNull(ExternalFeatureCompatibility.disabledReason(
            "renamed-manual-source", setOf("manual_edit"), "2.3.4-b1",
        ))
    }

    @Test
    fun nativeBridgesAreBuiltForTheNewHostOnly() {
        assertEquals(HostNativeAbi.Http(5, "p", "g", "h"), HostNativeAbi.http("2.3.4-b1"))
        assertTrue(HostNativeUiAbi.supports("2.3.4-b1"))
        assertTrue(HostNativeAbi.hasNativePaging("2.3.4-b1"))
        assertEquals(2.toByte(), HostNativeAbi.singleFlowBranch("2.3.4-b1"))
        assertEquals(11.toByte(), HostNativeAbi.settingsListBranch("2.3.4-b1"))
        assertEquals(15.toByte(), HostNativeAbi.settingsListBranch("2.3.3"))
        val http = assets.resolve("host-bridges/http-2.3.4-b1.dex").readBytes().toString(Charsets.ISO_8859_1)
        listOf("Lj99;", "Ltj6;", "Lbw0;").forEach { assertTrue(http.contains(it)) }
        assertFalse(http.contains("Ljz8;"))
        assertNotNull(profile.classOf("kotlin.coroutines.jvm.internal.SuspendLambda"))
        assertTrue(assets.resolve("host-bridges/2.3.4-b1.dex").length() > 0L)
    }
}
