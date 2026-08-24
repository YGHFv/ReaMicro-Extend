package com.reamicro.fix.cloud.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiContentPackageTest {
    @Test
    fun `compares semantic package versions`() {
        assertTrue(comparePackageVersions("1.2.0", "1.1.9") > 0)
        assertEquals(0, comparePackageVersions("1.2", "1.2.0"))
        assertTrue(comparePackageVersions("2.0.0", "10.0.0") < 0)
    }

    @Test
    fun `parses source package manifest`() {
        val item = ApiContentPackage.fromJson(JSONObject().apply {
            put("packageId", "source.example")
            put("kind", "online_source")
            put("version", "1.4.0")
            put("sha256", "abc")
            put("payload", "source.example.json")
            put("downloadUrl", "/v1/packages/online_source/source.example/download")
        })
        assertEquals(ApiPackageKind.ONLINE_SOURCE, item?.kind)
        assertEquals("1.4.0", item?.version)
        assertEquals("source.example.json", item?.payloadName)
        assertEquals(
            "source.example\nonline_source\nsource.example\n1.4.0\n0\nabc",
            item?.signedMessage()?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `newer build updates same version`() {
        assertTrue(isPackageUpdateAvailable("1.2.0", 200, "1.2.0", 100))
        assertTrue(!isPackageUpdateAvailable("1.2.0", 100, "1.2.0", 100))
        assertTrue(isPackageUpdateAvailable("1.2.1", 1, "1.2.0", 999))
    }
}
