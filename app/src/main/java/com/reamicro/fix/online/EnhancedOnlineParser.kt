package com.reamicro.fix.online

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.discover.DiscoverKind
import com.reamicro.fix.hook.HostSourceBrowserBridge
import com.reamicro.fix.hook.webdav.OnlineBinaryPayload
import com.reamicro.fix.hook.webdav.OnlineBookSearchResult
import com.reamicro.fix.hook.webdav.OnlineChapter
import com.reamicro.fix.hook.webdav.OnlineDownloadTarget
import com.reamicro.fix.hook.webdav.OnlineHttpResponse
import com.reamicro.fix.hook.webdav.OnlineTocSnapshot
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.TaskCancellation
import com.reamicro.fix.xposed.HookRuntime
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.webBook.WebBook
import io.legado.app.platform.LegadoPlatform
import io.legado.app.platform.ParserRepository
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/** The only engine switch boundary. Enhanced failures are reported without retrying legacy rules. */
internal object EnhancedOnlineParser {
    private val jobs = ConcurrentHashMap<Job, Long>()
    private val cacheLock = Any()
    private val cacheDirty = java.util.concurrent.atomic.AtomicBoolean()
    private val browserScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = EnhancedParserRepository { context() }
    private val platform = object : LegadoPlatform.Adapter {
        override val context: Context get() = context()
        override fun getCookie(url: String): String? =
            OnlineSourceAuth.parserCookie(context, url, repository.current.get()?.source)
        override fun putCookie(url: String, cookie: String?) =
            OnlineSourceAuth.putParserCookie(context, url, cookie, repository.current.get()?.source)
        override fun clearCookies() = OnlineSourceAuth.clearParserCookies(context)
        override fun captureSession(): ((() -> Any?) -> Any?)? =
            repository.current.get()?.let { op -> { action -> repository.withOperation(op, action) } }
        override suspend fun browser(request: LegadoPlatform.BrowserRequest): LegadoPlatform.BrowserResult {
            val op = repository.current.get()
            return HostSourceBrowserBridge.browse(request.copy(
                sourceSnapshot = op?.upstream,
                coroutineContext = currentCoroutineContext(),
                sessionRunner = op?.let { captured -> { action -> repository.withOperation(captured, action) } },
            ))
        }
        override suspend fun verificationCode(sourceKey: String?, imageUrl: String): String =
            HostSourceBrowserBridge.verificationCode(sourceKey, imageUrl)
        override fun openUrl(url: String, mimeType: String?, sourceKey: String?, sourceName: String?) {
            check(ModuleResources.accepting) { "模块正在重载" }
            val generation = OnlineParserMode.generation
            val op = repository.current.get()
            val operationContext = op?.let { repository.current.asContextElement(it) }
                ?: kotlin.coroutines.EmptyCoroutineContext
            browserScope.launch(operationContext) {
                try {
                    OnlineParserMode.requireCurrent(generation)
                    browser(LegadoPlatform.BrowserRequest(
                        url, title = sourceName, sourceKey = sourceKey, awaitResult = false,
                        isRule = op != null,
                    ))
                } catch (cancel: java.util.concurrent.CancellationException) { throw cancel }
                catch (error: Throwable) { toast(error.message ?: "无法打开书源网页") }
            }
        }
        override fun importUrl(url: String) {
            repository.current.get()?.check()
            OnlineSourceStore.importFromUrl(context, url)
        }
        override fun sourceAction(name: String, url: String?, title: String?, origin: String?, sourceKey: String?) {
            val op = repository.current.get()
            op?.check()
            when (name) {
                "upConfig" -> check(HostSourceBrowserBridge.updateConfig(sourceKey, url.orEmpty())) {
                    "没有可更新配置的书源网页"
                }
                "photo", "web", "browser" -> openUrl(requireNotNull(url), null, sourceKey, title)
                "login", "explore", "search", "searchBook", "addBook" ->
                    com.reamicro.fix.hook.HostSourceActions.dispatch(
                        checkNotNull(op) { "书源网页会话已结束" }.source, name, url, title, origin,
                    )
                else -> throw UnsupportedOperationException("阅微未提供对应的阅读应用页面：$name")
            }
        }
        override fun themeMode(): String =
            if (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES) "2" else "1"
        override fun log(message: String, error: Throwable?) {
            // Rule text may contain tokens. Detailed output follows the existing opt-in module logger.
            if (error == null) HookRuntime.log("[LegadoParser] $message")
            else HookRuntime.log("[LegadoParser] $message: ${error.javaClass.simpleName}")
        }
    }

