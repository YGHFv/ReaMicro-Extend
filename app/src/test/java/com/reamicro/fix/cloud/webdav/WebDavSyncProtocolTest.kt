package com.reamicro.fix.cloud.webdav

import com.reamicro.fix.settings.ModuleSettingsSnapshot
import org.junit.Assert.*
import org.junit.Test

class WebDavSyncProtocolTest {
    @Test fun syncIsIndependentAndDisabledByDefault() {
        assertFalse(ModuleSettingsSnapshot().canRunWebDavSync)
        assertTrue(ModuleSettingsSnapshot(cloudSyncExtensionEnabled = true, cloudWebDavEnabled = false).canRunWebDavSync)
        assertFalse(ModuleSettingsSnapshot(moduleEnabled = false, cloudSyncExtensionEnabled = true).canRunWebDavSync)
    }

    @Test fun preservesBaseAndEncodesSegmentsOnce() {
        assertEquals("https://example.com/dav/%E4%B9%A6%E6%9E%B6/a%20b%25%23.bin",
            WebDavSyncProtocol.objectUrl("https://example.com/dav/", "书架/a b%#.bin"))
        assertEquals("http://127.0.0.1:18783/dav", WebDavSyncProtocol.baseUrl(" http://127.0.0.1:18783/dav/ "))
    }

    @Test fun rejectsUnsafePathsAndEmbeddedCredentials() {
        listOf("ftp://example.com/dav", "https://u:p@example.com/dav", "https://example.com/dav?a=b",
            "https://example.com/dav#x", "https://example.com/../dav").forEach {
            assertTrue(runCatching { WebDavSyncProtocol.baseUrl(it) }.isFailure)
        }
        listOf("../a", "/a", "a//b", "a/./b", "a\\b").forEach {
            assertTrue(runCatching { WebDavSyncProtocol.objectUrl("https://example.com", it) }.isFailure)
        }
    }

    @Test fun preservesNativeConditionalWriteContract() {
        assertEquals(emptyMap<String, String>(), WebDavSyncProtocol.condition(null))
        assertEquals(mapOf("If-None-Match" to "*"), WebDavSyncProtocol.condition("*"))
        assertEquals(mapOf("If-Match" to "\"abc\""), WebDavSyncProtocol.condition("\"abc\""))
        listOf(null, "", "abc", "W/\"abc\"", "\"a\r\nb\"").forEach {
            assertTrue(runCatching { WebDavSyncProtocol.requireStrongEtag(it) }.isFailure)
        }
    }
}
