package com.reamicro.fix.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleLogPolicyTest {
    @Test
    fun `concise mode emits only errors`() {
        assertFalse(shouldEmitModuleLog(true, ModuleLogLevel.INFO))
        assertFalse(shouldEmitModuleLog(true, ModuleLogLevel.WARN))
        assertTrue(shouldEmitModuleLog(true, ModuleLogLevel.ERROR))
    }

    @Test
    fun `full mode emits every level`() {
        ModuleLogLevel.entries.forEach { level ->
            assertTrue(shouldEmitModuleLog(false, level))
        }
    }

    @Test
    fun `legacy classifier keeps failures and exceptions as errors`() {
        assertEquals(ModuleLogLevel.ERROR, legacyModuleLogLevel("download failed: HTTP 503"))
        assertEquals(ModuleLogLevel.ERROR, legacyModuleLogLevel("hook exception: broken"))
        assertEquals(ModuleLogLevel.ERROR, legacyModuleLogLevel("system tts onError utterance=3"))
    }

    @Test
    fun `legacy classifier treats progress and success as info`() {
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("chapter downloaded 120/579"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("batch returned=30 range=91-120"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("failed chapters count=0"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("retried=0 failed=0 style=false"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("primary failed, fallback to sqlite"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("scroll crash guard hook installed count=1"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("scroll crash fallback switched flip_style to translate"))
        assertEquals(ModuleLogLevel.INFO, legacyModuleLogLevel("scroll crash pending cleared: reader stable"))
    }
}
