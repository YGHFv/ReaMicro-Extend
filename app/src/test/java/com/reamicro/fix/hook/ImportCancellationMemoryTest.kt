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
    ) = importCancellationKeys(uuid, title, uri, fileName)

    @Test
    fun `同一个文件的另一条链路也能命中`() {
        val memory = ImportCancellationMemory()
        // 预检链路：拿得到 uuid 与书名，拿不到 uri。
        memory.remember(keys(uuid = uuid, title = title))
        // importBook 链路：只解析得出书名（uuid 没解析出来）——单键记忆在这里就会失配。
        assertTrue(memory.isCancelled(keys(title = title)))
    }

    @Test
    fun `按文件名也能命中`() {
        val memory = ImportCancellationMemory()
        memory.remember(keys(fileName = "三体.epub"))
        assertTrue(memory.isCancelled(keys(fileName = "三体.epub")))
    }

    @Test
    fun `文件名相同即视为同一次导入（不看大小）`() {
        // 刻意不把大小写进键：同一次导入在三个入口拿到的是不同对象（okio.Path / PlatformFile /
        // 缓存 File），大小只有部分能读到。键里带大小的话，只要有一侧读不到就两侧对不上、
        // 取消静默失效——而那正是要修的 bug。代价是同名文件在存续期内互相命中，
        // 表现为"多拦一次"，比"取消被忽略、书被覆盖"轻。
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
        assertFalse(memory.isCancelled(keys(fileName = "另一本.epub")))
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

    /**
     * 跨入口的键必须重叠。
     *
     * 实机踩过的坑：用户在覆盖检查里点了「取消导入」，但这次导入是**模块自己**从本地书库发起的
     * （`enqueueLocalLibraryImport` → 缓存文件 → `enqueueNativeImport`），而那条链路上
     * `importBook` 拿到的 opf 是 null —— Hook 侧既判不出冲突、也（当时）读不到取消记忆，
     * 于是书照样被导进去。现在模块的导入入口会用文件名+大小+来源 url 去查同一份取消记忆，
     * 所以两侧的键**至少要有一个相同**，这条断言把那个契约固定下来。
     */
    @Test
    fun `模块导入入口与 Hook 的文件名键重叠`() {
        // Hook 侧：从导入源对象解析出的身份（预检是 okio.Path，importBook 是 PlatformFile）
        val hookKeys = importCancellationKeys(
            uuid = "",
            title = "三体",
            uri = "",
            fileName = "三体.epub",
        )
        // 模块自己的导入入口：只有缓存文件名 / 来源 url
        val entryKeys = ImportCancellations.keysForSource("三体.epub", "local-library://x")
        assertTrue(
            "两侧没有任何共同键，用户在 Hook 里的取消就传不到模块导入入口：$hookKeys vs $entryKeys",
            hookKeys.any { it in entryKeys },
        )
    }

    @Test
    fun `共享实例的取消能被模块导入入口查到`() {
        val file = "你说这是恋爱游戏？_v2.epub"
        ImportCancellations.memory.clear()
        ImportCancellations.remember(
            uuid = "1accf8f9-fe66-d57a-e295-de97edba7183",
            title = "你说这是恋爱游戏？",
            uri = "",
            fileName = file,
            fileSize = 11_692_886L,
        )
        assertTrue(ImportCancellations.peek(ImportCancellations.keysForSource(file, "local-library://x")))
        ImportCancellations.memory.clear()
    }

    @Test
    fun `模块导入入口不会误伤别的文件`() {
        ImportCancellations.memory.clear()
        ImportCancellations.remember(
            uuid = "u",
            title = "三体",
            uri = "",
            fileName = "三体.epub",
            fileSize = 100L,
        )
        assertFalse(ImportCancellations.peek(ImportCancellations.keysForSource("另一本.epub", "local-library://y")))
        ImportCancellations.memory.clear()
    }

    /**
     * 「刚取消过」的时间窗。
     *
     * 实机日志：一次本地书库导入被取消后，宿主还会以
     * `importBook(null,null,null,null,null,null,continuation)` 再调几次——实参连文件名都没有，
     * 取消记忆按身份匹配必然落空。这类调用不可能是别的书的合法导入，所以只要刚落过一次取消，
     * 就把它们一并取消。这里锁住时间窗语义：窗口内为真、窗口外为假、没取消过为假。
     */
    @Test
    fun `刚取消过的时间窗内为真`() {
        var now = 5_000L
        val memory = ImportCancellationMemory(ttlMs = 600_000L, nowProvider = { now })
        assertFalse("没取消过就应为假", memory.hasRecentCancellation(30_000L))

        memory.remember(listOf("uuid:x"))
        assertTrue(memory.hasRecentCancellation(30_000L))

        now = 5_000L + 30_000L
        assertTrue("刚好到窗口边界仍算刚取消过", memory.hasRecentCancellation(30_000L))

        now = 5_000L + 30_001L
        assertFalse("超出窗口就不该再拦无身份调用", memory.hasRecentCancellation(30_000L))
    }
}
