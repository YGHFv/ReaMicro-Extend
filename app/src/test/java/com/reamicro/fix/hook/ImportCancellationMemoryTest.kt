package com.reamicro.fix.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「取消导入」记忆的多键匹配。
 *
 * 回归背景：宿主把导入做成**可重发**的 Work——`HomeViewModel.scanAutoImportDirectory` 每次扫描
 * 都会为同一个文件重新发起导入，zip/txt 等还会走别的车道。模块只能靠抛异常中止**当前那次**
 * Work，所以取消必须按文件身份记住、并在重发时再次命中。
 *
 * 此前只存"uuid 优先、否则书名"这**一个**键：预检（`EpubFileManager.import`）与 `importBook`
 * 两条链路解析出的身份不一致时（一条有 uuid、另一条没有）就会失配，用户表现为
 * 「点了取消导入，书还是被导入/更新了」。这里锁住多键并集的语义。
 */
class ImportCancellationMemoryTest {

    private val uuid = "0f8b1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d"
    private val title = "三体"

    private fun keys(
        uuid: String = "",
        title: String = "",
        uri: String = "",
        fileName: String = "",
        fileSize: Long = 0L,
    ) = importCancellationKeys(uuid, title, uri, fileName, fileSize)

    @Test
    fun `同一个文件的另一条链路也能命中`() {
        val memory = ImportCancellationMemory()
        // 预检链路：拿得到 uuid 与书名，拿不到 uri。
        memory.remember(keys(uuid = uuid, title = title))
        // importBook 链路：只解析得出书名（uuid 没解析出来）——单键记忆在这里就会失配。
        assertTrue(memory.isCancelled(keys(title = title)))
    }

    @Test
    fun `按文件名与大小也能命中`() {
        val memory = ImportCancellationMemory()
        memory.remember(keys(fileName = "三体.epub", fileSize = 1234L))
        assertTrue(memory.isCancelled(keys(fileName = "三体.epub", fileSize = 1234L)))
    }

    @Test
    fun `同名不同大小不算同一个文件`() {
        val memory = ImportCancellationMemory()
        memory.remember(keys(fileName = "三体.epub", fileSize = 1234L))
        assertFalse(memory.isCancelled(keys(fileName = "三体.epub", fileSize = 9999L)))
    }

    @Test
    fun `取不到大小时退化成只按文件名`() {
        val memory = ImportCancellationMemory()
        memory.remember(keys(fileName = "三体.epub"))
        assertTrue(memory.isCancelled(keys(fileName = "三体.epub")))
    }

    @Test
    fun `书名做空白归一化`() {
        val memory = ImportCancellationMemory()
        memory.remember(keys(title = "  三体  黑暗森林 "))
        assertTrue(memory.isCancelled(keys(title = "三体 黑暗森林")))
    }

    @Test
    fun `无关的书不会被误伤`() {
        val memory = ImportCancellationMemory()
        memory.remember(keys(uuid = uuid, title = title))
        assertFalse(memory.isCancelled(keys(uuid = "another-uuid", title = "另一本书")))
        assertFalse(memory.isCancelled(keys(fileName = "另一本.epub", fileSize = 1L)))
    }

    @Test
    fun `不同类型的身份不会互相撞键`() {
        // 书名恰好等于 uuid 这种输入不该让两个不同的文件互相命中。
        val memory = ImportCancellationMemory()
        memory.remember(keys(uuid = "shared-value"))
        assertFalse(memory.isCancelled(keys(title = "shared-value")))
    }

    @Test
    fun `一个身份都取不到时不记录也不命中`() {
        // 取不到身份就没法在重发时认出来；此时 remember 返回 false，调用方会记一条日志。
        val memory = ImportCancellationMemory()
        assertFalse(memory.remember(keys()))
        assertFalse(memory.isCancelled(keys()))
        assertEquals(0, memory.size())
    }

    @Test
    fun `超过存活时长后不再命中`() {
        // 用可注入时钟测真实的过期边界；否则这条只能靠 ttl=0 之类的技巧，测不到语义本身。
        var now = 1_000L
        val memory = ImportCancellationMemory(ttlMs = 1_000L, nowProvider = { now })
        val k = keys(uuid = uuid)
        memory.remember(k)

        now = 2_000L
        assertTrue("刚好到边界仍应在存续期内", memory.isCancelled(k))

        now = 2_001L
        assertFalse("超过存活时长就该失效，否则会一直拦截同一个文件", memory.isCancelled(k))
        assertEquals(0, memory.size())
    }

    @Test
    fun `用户重新导入时可以主动清除`() {
        val memory = ImportCancellationMemory()
        val k = keys(uuid = uuid)
        memory.remember(k)
        assertTrue(memory.isCancelled(k))
        memory.forget(k)
        assertFalse(memory.isCancelled(k))
    }

    @Test
    fun `空白的身份片段会被丢掉`() {
        assertEquals(emptyList<String>(), keys(title = "   ", uri = "  "))
    }
}
