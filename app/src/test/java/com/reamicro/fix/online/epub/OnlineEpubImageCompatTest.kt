package com.reamicro.fix.online.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineEpubImageCompatTest {
    @Test
    fun stableImageNameDependsOnUrlInsteadOfDownloadOrder() {
        val first = stableOnlineImageFileStem("https://img.example/a.jpg?token=1")
        val again = stableOnlineImageFileStem("https://img.example/a.jpg?token=1")
        val other = stableOnlineImageFileStem("https://img.example/b.jpg?token=1")

        assertEquals(first, again)
        assertTrue(first.startsWith("online_img_"))
        assertFalse(first == other)
    }

    @Test
    fun detectsSupportedManifestTypes() {
        assertEquals("image/jpeg", onlineEpubImageManifestItem("online_img_a.jpg")?.mimeType)
        assertEquals("image/png", onlineEpubImageManifestItem("online_img_b.png")?.mimeType)
        assertEquals("image/webp", onlineEpubImageManifestItem("online_img_c.webp")?.mimeType)
        assertEquals(null, onlineEpubImageManifestItem("online_img_d.bin"))
    }

    @Test
    fun addsMissingImagesToOpfWithoutDuplicatingExistingEntry() {
        val opf = """<package><manifest>
            <item id="old" href="Images/online_img_existing.jpg" media-type="image/jpeg"/>
          </manifest></package>""".trimIndent()
        val merged = mergeOnlineEpubImageManifest(
            opf,
            listOf(
                OnlineEpubImageManifestItem("online_img_existing.jpg", "image/jpeg"),
                OnlineEpubImageManifestItem("online_img_new.png", "image/png"),
            ),
        )

        assertEquals(1, Regex("online_img_existing\\.jpg").findAll(merged).count())
        assertEquals(1, Regex("online_img_new\\.png").findAll(merged).count())
        assertTrue(merged.contains("media-type=\"image/png\""))
    }
}
