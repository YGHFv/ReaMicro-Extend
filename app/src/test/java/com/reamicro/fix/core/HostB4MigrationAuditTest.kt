package com.reamicro.fix.core

import com.reamicro.fix.xposed.XposedHelpers
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.hook.HostCrashReportBlocker
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定本轮双源确认的内联 getter、字体开关和崩溃 SDK 入口迁移。 */
class HostB4MigrationAuditTest {
    class FamilyName(@JvmField val b: String)
    class InitFixture {
        companion object {
            @JvmStatic fun init(app: Any, config: Any) = Unit
        }
    }

    private fun main(): File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun profile(): HostProfile.Parsed = HostProfile.parse(
        main().resolve("assets/host-profiles/2.3.3-b4.profile").readText(Charsets.UTF_8),
    )

    @After fun reset() = HostSymbols.reset()

    @Test fun `family names use the inlined field through the existing accessor bridge`() {
        val family = "org.epub.css.property.FontFamily\$FamilyName"
        val parsed = profile()
        assertEquals("bk3", parsed.classOf(family))
        assertEquals("b", parsed.fieldOf(family, "name"))
        val fixture = HostProfile.parse(
            "profile=fixture\nrequiredClass=fixture.Marker\n" +
                "typeAlias=$family=${FamilyName::class.java.name}\nfield=$family#name=b\n",
        )
        HostSymbols::class.java.getDeclaredField("active").apply { isAccessible = true }.set(null, fixture)
        assertEquals("宋体", XposedHelpers.callMethod(FamilyName("宋体"), "getName"))
        assertEquals("楷体", HostAccessor.bind(FamilyName::class.java, "getName")?.get(FamilyName("楷体")))
    }

    @Test fun `font flags match old withName branches instead of target short name order`() {
        val parsed = profile()
        assertEquals("h", parsed.fieldOf("org.epub.FontProvider", "embeddedFonts"))
        assertEquals("i", parsed.fieldOf("org.epub.FontProvider", "buildInFonts"))
        val source = main().resolve("java/com/reamicro/fix/hook/ReaderFontCompletionHook.kt")
            .readText(Charsets.UTF_8)
        assertTrue(source.contains("fontProviderBoolean(\"embeddedFonts\", true)"))
        assertTrue(source.contains("fontProviderBoolean(\"buildInFonts\", true)"))
    }

    @Test fun `sentry init guard binds only the verified narrowed signature and preserves the switch`() {
        val parsed = profile()
        val owner = "io.sentry.android.core.SentryAndroid"
        assertEquals("io.sentry.android.core.h1", parsed.methodOwners["$owner#init"])
        assertEquals("b", parsed.methodOf(owner, "init"))
        assertEquals("uu", parsed.classOf("ReaMicroHost.SentryOptionsConfig"))
        val source = main().resolve("java/com/reamicro/fix/hook/HostCrashReportBlocker.kt")
            .readText(Charsets.UTF_8)
        val b4 = source.substringAfter("private fun installB4InitGuard(")
            .substringBefore("private fun installSettingsBootstrap(")
        assertTrue(b4.contains("installSettingsBootstrap(packageName)"))
        assertTrue(b4.contains("hostStaticMethod(\"io.sentry.android.core.SentryAndroid\", \"init\", app, config)"))
        assertTrue(b4.contains("replacement(method, ResultKind.VOID, ::isBlockingEnabled)"))
        assertTrue(b4.contains("XposedBridge.deoptimize(app.getDeclaredMethod(\"onCreate\"))"))
        assertFalse(b4.contains("TRANSPORT_SUCCESS"))
    }

    @Test fun `upload permission keeps sdk init available and blocking only skips init`() {
        val method = InitFixture::class.java.getDeclaredMethod("init", Any::class.java, Any::class.java)
        fun blocked(value: Boolean): XC_MethodHook.MethodHookParam {
            val param = XC_MethodHook.MethodHookParam(method, null, arrayOf(Any(), Any()))
            HostCrashReportBlocker.replacement(method, HostCrashReportBlocker.ResultKind.VOID) { value }
                .callBeforeHookedMethod(param)
            return param
        }
        assertFalse(blocked(false).isReturnEarly)
        assertTrue(blocked(true).isReturnEarly)
        assertEquals(null, blocked(true).result)
    }

    @Test fun `on demand refresh resolves split job and dispatcher owners without changing the lease`() {
        val parsed = profile()
        assertEquals("s2e", parsed.methodOwners["kotlinx.coroutines.JobKt#getJob"])
        assertEquals("i", parsed.methodOf("kotlinx.coroutines.JobKt", "getJob"))
        assertEquals("g", parsed.methodOf("kotlinx.coroutines.Job", "isActive"))
        assertEquals("jtd", parsed.methodOwners["kotlinx.coroutines.BuildersKt#withContext"])
        assertEquals("q", parsed.methodOf("kotlinx.coroutines.BuildersKt", "withContext"))
        val source = main().resolve("java/com/reamicro/fix/hook/ReaderHook.RefreshRuntime.kt")
            .readText(Charsets.UTF_8)
        assertFalse(source.contains("classLoader.hostClass(\"kotlinx.coroutines.JobKt\")"))
        assertFalse(source.contains("classLoader.hostClass(\"kotlinx.coroutines.BuildersKt\")"))
        assertTrue(source.contains("classLoader.hostStaticMethod("))
        assertTrue(source.contains("check(XposedHelpers.callMethod(job, \"isActive\") == true)"))
        assertTrue(source.contains("invokeSelectionHostSuspend(parentContext = job)"))
        assertTrue(source.contains("requireRefreshCurrent(current)"))
    }
}
