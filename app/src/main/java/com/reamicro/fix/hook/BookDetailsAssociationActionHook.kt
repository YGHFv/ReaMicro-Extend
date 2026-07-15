package com.reamicro.fix.hook

import android.app.Activity
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

class BookDetailsAssociationActionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {
    private val localBooksById = ConcurrentHashMap<Long, Any>()
    private val localBooksByCloudId = ConcurrentHashMap<Long, Any>()
    private val completedCoverFixes = ConcurrentHashMap<String, Boolean>()
    @Volatile private var currentDetailsContext: DetailContext? = null

    fun install() {
        hookBookshelfDetailNavigation()
        hookBookDetailsViewModel()
        hookBookOverviewViewModel()
        hookNavGraphScope()
        hookNavigationBackCleanup()
    }

    fun requestCoverFixForCurrentDetails(): Boolean {
        val details = currentDetailsContext ?: return false
        fixAssociationCover(details.bookId)
        return true
    }

    private fun hookBookshelfDetailNavigation() {
        runCatching {
            val screenClass = XposedHelpers.findClass(BOOKSHELF_SCREEN_CLASS, classLoader)
            val methods = screenClass.declaredMethods.filter { method ->
                method.parameterTypes.size == 4 &&
                    method.parameterTypes.lastOrNull()?.name == BOOK_CLASS
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val book = param.args?.getOrNull(3) ?: return
                        cacheLocalBook(book)
                    }
                })
            }
            XposedBridge.log("$LOG_PREFIX book details association bookshelf cache hooks installed: ${methods.size}")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook bookshelf detail navigation: ${it.stackTraceToString()}")
        }
    }

    private fun hookBookDetailsViewModel() {
        runCatching {
            val viewModelClass = XposedHelpers.findClass(BOOK_DETAILS_VIEW_MODEL_CLASS, classLoader)
            XposedBridge.hookAllConstructors(viewModelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val viewModel = param.thisObject ?: return
                    val args = fieldValue(viewModel, "args") ?: param.args?.getOrNull(0) ?: return
                    val repository = fieldValue(viewModel, "repository") ?: param.args?.getOrNull(1) ?: return
                    val bookId = callLong(args, "getBookId")
                    val compat = callBoolean(args, "getCompat")
                    currentDetailsContext = DetailContext(
                        bookId = bookId,
                        compat = compat,
                        viewModelRef = WeakReference(viewModel),
                        repositoryRef = WeakReference(repository),
                    )
                    XposedBridge.log("$LOG_PREFIX book details association context captured: bookId=$bookId, compat=$compat")
                }
            })
            XposedBridge.hookAllMethods(viewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentDetailsContext?.viewModelRef?.get() === param.thisObject) {
                        clearBookDetailsContext()
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BookDetailsViewModel: ${it.stackTraceToString()}")
        }
    }

    /**
     * 2.2.0 起点书打开的是 BookOverviewScreen（BookOverviewViewModel），封面弹窗 CoverBottomSheet
     * 也挂在该页；而封面修复原本只监听 BookDetailsViewModel，导致 context 从未捕获、
     * requestCoverFix() 恒返回 false（Toast「当前页面无法执行封面修复」）。
     * 这里额外监听 BookOverviewViewModel：构造器捕获 bookId + bookRepository(BookRepository)，
     * applyBook(Book) 缓存实际 Book 供后续封面上传使用。
     */
    private fun hookBookOverviewViewModel() {
        runCatching {
            val viewModelClass = XposedHelpers.findClass(BOOK_OVERVIEW_VIEW_MODEL_CLASS, classLoader)
            XposedBridge.hookAllConstructors(viewModelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val viewModel = param.thisObject ?: return
                    val bookId = fieldValue(viewModel, "bookId")?.let { it as? Long } ?: callLong(viewModel, "getBookId")
                    val repository = fieldValue(viewModel, "bookRepository")
                        ?: param.args?.getOrNull(1)
                        ?: return
                    currentDetailsContext = DetailContext(
                        bookId = bookId,
                        compat = true,
                        viewModelRef = WeakReference(viewModel),
                        repositoryRef = WeakReference(repository),
                        isOverview = true,
                    )
                    XposedBridge.log("$LOG_PREFIX book overview association context captured: bookId=$bookId")
                }
            })
            XposedBridge.hookAllMethods(viewModelClass, "applyBook", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0) ?: return
                    cacheLocalBook(book)
                }
            })
            XposedBridge.hookAllMethods(viewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (currentDetailsContext?.viewModelRef?.get() === param.thisObject) {
                        clearBookDetailsContext()
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX book overview viewModel hook installed")
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BookOverviewViewModel: ${it.stackTraceToString()}")
        }
    }

    private fun hookNavGraphScope() {
        runCatching {
            val navGraphScopeClass = XposedHelpers.findClass(NAV_GRAPH_SCOPE_CLASS, classLoader)
            XposedBridge.hookAllMethods(navGraphScopeClass, "navigate", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val route = param.args?.getOrNull(0)
                    val routeName = route?.javaClass?.name
                    if (routeName != ROUTE_BOOK_DETAIL_CLASS && routeName != ROUTE_BOOK_OVERVIEW_CLASS) {
                        clearBookDetailsContext()
                    }
                }
            })
            XposedBridge.hookAllMethods(navGraphScopeClass, "popBackStack", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == true) clearBookDetailsContext()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavGraphScope: ${it.stackTraceToString()}")
        }
    }

    private fun hookNavigationBackCleanup() {
        runCatching {
            val holderClass = XposedHelpers.findClass(NAV_CONTROLLER_HOLDER_CLASS, classLoader)
            XposedBridge.hookAllMethods(holderClass, "popBack", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    clearBookDetailsContext()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavControllerHolder popBack: ${it.stackTraceToString()}")
        }
        runCatching {
            val navControllerClass = XposedHelpers.findClass(ANDROIDX_NAV_CONTROLLER_CLASS, classLoader)
            XposedBridge.hookAllMethods(navControllerClass, "popBackStack", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != false) clearBookDetailsContext()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook NavController popBackStack: ${it.stackTraceToString()}")
        }
    }

    private fun fixAssociationCover(bookId: Long) {
        val activity = activityProvider()
        val details = currentDetailsContext
        Thread {
            val result = runCatching {
                if (details == null || details.bookId != bookId) error("Book details unavailable")
                val repository = details.repositoryRef.get() ?: error("BookRepository unavailable")
                val bookshelf = fieldValue(repository, "bookshelfRepository") ?: error("BookshelfRepository unavailable")
                val book = findLocalBookForDetails(bookshelf, details) ?: error("Book not found: $bookId")
                val coverUrl = resolveDetailsCoverUrl(repository, details, book)
                if (!isRemoteCoverUrl(coverUrl)) error("Cover unavailable")
                val dedupeKey = "$bookId|$coverUrl"
                if (completedCoverFixes.putIfAbsent(dedupeKey, true) != null) {
                    CoverFixUploadResult(0L, coverUrl)
                } else {
                    runCatching {
                        CoverFixUploadResult(postAssociationCover(repository, book, coverUrl), coverUrl)
                    }.onFailure {
                        completedCoverFixes.remove(dedupeKey)
                    }.getOrThrow()
                }
            }
            activity?.runOnUiThread {
                result
                    .onSuccess { upload ->
                        Toast.makeText(activity, "\u5df2\u4e0a\u4f20\u5c01\u9762", Toast.LENGTH_SHORT).show()
                        XposedBridge.log(
                            "$LOG_PREFIX association cover fix uploaded manually: " +
                                "bookId=$bookId, userBookId=${upload.userBookId}, cover=${upload.coverUrl}",
                        )
                    }
                    .onFailure {
                        val message = if (it.message == "Cover unavailable") {
                            "\u672a\u627e\u5230\u53ef\u4e0a\u4f20\u7684\u5c01\u9762"
                        } else {
                            "\u5c01\u9762\u4fee\u590d\u5931\u8d25"
                        }
                        XposedBridge.log("$LOG_PREFIX failed to fix association cover manually: ${it.stackTraceToString()}")
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    }
            }
        }.apply { name = "ReaMicro-ManualCoverFix" }.start()
    }

    private fun resolveDetailsCoverUrl(repository: Any, details: DetailContext, book: Any): String {
        val publisherName = callString(book, "getPublisher").trim()
        // BookOverview 页的 state 是 BookOverviewUiState，没有 getPublishers()，
        // 封面来自 getRelationPublisher()（单个 Publisher）与 getBook().getCover()。
        if (details.isOverview) {
            overviewStateCoverUrl(details, publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
        } else {
            publisherCoverUrl(detailStatePublishers(details), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
            publisherCoverUrl(fetchCloudInfoPublishers(repository, details), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
        }
        return callString(book, "getCover").trim().takeIf(::isRemoteCoverUrl).orEmpty()
    }

    // 从 BookOverviewUiState 读取封面：优先关联出版方 relationPublisher.cover，
    // 其次 state.book.cover。返回首个远程 URL。
    private fun overviewStateCoverUrl(details: DetailContext, publisherName: String): String {
        val state = runCatching {
            details.viewModelRef.get()?.let { XposedHelpers.callMethod(it, "getCurrentState") }
        }.getOrNull() ?: return ""
        runCatching {
            XposedHelpers.callMethod(state, "getRelationPublisher")?.let { publisher ->
                publisherCoverUrl(listOf(publisher), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
            }
        }
        return runCatching {
            XposedHelpers.callMethod(state, "getBook")
                ?.let { callString(it, "getCover").trim() }
                ?.takeIf(::isRemoteCoverUrl)
                .orEmpty()
        }.getOrDefault("")
    }

    private fun detailStatePublishers(details: DetailContext): List<Any> =
        runCatching {
            details.viewModelRef.get()
                ?.let { XposedHelpers.callMethod(it, "getCurrentState") }
                ?.let(::publishersFrom)
                ?: emptyList()
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to read details publishers: ${it.stackTraceToString()}")
            emptyList()
        }

    private fun fetchCloudInfoPublishers(repository: Any, details: DetailContext): List<Any> {
        val method = (repository.javaClass.methods.asSequence() + repository.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name.contains("getBookCloudInfo", ignoreCase = true) &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE &&
                    it.parameterTypes[1] == java.lang.Boolean.TYPE
            }
            ?.apply { isAccessible = true }
            ?: return emptyList()
        return runCatching {
            val info = unwrapKotlinResult(invokeSuspendBlocking(method, repository, details.bookId, details.compat))
            publishersFrom(info)
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to fetch cloud publishers for cover fix: ${it.stackTraceToString()}")
            emptyList()
        }
    }

    private fun publishersFrom(target: Any?): List<Any> {
        if (target == null) return emptyList()
        return runCatching {
            (XposedHelpers.callMethod(target, "getPublishers") as? Iterable<*>)
                ?.mapNotNull { it }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun publisherCoverUrl(publishers: List<Any>, publisherName: String): String {
        val covers = publishers.mapNotNull { publisher ->
            val cover = callString(publisher, "getCover").trim()
            if (!isRemoteCoverUrl(cover)) return@mapNotNull null
            callString(publisher, "getPublisher").trim() to cover
        }
        return covers.firstOrNull { (name, _) ->
            publisherName.isNotEmpty() && name.equals(publisherName, ignoreCase = true)
        }?.second ?: covers.firstOrNull()?.second.orEmpty()
    }

    private fun findLocalBookForDetails(bookshelf: Any, details: DetailContext): Any? {
        findLocalBookById(bookshelf, details.bookId)?.let { book ->
            if (details.compat || callLong(book, "getCloudId") == details.bookId) return book
        }
        localBooksByCloudId[details.bookId]?.let { return it }
        if (!details.compat) {
            findLocalBookByCloudIdFromSearch(bookshelf, details)?.let { return it }
        }
        return null
    }

    private fun findLocalBookById(bookshelf: Any, bookId: Long): Any? {
        val method = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name == BOOKSHELF_FIND_BOOK_BY_ID_METHOD &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == java.lang.Long.TYPE
            }
            ?.apply { isAccessible = true }
            ?: return null
        return invokeSuspendBlocking(method, bookshelf, bookId)?.also { cacheLocalBook(it) }
    }

    private fun findLocalBookByCloudIdFromSearch(bookshelf: Any, details: DetailContext): Any? {
        val state = runCatching {
            details.viewModelRef.get()?.let { XposedHelpers.callMethod(it, "getCurrentState") }
        }.getOrNull()
        val title = state?.let { callString(it, "getTitle") }.orEmpty()
        val author = state?.let { callString(it, "getAuthor") }.orEmpty()
        val queries = listOf(title, author, "").map { it.trim() }.distinct()
        for (query in queries) {
            val book = searchLocalBooks(bookshelf, query)
                .firstOrNull { callLong(it, "getCloudId") == details.bookId }
            if (book != null) {
                cacheLocalBook(book)
                return book
            }
        }
        return null
    }

    private fun searchLocalBooks(bookshelf: Any, query: String): List<Any> {
        val method = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.name.contains(BOOKSHELF_SEARCH_METHOD_KEYWORD, ignoreCase = true) &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            }
            ?.apply { isAccessible = true }
            ?: return emptyList()
        return runCatching {
            val result = unwrapKotlinResult(invokeSuspendBlocking(method, bookshelf, query))
            (result as? Iterable<*>)?.mapNotNull { it }?.onEach { cacheLocalBook(it) } ?: emptyList()
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to search local books for unlink: ${it.stackTraceToString()}")
            emptyList()
        }
    }

    private fun postAssociationCover(repository: Any, book: Any, coverUrl: String): Long {
        val api = fieldValue(repository, "api") ?: error("Api unavailable")
        val requestClass = XposedHelpers.findClass(POST_USER_BOOK_REQ_CLASS, classLoader)
        val request = requestClass.getConstructor(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        ).newInstance(
            callString(book, "getTitle"),
            callString(book, "getAuthor"),
            callString(book, "getUuid").replace("-", ""),
            coverUrl,
            "",
        )
        val method = (api.javaClass.methods.asSequence() + api.javaClass.declaredMethods.asSequence())
            .first {
                it.name == "postUserBook" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == POST_USER_BOOK_REQ_CLASS
            }
            .apply { isAccessible = true }
        val envelope = invokeSuspendBlocking(method, api, request)
        val data = envelopeDataOrThrow(envelope) ?: return 0L
        return callLong(data, "getId")
    }

    private fun envelopeDataOrThrow(envelope: Any?): Any? {
        if (envelope == null) return null
        val envelopeKt = XposedHelpers.findClass(ENVELOPE_KT_CLASS, classLoader)
        val method = envelopeKt.declaredMethods.first {
            it.name == "dataOrThrow" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        return method.invoke(null, envelope)
    }

    private fun isRemoteCoverUrl(value: String): Boolean {
        val lower = value.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private fun clearBookDetailsContext() {
        currentDetailsContext = null
    }

    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? {
        val latch = CountDownLatch(1)
        var value: Any? = null
        var error: Throwable? = null
        val continuationClass = XposedHelpers.findClass(KOTLIN_CONTINUATION_CLASS, classLoader)
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        val continuation = Proxy.newProxyInstance(classLoader, arrayOf(continuationClass)) { proxy, proxyMethod, proxyArgs ->
            when (proxyMethod.name) {
                "getContext" -> emptyCoroutineContext()
                "resumeWith" -> {
                    val result = proxyArgs?.getOrNull(0)
                    runCatching {
                        throwOnFailure.invoke(null, result)
                        value = result
                    }.onFailure {
                        error = if (it is InvocationTargetException) it.targetException ?: it else it
                    }
                    latch.countDown()
                    targetUnit()
                }
                "toString" -> "ReaMicroAssociationDetailsContinuation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === proxyArgs?.getOrNull(0)
                else -> null
            }
        }
        val returned = try {
            method.invoke(target, *args.toMutableList().apply { add(continuation) }.toTypedArray())
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        if (returned !== coroutineSuspended()) return returned
        latch.await()
        error?.let { throw it }
        return value
    }

    private fun emptyCoroutineContext(): Any =
        XposedHelpers.findClass(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null) ?: error("EmptyCoroutineContext unavailable")

    private fun coroutineSuspended(): Any =
        runCatching {
            XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
                it.name == "getCOROUTINE_SUSPENDED" && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            (XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader).enumConstants ?: emptyArray())
                .first { value -> value.toString() == "COROUTINE_SUSPENDED" }
        }

    private fun unwrapKotlinResult(value: Any?): Any? {
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.name == "throwOnFailure" && it.parameterTypes.size == 1
        }.apply { isAccessible = true }
        try {
            throwOnFailure.invoke(null, value)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
        return value
    }

    private fun fieldValue(target: Any?, vararg names: String): Any? {
        if (target == null) return null
        for (name in names) {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    return runCatching { field.get(target) }.getOrNull()
                }
                type = type.superclass
            }
        }
        return null
    }

    private fun cacheLocalBook(book: Any) {
        runCatching {
            val id = callLong(book, "getId")
            val cloudId = callLong(book, "getCloudId")
            if (id > 0) localBooksById[id] = book
            if (cloudId > 0) localBooksByCloudId[cloudId] = book
        }
    }

    private fun callLong(target: Any, methodName: String): Long =
        (XposedHelpers.callMethod(target, methodName) as? Number)?.toLong()
            ?: XposedHelpers.callMethod(target, methodName).toString().toLong()

    private fun callString(target: Any, methodName: String): String =
        XposedHelpers.callMethod(target, methodName)?.toString().orEmpty()

    private fun callBoolean(target: Any, methodName: String): Boolean =
        (XposedHelpers.callMethod(target, methodName) as? Boolean)
            ?: XposedHelpers.callMethod(target, methodName).toString().toBoolean()

    private fun targetUnit(): Any? = runCatching {
        XposedHelpers.findClass(KOTLIN_UNIT_CLASS, classLoader)
            .getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
    }.getOrNull()

    private data class DetailContext(
        val bookId: Long,
        val compat: Boolean,
        val viewModelRef: WeakReference<Any>,
        val repositoryRef: WeakReference<Any>,
        // 2.2.0 BookOverview 页的 state 结构与 BookDetails 不同（无 getPublishers()，
        // 封面来自 getRelationPublisher()/getBook()），据此走独立的封面解析分支。
        val isOverview: Boolean = false,
    )

    private data class CoverFixUploadResult(
        val userBookId: Long,
        val coverUrl: String,
    )

    private companion object {
        const val BOOK_DETAILS_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.book.BookDetailsViewModel"
        const val BOOK_OVERVIEW_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.home.BookOverviewViewModel"
        const val BOOKSHELF_SCREEN_CLASS = "app.zhendong.reamicro.ui.home.BookshelfScreenKt"
        const val BOOK_CLASS = "app.zhendong.reamicro.data.db.entity.Book"
        const val ROUTE_BOOK_DETAIL_CLASS = "app.zhendong.reamicro.Route\$BookDetail"
        const val ROUTE_BOOK_OVERVIEW_CLASS = "app.zhendong.reamicro.Route\$BookOverview"
        const val POST_USER_BOOK_REQ_CLASS = "app.zhendong.reamicro.data.res.book.PostUserBookReq"
        const val ENVELOPE_KT_CLASS = "app.zhendong.reamicro.data.res.EnvelopeKt"
        const val BOOKSHELF_FIND_BOOK_BY_ID_METHOD = "findBookById"
        const val BOOKSHELF_SEARCH_METHOD_KEYWORD = "search"
        const val NAV_GRAPH_SCOPE_CLASS = "app.zhendong.reamicro.NavGraphScope"
        const val NAV_CONTROLLER_HOLDER_CLASS = "app.zhendong.reamicro.arch.components.NavControllerHolder"
        const val ANDROIDX_NAV_CONTROLLER_CLASS = "androidx.navigation.NavController"
        const val KOTLIN_UNIT_CLASS = "kotlin.Unit"
        const val KOTLIN_CONTINUATION_CLASS = "kotlin.coroutines.Continuation"
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = "kotlin.coroutines.EmptyCoroutineContext"
        const val KOTLIN_INTRINSICS_CLASS = "kotlin.coroutines.intrinsics.IntrinsicsKt"
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = "kotlin.coroutines.intrinsics.CoroutineSingletons"
        const val KOTLIN_RESULT_KT_CLASS = "kotlin.ResultKt"
        const val LOG_PREFIX = "ReaMicro LSP"
    }
}