    init {
        LegadoPlatform.install(platform)
        ParserRepository.install(repository)
        val modeObserver = OnlineParserMode.observeChanges {
            val generation = OnlineParserMode.generation
            jobs.forEach { (job, captured) -> if (captured != generation) job.cancel() }
        }
        val sourceObserver = OnlineSourceStore.observeChanges { cacheDirty.set(true) }
        ModuleResources.cleanup {
            modeObserver.close()
            sourceObserver.close()
            jobs.keys.toList().forEach { it.cancel() }
            browserScope.cancel()
            repository.close()
            io.legado.app.model.SharedJsScope.clearRuntimeCache()
            ParserRepository.uninstall(repository)
            LegadoPlatform.uninstall(platform)
        }
    }

    fun attach(context: Context) = OnlineParserContext.attach(context)
    private fun context(): Context = OnlineParserContext.require()

    internal fun decodeSource(source: OnlineSourceEntry): BookSource {
        check(source.rawJson.isNotBlank()) { "书源原始配置缺失，请重新导入书源" }
        return GSON.fromJson(source.rawJson, BookSource::class.java).also {
            it.concurrentRate = OnlineSourceDownloadPolicyStore.effectiveConcurrentRate(source)
        }
    }

    internal fun <T> run(
        source: OnlineSourceEntry,
        state: OnlineParsingState = OnlineParserMode.capture(),
        book: EnhancedParserBook? = null,
        block: suspend (EnhancedParserOperation) -> T,
    ): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "书源规则必须在工作线程解析" }
        OnlineParserMode.requireCurrent(state.generation)
        check(state.enhanced) { "增强解析已关闭" }
        OnlineSourceStore.requireCurrent(source)
        val lease = ModuleResources.lease() ?: throw java.util.concurrent.CancellationException("模块正在重载")
        val upstream = try {
            synchronized(cacheLock) {
                if (cacheDirty.getAndSet(false)) io.legado.app.help.source.clearParserRuntimeCaches()
            }
            decodeSource(source).apply {
                setTemporaryVariable(book?.sourceTemporaryVariable ?: state.sourceTemporaryVariable)
            }
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
        val op = EnhancedParserOperation(source, upstream, state.generation, book)
        val job = Job()
        jobs[job] = state.generation
        val cancellation = TaskCancellation.current()?.register { job.cancel() }
        val sourceObserver = OnlineSourceStore.observeChanges {
            if (!OnlineSourceStore.isCurrent(source)) job.cancel()
        }
        try {
            op.check()
            return runBlocking(job + repository.current.asContextElement(op)) {
                currentCoroutineContext().ensureActive()
                val result = block(op)
                currentCoroutineContext().ensureActive()
                op.check()
                op.book?.let {
                    if (it.sourceTemporaryVariable != op.upstream.getTemporaryVariable()) it.dirty = true
                    it.sourceTemporaryVariable = op.upstream.getTemporaryVariable()
                }
                if (op.book?.dirty == true) repository.persist(op.book!!)
                result
            }
        } finally {
            sourceObserver.close()
            cancellation?.close()
            job.cancel()
            jobs.remove(job)
            lease.close()
        }
    }

    fun search(source: OnlineSourceEntry, query: String, page: Int = 1): List<OnlineBookSearchResult> {
        val mode = OnlineParserMode.capture()
        return run(source, mode) { op ->
            WebBook.searchBookAwait(op.upstream, query, page).map { result ->
                result.toResult(source, mode.copy(sourceTemporaryVariable = op.upstream.getTemporaryVariable()))
            }
        }
    }

    fun kinds(source: OnlineSourceEntry): List<DiscoverKind> =
        discoverControls(source).rows.map { row ->
            DiscoverKind(row.title, row.url, upstreamJson = row.upstreamJson, control = row)
        }

    fun explore(source: OnlineSourceEntry, kind: DiscoverKind, page: Int): List<DiscoverBook> {
        val mode = OnlineParserMode.capture()
        return run(source, mode) { op ->
            WebBook.exploreBookAwait(op.upstream, kind.url, page).map { item ->
                DiscoverBook(
                    name = item.name, author = item.author, coverUrl = item.coverUrl.orEmpty(),
                    detailUrl = item.bookUrl, intro = item.intro.orEmpty(), kind = item.kind.orEmpty(),
                    lastChapter = item.latestChapterTitle.orEmpty(), wordCount = item.wordCount.orEmpty(),
                    parsingState = mode.copy(bookJson = GSON.toJson(item.toBook()),
                        sourceTemporaryVariable = op.upstream.getTemporaryVariable()),
                )
            }
        }
    }

    fun toc(target: OnlineDownloadTarget, onProgress: (Int, String) -> Unit): OnlineTocSnapshot {
        val state = OnlineParserMode.forTarget(target)
        return run(target.source, state, target.parserBook) { op ->
            val parserBook = target.parserBook ?: if (state.bookJson.isNotBlank()) {
                EnhancedParserBook(target.source, GSON.fromJson(state.bookJson, Book::class.java))
            } else repository.read(target.source, target.result.detailUrl)
                ?: EnhancedParserBook(target.source, newBook(target))
            op.upstream.setTemporaryVariable(parserBook.sourceTemporaryVariable ?: state.sourceTemporaryVariable)
            op.book = parserBook
            onProgress(3, "读取详情页")
            WebBook.getBookInfoAwait(op.upstream, parserBook.book)
            onProgress(4, "读取目录")
            val chapters = WebBook.getChapterListAwait(op.upstream, parserBook.book, runPerJs = true).getOrThrow()
            check(chapters.isNotEmpty()) { "在线源目录为空：${target.source.name}" }
            parserBook.chapters = chapters
            parserBook.dirty = true
            parserBook.replaceChapters = true
            target.parserBook = parserBook
            val book = parserBook.book
            target.result = target.result.copy(
                name = book.name.ifBlank { target.result.name },
                author = book.author.ifBlank { target.result.author },
                coverUrl = book.coverUrl.orEmpty().ifBlank { target.result.coverUrl },
                intro = book.intro.orEmpty(), chapterCount = chapters.count { !it.isVolume },
                wordCount = book.wordCount.orEmpty(),
                parsingState = state.copy(bookJson = GSON.toJson(book),
                    sourceTemporaryVariable = op.upstream.getTemporaryVariable()),
            )
            var volume = ""
            val readable = chapters.mapIndexedNotNull { index, chapter ->
                if (chapter.isVolume && chapter.url.startsWith(chapter.title)) {
                    volume = chapter.title
                    null
                } else OnlineChapter(
                    chapter.title, chapter.getAbsoluteURL(), volume, chapter.tocLevel,
                    GSON.toJson(chapter), chapters.getOrNull(index + 1)?.getAbsoluteURL(),
                )
            }
            check(readable.isNotEmpty()) { "在线源目录没有可下载章节" }
            OnlineTocSnapshot(
                OnlineHttpResponse(book.bookUrl, book.infoHtml.orEmpty()),
                OnlineHttpResponse(book.tocUrl, book.tocHtml.orEmpty()), readable,
            )
        }
    }

    fun content(target: OnlineDownloadTarget, chapter: OnlineChapter, index: Int): String {
        val state = OnlineParserMode.forTarget(target)
        var session = target.parserBook
        if (session == null) {
            // On-demand reads and retries reopen the persisted upstream variables.
            session = repository.read(target.source, target.result.detailUrl)
            if (session == null) {
                toc(target) { _, _ -> }
                session = target.parserBook
            }
            target.parserBook = session
        }
        val parserBook = checkNotNull(session)
        return run(target.source, state, parserBook) { op ->
            val upstreamChapter = if (chapter.upstreamJson.isNotBlank()) {
                parserBook.chapters.firstOrNull { it.getAbsoluteURL() == chapter.url }
                    ?: GSON.fromJson(chapter.upstreamJson, BookChapter::class.java)
            } else parserBook.chapters.firstOrNull { it.getAbsoluteURL() == chapter.url }
                ?: error("章节已不在上游目录中，请更新图书")
            val next = chapter.nextChapterUrl ?: parserBook.chapters
                .firstOrNull { it.index == upstreamChapter.index + 1 }?.getAbsoluteURL()
            val bookVariable = parserBook.book.variable
            val chapterVariable = upstreamChapter.variable
            val content = WebBook.getContentAwait(op.upstream, parserBook.book, upstreamChapter, next, needSave = false)
            if (bookVariable != parserBook.book.variable || chapterVariable != upstreamChapter.variable) parserBook.dirty = true
            if (chapterVariable != upstreamChapter.variable) parserBook.dirtyChapters += upstreamChapter.index
            content
        }
    }

    fun bytes(
        source: OnlineSourceEntry,
        url: String,
        isCover: Boolean = false,
        book: Book? = null,
        state: OnlineParsingState = OnlineParserMode.capture(),
        session: EnhancedParserBook? = null,
    ): OnlineBinaryPayload =
        run(source, state, session) { op ->
            val ruleBook = session?.book ?: book
            val request = AnalyzeUrl(url, baseUrl = source.sourceUrl, source = op.upstream,
                ruleData = ruleBook, coroutineContext = currentCoroutineContext())
            val bytes = if (url.startsWith("data:", true)) {
                check(url.length <= 24 * 1024 * 1024) { "图片超过 16 MiB" }
                request.getByteArrayAwait().also { check(it.size <= 16 * 1024 * 1024) { "图片超过 16 MiB" } }
            } else request.getResponseAwait().use { response ->
                check(response.body.contentLength() <= 16 * 1024 * 1024) { "图片超过 16 MiB" }
                response.body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count <= 16 * 1024 * 1024 - output.size()) { "图片超过 16 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
            val variable = ruleBook?.variable
            val decoded = com.script.rhino.runScriptWithContext(currentCoroutineContext()) {
                ImageUtils.decode(url, bytes, isCover, op.upstream, ruleBook)
            } ?: error("书源图片解码失败")
            if (session != null && variable != ruleBook?.variable) session.dirty = true
            OnlineBinaryPayload(decoded, java.net.URLConnection.guessContentTypeFromStream(
                java.io.ByteArrayInputStream(decoded)).orEmpty(), request.url)
        }

    fun login(source: OnlineSourceEntry): OnlineSourceAuthResult = try {
        run(source) { op ->
            com.script.rhino.runScriptWithContext(currentCoroutineContext()) { op.upstream.login() }
        }
        OnlineSourceAuthResult(true, "已执行书源登录规则")
    } catch (error: Throwable) {
        TaskCancellation.rethrowIfCancelled(error)
        OnlineSourceAuthResult(false, error.message ?: "书源登录失败")
    }

    private fun newBook(target: OnlineDownloadTarget) = Book(
        bookUrl = target.result.detailUrl, origin = target.source.sourceUrl,
        originName = target.source.name, name = target.result.name, author = target.result.author,
        coverUrl = target.result.coverUrl, intro = target.result.intro,
    )

    private fun SearchBook.toResult(source: OnlineSourceEntry, mode: OnlineParsingState) = OnlineBookSearchResult(
        sourceName = source.name, name = name, author = author, coverUrl = coverUrl.orEmpty(),
        detailUrl = bookUrl, intro = intro.orEmpty(), wordCount = wordCount.orEmpty(),
        parsingState = mode.copy(bookJson = GSON.toJson(toBook())),
    )
}
