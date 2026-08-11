package com.reamicro.fix.online.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 启动缓存清理挑选目标的规则。
 *
 * 重点是「不能删到还在用的东西」：当前设置指向的那张头图必须留下，
 * 其余同目录的历史文件才是重置头图后没跟着删的残留。
 */
class StartupCacheCleanupTargetsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun activeProfileBackgroundIsKeptAndOthersAreOrphans() {
        val filesDir = folder.newFolder("files")
        val dir = File(filesDir, "profile_background").apply { mkdirs() }
        val active = File(dir, "profile_background_2.png").apply { writeText("active") }
        val stale = File(dir, "profile_background_1.png").apply { writeText("stale") }

        val orphans = orphanProfileBackgroundFiles(filesDir, active.absolutePath)

        assertEquals(listOf(stale.name), orphans.map { it.name })
        assertTrue(active.isFile)
    }

    @Test
    fun everyFileIsOrphanWhenNoBackgroundIsConfigured() {
        val filesDir = folder.newFolder("files")
        val dir = File(filesDir, "profile_background").apply { mkdirs() }
        File(dir, "profile_background_1.png").writeText("a")
        File(dir, "profile_background_2.png").writeText("b")

        val orphans = orphanProfileBackgroundFiles(filesDir, "")

        assertEquals(2, orphans.size)
    }

    @Test
    fun missingProfileBackgroundDirYieldsNoTargets() {
        assertTrue(orphanProfileBackgroundFiles(folder.newFolder("files"), "").isEmpty())
    }

    @Test
    fun activePathIsMatchedThroughNonCanonicalForm() {
        val filesDir = folder.newFolder("files")
        val dir = File(filesDir, "profile_background").apply { mkdirs() }
        val active = File(dir, "profile_background_1.png").apply { writeText("active") }
        val indirect = File(dir, "./profile_background_1.png").path

        assertTrue(orphanProfileBackgroundFiles(filesDir, indirect).isEmpty())
        assertTrue(active.isFile)
    }

    @Test
    fun paragraphCommentCacheDirResolvesUnderFilesDir() {
        val filesDir = folder.newFolder("files")

        val target = paragraphCommentCacheDir(filesDir)

        assertEquals(filesDir, target.parentFile)
        assertEquals("online-paragraph-comments", target.name)
    }

    @Test
    fun freshImportCacheDirIsNotTreatedAsStale() {
        val cacheDir = folder.newFolder("cache")
        File(cacheDir, "6f1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9").mkdirs()

        assertTrue(staleTopLevelImportCacheDirs(cacheDir).isEmpty())
    }

    @Test
    fun oldImportCacheDirIsStaleButUnrelatedDirIsNot() {
        val cacheDir = folder.newFolder("cache")
        val old = File(cacheDir, "6f1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9").apply {
            mkdirs()
            setLastModified(System.currentTimeMillis() - 3 * 60 * 60_000L)
        }
        File(cacheDir, "reamicro-webdav").apply {
            mkdirs()
            setLastModified(System.currentTimeMillis() - 3 * 60 * 60_000L)
        }

        val stale = staleTopLevelImportCacheDirs(cacheDir)

        assertEquals(listOf(old.name), stale.map { it.name })
        assertFalse(stale.any { it.name == "reamicro-webdav" })
    }
}
