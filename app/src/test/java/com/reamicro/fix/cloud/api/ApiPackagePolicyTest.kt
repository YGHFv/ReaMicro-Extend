package com.reamicro.fix.cloud.api

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ApiPackagePolicyTest {
    private fun content(minimum: String = "", schema: Int = 1) = ApiContentPackage(
        "test-source", ApiPackageKind.ONLINE_SOURCE, "1.0.0", schema, minimum,
        "", "", "/v1/packages/test/download", "测试源", "",
    )

    @Test fun compatibleAndLegacyPackagesAreAccepted() {
        requireCompatiblePackage(content(), "2.3.20")
        requireCompatiblePackage(content("2.3.20"), "2.3.20")
        requireCompatiblePackage(content("2.3.9"), "2.3.20")
        requireCompatiblePackage(content("2.3.20-beta.2"), "2.3.20")
    }

    @Test fun newerOrMalformedRequirementsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { requireCompatiblePackage(content("2.3.21"), "2.3.20") }
        assertThrows(IllegalArgumentException::class.java) { requireCompatiblePackage(content("latest"), "2.3.20") }
        assertThrows(IllegalArgumentException::class.java) { requireCompatiblePackage(content(schema = 2), "2.3.20") }
        assertThrows(IllegalArgumentException::class.java) { requireCompatiblePackage(content("2.3.20"), "2.3.20-beta.2") }
    }

    @Test fun semanticOrderingHandlesNumericAndPrereleaseSegments() {
        assertTrue(comparePackageVersions("2.3.10", "2.3.9") > 0)
        assertTrue(comparePackageVersions("2.3.20-beta.10", "2.3.20-beta.2") > 0)
        assertEquals(0, comparePackageVersions("2.3.20+build.1", "2.3.20+build.2"))
        assertTrue(comparePackageVersions("2.3.20-beta", "2.3.20") < 0)
    }

    @Test fun downloadUrlsCannotLeakCredentialsToAnotherOrigin() {
        assertEquals("https://api.example/v1/file", apiDownloadUrl("https://api.example", "/v1/file"))
        assertEquals("https://api.example:443/v1/file", apiDownloadUrl("https://api.example", "https://api.example:443/v1/file"))
        for (target in listOf("//evil.example/file", "https://evil.example/file", "http://api.example/file",
            "https://api.example:444/file", "https://name@api.example/file")) {
            assertThrows(IllegalArgumentException::class.java) { apiDownloadUrl("https://api.example", target) }
        }
    }

    @Test fun storedAssociationNamesCannotEscapeTheirDirectory() {
        val directory = Files.createTempDirectory("package-paths").toFile()
        try {
            assertEquals(directory.canonicalFile, ApiPackagePaths.child(directory, "source.rmsource").parentFile)
            for (name in listOf("", ".", "..", "../source", "folder/source", "folder\\source")) {
                assertThrows(IllegalArgumentException::class.java) { ApiPackagePaths.child(directory, name) }
            }
        } finally { directory.delete() }
    }
}
