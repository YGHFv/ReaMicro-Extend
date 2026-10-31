package com.reamicro.fix.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTypographyPolicyTest {
    @Test
    fun absentAndInvalidValuesUseNativeDefaults() {
        listOf(null, 0f, -0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(18f, ReaderTypographyPolicy.textSize(it), 0f)
            assertEquals(1.5f, ReaderTypographyPolicy.lineHeight(it), 0f)
        }
    }

    @Test
    fun validCustomTypographyIsNotClampedOrRounded() {
        listOf(0.5f, 1.3f, 1.4f, 1.9f, 2.8f, 24f, 48f).forEach {
            assertEquals(it.toRawBits(), ReaderTypographyPolicy.textSize(it).toRawBits())
            assertEquals(it.toRawBits(), ReaderTypographyPolicy.lineHeight(it).toRawBits())
        }
    }

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val account by lazy {
        main.resolve("java/com/reamicro/fix/hook/AccountCompletionController.kt").readText(Charsets.UTF_8)
    }

    @Test
    fun switchingAccountsPreservesDevicePreferencesButExplicitRestoreCanRestoreThem() {
        val switch = account.substringAfter("fun switchToAccount(").substringBefore("fun persistCurrentAccountSnapshot")
        assertTrue(switch.contains("restoreAccount(activity, normalizedTarget)"))
        assertFalse(switch.contains("restoreDevicePreferences = true"))
        assertTrue(account.contains("restoreDevicePreferences: Boolean = false"))
        assertTrue(account.contains("if (restoreDevicePreferences) restoreFullSessionPreferences"))
        assertTrue(account.contains("restoreAccount(activity, normalized, restoreDevicePreferences = true)"))
    }

    @Test
    fun snapshotImportAndCaptureCannotReintroduceZeroTypography() {
        val restore = account.substringAfter("private fun restoreFullSessionPreferences(")
            .substringBefore("private fun captureSharedPrefsSnapshot(")
        assertTrue(restore.contains("ReaderTypographyPolicy.textSize(snapshot.textSize)"))
        assertTrue(restore.contains("ReaderTypographyPolicy.lineHeight(snapshot.lineHeight)"))
        assertFalse(restore.contains("?.toFloat() ?: 0f"))
    }

    @Test
    fun runtimeGuardRunsBeforeLayoutAndDoesNotWritePreferencesOrCss() {
        val hook = main.resolve("java/com/reamicro/fix/hook/ReaderHook.Typography.kt").readText(Charsets.UTF_8)
        assertTrue(hook.contains("HostSymbols.profileId != \"2.3.3\""))
        assertTrue(hook.contains("override fun beforeHookedMethod"))
        assertTrue(hook.contains("XposedBridge.deoptimize(caller)"))
        assertFalse(hook.contains("updatePreference("))
        assertFalse(hook.contains("getSharedPreferences("))
    }
}
