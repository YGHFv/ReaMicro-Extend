package com.reamicro.fix.hook

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostClassOrNull
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostAbiField
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostMethod

import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.typeMatches

import android.app.Activity
import android.widget.Toast
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.core.HostNativeAbi
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch

class BookDetailsAssociationActionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {
    private val localBooksById = Collections.synchronizedMap(limitedBookCache())
    private val localBooksByCloudId = Collections.synchronizedMap(limitedBookCache())
    private val completedCoverFixes = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > MAX_COMPLETED_COVER_FIXES
        },
    )

    private val overviewContexts = Collections.synchronizedMap(LinkedHashMap<Long, DetailContext>())
    @Volatile private var currentDetailsContext: DetailContext? = null

    fun install() {
        hookBookshelfDetailNavigation()
        hookBookDetailsViewModel()
        hookBookOverviewViewModel()
        hookNavGraphScope()
        hookNavigationBackCleanup()
    }

    internal fun requestCoverFixFromBarcode(viewModel: Any, book: Any): Boolean {
        val details = currentDetailsContext?.takeIf { it.viewModelRef.get() === viewModel } ?: return false
        if (callLong(book, "getCloudId") != details.bookId) return false
        val localId = callLong(book, "getId").takeIf { it > 0L } ?: return false
        cacheLocalBook(book)
        fixAssociationCover(details.copy(localBookId = localId))
        return true
    }

    fun requestCoverFixForCurrentDetails(expectedBook: Any): Boolean {
        val expectedBookId = callLong(expectedBook, "getId")
        if (expectedBookId <= 0L) return false
        val details = currentDetailsContext?.takeIf { it.bookId == expectedBookId }
            ?: overviewContexts[expectedBookId]?.takeIf { it.viewModelRef.get() != null && it.repositoryRef.get() != null }
            ?: return false
        if (expectedBookId != details.bookId) return false
        cacheLocalBook(expectedBook)
        fixAssociationCover(details.copy(localBookId = expectedBookId))
        return true
    }

    fun releaseMemory(reason: String) {
        currentDetailsContext = null
        overviewContexts.clear()
        localBooksById.clear()
        localBooksByCloudId.clear()
        completedCoverFixes.clear()
        XposedBridge.log("$LOG_PREFIX book details memory released: $reason")
    }

    private fun hookBookshelfDetailNavigation() {
        runCatching {
            val screenClass = XposedHelpers.findClass(BOOKSHELF_SCREEN_CLASS, classLoader)
            val methods = screenClass.declaredMethods.filter { method ->
                method.parameterTypes.size == 4 &&
                    typeMatches(method.parameterTypes.lastOrNull(), BOOK_CLASS)
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
        HookInstallReport.install("BookDetailsAssociationActionHook", "hookBookDetailsViewModel") {
            val viewModelClass = XposedHelpers.findClass(BOOK_DETAILS_VIEW_MODEL_CLASS, classLoader)
            XposedBridge.hookAllConstructors(viewModelClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val viewModel = param.thisObject ?: return
                    val args = fieldValue(viewModel, "args") ?: param.args?.getOrNull(0) ?: return
                    val repository = fieldValue(viewModel, "bookRepository", "repository") ?: param.args?.getOrNull(1) ?: return
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
            hookViewModelCleared(viewModelClass) { viewModel ->
                synchronized(overviewContexts) {
                    overviewContexts.entries.removeAll { it.value.viewModelRef.get() == null || it.value.viewModelRef.get() === viewModel }
                }
                if (currentDetailsContext?.viewModelRef?.get() === viewModel) {
                    clearBookDetailsContext()
                }
            }
        }
    }

    private fun hookBookOverviewViewModel() {
        HookInstallReport.install("BookDetailsAssociationActionHook", "hookBookOverviewViewModel") {
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
                    currentDetailsContext?.let { overviewContexts[bookId] = it }
                    XposedBridge.log("$LOG_PREFIX book overview association context captured: bookId=$bookId")
                }
            })
            XposedBridge.hookAllMethods(viewModelClass, "applyBook", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val book = param.args?.getOrNull(0) ?: return
                    cacheLocalBook(book)
                    val vm = param.thisObject ?: return
                    val repository = fieldValue(vm, "bookRepository") ?: return
                    val id = callLong(book, "getId")
                    if (id > 0L) overviewContexts[id] = DetailContext(id, true, WeakReference(vm), WeakReference(repository), isOverview = true)
                }
            })
            hookViewModelCleared(viewModelClass) { viewModel ->
                synchronized(overviewContexts) {
                    overviewContexts.entries.removeAll { it.value.viewModelRef.get() == null || it.value.viewModelRef.get() === viewModel }
                }
                if (currentDetailsContext?.viewModelRef?.get() === viewModel) {
                    clearBookDetailsContext()
                }
            }
            XposedBridge.log("$LOG_PREFIX book overview viewModel hook installed")
        }
    }

    private fun hookNavGraphScope() {
        val navigation = HostNativeAbi.navigation(HostSymbols.profileId)
        if (navigation != null) {
            HookInstallReport.install("BookDetailsAssociationActionHook", "nativeNavigationCleanup") {
                val scope = classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS)
                XposedBridge.hookMethod(
                    scope.getDeclaredMethod(navigation.navigate, scope, Any::class.java),
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val routeType = param.args?.getOrNull(1)?.javaClass
                            if (!typeMatches(routeType, ROUTE_BOOK_DETAIL_CLASS) &&
                                !typeMatches(routeType, ROUTE_BOOK_OVERVIEW_CLASS)) {
                                clearBookDetailsContext()
                            }
                        }
                    },
                )
            }
            return
        }
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
        val navigation = HostNativeAbi.navigation(HostSymbols.profileId)
        if (navigation != null) {
            HookInstallReport.install("BookDetailsAssociationActionHook", "nativeBackCleanup") {
                val controller = classLoader.hostClass("ReaMicroHost.NavController")
                XposedBridge.hookMethod(controller.getDeclaredMethod(navigation.controllerPop), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.throwable == null && param.result == true) clearBookDetailsContext()
                    }
                })
            }
            return
        }
        if (classLoader.hostClassOrNull(NAV_CONTROLLER_HOLDER_CLASS) != null) {
            runCatching {
                val holderClass = classLoader.hostClass(NAV_CONTROLLER_HOLDER_CLASS)
                XposedBridge.hookAllMethods(holderClass, "popBack", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        clearBookDetailsContext()
                    }
                })
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to hook NavControllerHolder popBack: ${it.stackTraceToString()}")
            }
        } else {
            XposedBridge.log("$LOG_PREFIX NavControllerHolder unavailable; using NavGraphScope cleanup")
        }
        if (classLoader.hostClassOrNull(ANDROIDX_NAV_CONTROLLER_CLASS) != null) {
            runCatching {
                val navControllerClass = classLoader.hostClass(ANDROIDX_NAV_CONTROLLER_CLASS)
                XposedBridge.hookAllMethods(navControllerClass, "popBackStack", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result != false) clearBookDetailsContext()
                    }
                })
            }.onFailure {
                XposedBridge.log("$LOG_PREFIX failed to hook NavController popBackStack: ${it.stackTraceToString()}")
            }
        } else {
            XposedBridge.log("$LOG_PREFIX AndroidX NavController unavailable; using NavGraphScope cleanup")
        }
    }

    private fun fixAssociationCover(details: DetailContext) {
        val bookId = details.bookId
        val activity = activityProvider()
        Thread {
            val result = runCatching {

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
                        XposedBridge.logAlways(
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

        if (details.isOverview) {
            overviewStateCoverUrl(details, publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
        } else {
            publisherCoverUrl(detailStatePublishers(details), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
            publisherCoverUrl(fetchCloudInfoPublishers(repository, details), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
        }
        return callString(book, "getCover").trim().takeIf(::isRemoteCoverUrl).orEmpty()
    }

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
        if (HostSymbols.abiProfileId == "2.3.3-b4") {
            val method = repository.javaClass.hostAbiMethod(
                "j", Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                classLoader.hostClass("ReaMicroHost.SuspendContinuation"),
            )
            return publishersFrom(unwrapKotlinResult(invokeSuspendBlocking(method, repository, details.bookId, details.compat)))
        }
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
        if (HostSymbols.abiProfileId == "2.3.3-b4" &&
            classLoader.hostClass("ReaMicroHost.CloudBookInfo").isInstance(target)) {
            return (target.javaClass.hostAbiField("f").get(target) as List<*>).filterNotNull()
        }
        return runCatching {
            (XposedHelpers.callMethod(target, "getPublishers") as? Iterable<*>)
                ?.mapNotNull { it }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun publisherCoverUrl(publishers: List<Any>, publisherName: String): String {
        val covers = publishers.mapNotNull { publisher ->
            val b4 = HostSymbols.abiProfileId == "2.3.3-b4" &&
                classLoader.hostClass("ReaMicroHost.BookPublisherRecord").isInstance(publisher)
            val cover = if (b4) (publisher.javaClass.hostAbiField("b").get(publisher) as String).trim()
                else callString(publisher, "getCover").trim()
            if (!isRemoteCoverUrl(cover)) return@mapNotNull null
            val name = if (b4) publisher.javaClass.hostAbiField("d").get(publisher) as String
                else callString(publisher, "getPublisher")
            name.trim() to cover
        }
        return covers.firstOrNull { (name, _) ->
            publisherName.isNotEmpty() && name.equals(publisherName, ignoreCase = true)
        }?.second ?: covers.firstOrNull()?.second.orEmpty()
    }

    private fun findLocalBookForDetails(bookshelf: Any, details: DetailContext): Any? {
        details.localBookId?.let { localId ->
            val book = findLocalBookById(bookshelf, localId) ?: error("Local book no longer exists: $localId")
            check(callLong(book, "getId") == localId) { "Local book identity changed" }
            check(details.compat || callLong(book, "getCloudId") == details.bookId) {
                "Book association changed; reopen book details"
            }
            return book
        }
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
        if (HostSymbols.abiProfileId in setOf("2.3.3-b2", "2.3.3-b4")) {
            val dao = requireNotNull(bookshelf.javaClass.hostField("bookDao").get(bookshelf))
            val find = dao.javaClass.hostMethod(
                if (HostSymbols.abiProfileId == "2.3.3-b4") "b" else "findById", Long::class.javaPrimitiveType,
                classLoader.hostClass("kotlin.coroutines.Continuation"))
            return invokeSuspendBlocking(find, dao, bookId)?.also(::cacheLocalBook)
        }
        val method = (bookshelf.javaClass.methods.asSequence() + bookshelf.javaClass.declaredMethods.asSequence())
            .firstOrNull {
                it.hostNameMatches(BOOKSHELF_FIND_BOOK_BY_ID_METHOD) &&
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
        val api = if (HostSymbols.abiProfileId == "2.3.3-b4") {
            repository.javaClass.hostAbiField("h").get(repository)
        } else fieldValue(repository, "api") ?: error("Api unavailable")
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
        if (HostSymbols.abiProfileId == "2.3.3-b4") {
            val post = api.javaClass.getDeclaredMethod(
                "a1", requestClass, classLoader.hostClass("ReaMicroHost.SuspendContinuation"),
            )
            val envelope = invokeSuspendBlocking(post, api, request)
            val data = classLoader.hostClass("ReaMicroHost.EnvelopeData").hostAbiMethod(
                "b", requireNotNull(envelope).javaClass,
            ).invoke(null, envelope)
            return requireNotNull(data).javaClass.getDeclaredField("a").getLong(data)
        }
        val method = (api.javaClass.methods.asSequence() + api.javaClass.declaredMethods.asSequence())
            .first {
                it.hostNameMatches("postUserBook") &&
                    it.parameterTypes.size == 2 &&
                    typeMatches(it.parameterTypes[0], POST_USER_BOOK_REQ_CLASS)
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
            it.hostNameMatches("dataOrThrow") && it.parameterTypes.size == 1
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

    private fun invokeSuspendBlocking(method: Method, target: Any?, vararg args: Any?): Any? =
        com.reamicro.fix.core.HostCoroutines.invoke(classLoader, method, target, *args)

    private fun emptyCoroutineContext(): Any =
        XposedHelpers.findClass(KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS, classLoader)
            .hostField("INSTANCE")
            .apply { isAccessible = true }
            .get(null) ?: error("EmptyCoroutineContext unavailable")

    private fun coroutineSuspended(): Any =
        runCatching {
            XposedHelpers.findClass(KOTLIN_INTRINSICS_CLASS, classLoader).methods.first {
                it.hostNameMatches("getCOROUTINE_SUSPENDED") && it.parameterTypes.isEmpty()
            }.apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            (XposedHelpers.findClass(KOTLIN_COROUTINE_SINGLETONS_CLASS, classLoader).enumConstants ?: emptyArray())
                .first { value -> value.toString() == "COROUTINE_SUSPENDED" }
        }

    private fun unwrapKotlinResult(value: Any?): Any? {
        val throwOnFailure = XposedHelpers.findClass(KOTLIN_RESULT_KT_CLASS, classLoader).declaredMethods.first {
            it.hostNameMatches("throwOnFailure") && it.parameterTypes.size == 1
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
                val field = runCatching { type.hostField(name) }.getOrNull()
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

    private fun limitedBookCache(): LinkedHashMap<Long, Any> =
        object : LinkedHashMap<Long, Any>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean =
                size > MAX_CACHED_LOCAL_BOOKS
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
            .hostField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
    }.getOrNull()

    private data class DetailContext(
        val bookId: Long,
        val compat: Boolean,
        val viewModelRef: WeakReference<Any>,
        val repositoryRef: WeakReference<Any>,

        val isOverview: Boolean = false,
        val localBookId: Long? = null,
    )

    private data class CoverFixUploadResult(
        val userBookId: Long,
        val coverUrl: String,
    )

    private companion object {
        const val BOOK_DETAILS_VIEW_MODEL_CLASS = HostClasses.Host.BOOK_DETAILS_VIEW_MODEL
        const val BOOK_OVERVIEW_VIEW_MODEL_CLASS = HostClasses.Host.BOOK_OVERVIEW_VIEW_MODEL
        const val BOOKSHELF_SCREEN_CLASS = HostClasses.Host.BOOKSHELF_SCREEN
        const val BOOK_CLASS = HostClasses.Host.BOOK
        const val ROUTE_BOOK_DETAIL_CLASS = "app.zhendong.reamicro.Route\$BookDetail"
        const val ROUTE_BOOK_OVERVIEW_CLASS = "app.zhendong.reamicro.Route\$BookOverview"
        const val POST_USER_BOOK_REQ_CLASS = HostClasses.Host.POST_USER_BOOK_REQ
        const val ENVELOPE_KT_CLASS = HostClasses.Host.ENVELOPE_KT
        const val BOOKSHELF_FIND_BOOK_BY_ID_METHOD = "findBookById"
        const val BOOKSHELF_SEARCH_METHOD_KEYWORD = "search"
        const val NAV_GRAPH_SCOPE_CLASS = HostClasses.Host.NAV_GRAPH_SCOPE
        const val NAV_CONTROLLER_HOLDER_CLASS = HostClasses.Host.NAV_CONTROLLER_HOLDER
        const val ANDROIDX_NAV_CONTROLLER_CLASS = HostClasses.AndroidX.NAV_CONTROLLER
        const val KOTLIN_UNIT_CLASS = HostClasses.Kotlin.KOTLIN_UNIT
        const val KOTLIN_CONTINUATION_CLASS = HostClasses.Kotlin.KOTLIN_CONTINUATION
        const val KOTLIN_EMPTY_COROUTINE_CONTEXT_CLASS = HostClasses.Kotlin.KOTLIN_EMPTY_COROUTINE_CONTEXT
        const val KOTLIN_INTRINSICS_CLASS = HostClasses.Kotlin.KOTLIN_INTRINSICS
        const val KOTLIN_COROUTINE_SINGLETONS_CLASS = HostClasses.Kotlin.KOTLIN_COROUTINE_SINGLETONS
        const val KOTLIN_RESULT_KT_CLASS = HostClasses.Kotlin.KOTLIN_RESULT_KT
        const val MAX_CACHED_LOCAL_BOOKS = 32
        const val MAX_COMPLETED_COVER_FIXES = 64
        const val LOG_PREFIX = "ReaMicro LSP"
    }
}
