package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import android.app.Activity
import android.widget.Toast
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.LinkedHashMap

class BookDetailsAssociationActionHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
) {
    private val localBooksByCloudId = Collections.synchronizedMap(limitedBookCache())
    private val completedCoverFixes = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > MAX_COMPLETED_COVER_FIXES
        },
    )

    private val overviewContexts = Collections.synchronizedMap(LinkedHashMap<Long, DetailContext>())
    @Volatile private var currentDetailsContext: DetailContext? = null
    @Volatile private var cachedAccountGeneration = Long.MIN_VALUE
    private val pendingRequests = NativeCoverRequestOwnership()

    private val binding get() = requireNotNull(com.reamicro.fix.core.HostBookCoverBindings.current)

    fun install() {
        HookInstallReport.install("BookDetailsAssociationActionHook", "dynamicCoverActions") {
            val contract = binding
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            fun hook(member: java.lang.reflect.Executable, callback: HookCallback) {
                handles += HookRuntime.hookMethod(member, callback)
            }
            try {
                hook(contract.method("sourceClick"), object : HookCallback() {
                    override fun before(param: Call) {
                        param.thisObject?.let { contract.field("sourceBook").get(it) }?.let(::cacheLocalBook)
                    }
                })
                HookRuntime.deoptimize(contract.method("sourceClick"))
                hook(contract.constructor("details"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val vm = param.thisObject ?: return
                        val args = contract.field("args").get(vm) ?: return
                        currentDetailsContext = detailContext(
                            contract.field("args#bookId").getLong(args), contract.field("args#compat").getBoolean(args),
                            vm, contract.field("detailsRepository").get(vm),
                        )
                    }
                })
                hook(contract.constructor("overview"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val vm = param.thisObject ?: return
                        val id = contract.field("overviewId").getLong(vm)
                        val context = detailContext(id, true, vm,
                            contract.field("overviewRepository").get(vm), isOverview = true) ?: return
                        overviewContexts[id] = context
                        currentDetailsContext = context
                    }
                })
                hook(contract.method("applyBook"), object : HookCallback() {
                    override fun before(param: Call) {
                        val vm = param.thisObject ?: return
                        val book = param.args?.firstOrNull() ?: return
                        cacheLocalBook(book)
                        val id = contract.field("book#id").getLong(book)
                        val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return
                        if (id <= 0L || callLong(book, "getUid") != identity.accountId) return
                        val uuid = callString(book, "getUuid")
                        val previous = overviewContexts[id]?.takeIf {
                            it.viewModelRef.get() === vm && AccountHostRuntime.isCurrentStorage(it.identity) &&
                                (it.localBookUuid == null || it.localBookUuid == uuid)
                        }
                        val context = previous?.copy(localBookUuid = uuid) ?: detailContext(id, true, vm,
                            contract.field("overviewRepository").get(vm), isOverview = true,
                            localBookUuid = uuid) ?: return
                        overviewContexts[id] = context
                        if (currentDetailsContext?.viewModelRef?.get() === vm) currentDetailsContext = context
                    }
                })
                hook(contract.method("cleared"), object : HookCallback() {
                    override fun after(param: Call) {
                        val vm = param.thisObject ?: return
                        synchronized(overviewContexts) {
                            overviewContexts.entries.removeAll { it.value.viewModelRef.get() == null || it.value.viewModelRef.get() === vm }
                        }
                        if (currentDetailsContext?.viewModelRef?.get() === vm) clearBookDetailsContext()
                    }
                })
                HookRuntime.deoptimize(contract.method("cleared"))
                hook(contract.method("configureRequest"), object : HookCallback() {
                    override fun before(param: Call) {
                        val callback = param.thisObject ?: return
                        if ((contract.field("configuration.tag").get(callback) as Number).toLong() !=
                            contract.configurationTag()) return
                        val request = contract.field("configuration.request").get(callback)
                        runCatching { pendingRequests.check(request) }.onFailure { param.throwable = it }
                    }
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val callback = param.thisObject ?: return
                        if ((contract.field("configuration.tag").get(callback) as Number).toLong() !=
                            contract.configurationTag()) return
                        runCatching {
                            val request = contract.field("configuration.request").get(callback)
                            if (!pendingRequests.check(request)) return@runCatching
                            val builder = requireNotNull(param.args.firstOrNull())
                            check(contract.field("nativeRequest.body").get(builder) === request) {
                                "Native cover request body changed"
                            }
                        }.onFailure { param.throwable = it }
                    }
                })
                hook(contract.method("defaultRequest"), object : HookCallback() {
                    override fun before(param: Call) {
                        val callback = param.thisObject ?: return
                        runCatching {
                            val builder = contract.defaultRequestBuilder(callback) ?: return@runCatching
                            pendingRequests.check(contract.field("nativeRequest.body").get(builder))
                        }.onFailure { param.throwable = it }
                    }
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val callback = param.thisObject ?: return
                        runCatching {
                            val builder = contract.defaultRequestBuilder(callback) ?: return@runCatching
                            pendingRequests.bind(contract.field("nativeRequest.body").get(builder), builder)
                        }.onFailure { param.throwable = it }
                    }
                })
                HookRuntime.deoptimize(contract.method("configureRequest"))
                HookRuntime.deoptimize(contract.method("defaultRequest"))
                com.reamicro.fix.reload.ModuleResources.cleanup { pendingRequests.clear() }
                hook(com.reamicro.fix.core.HostNavigation.navigateMethod(classLoader), object : HookCallback() {
                    override fun before(param: Call) {
                        val route = param.args?.getOrNull(1)
                        if (!contract.type("route").isInstance(route) && !contract.type("overviewRoute").isInstance(route)) clearBookDetailsContext()
                    }
                })
                hook(com.reamicro.fix.core.HostNavigation.controllerPopMethod(classLoader), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable == null && param.result == true) clearBookDetailsContext()
                    }
                })
            } catch (error: Throwable) {
                handles.asReversed().forEach { handle -> runCatching { handle.unhook() }.onFailure(error::addSuppressed) }
                releaseMemory("cover hook installation failed")
                throw error
            }
        }
    }

    internal fun requestCoverFixFromBarcode(viewModel: Any, book: Any): Boolean {
        val details = currentDetailsContext?.takeIf { it.viewModelRef.get() === viewModel } ?: return false
        if (callLong(book, "getCloudId") != details.bookId) return false
        val localId = callLong(book, "getId").takeIf { it > 0L } ?: return false
        if (!isDetailsCurrent(details) || callLong(book, "getUid") != details.identity.accountId) return false
        cacheLocalBook(book)
        fixAssociationCover(details.copy(localBookId = localId, localBookUuid = callString(book, "getUuid")))
        return true
    }

    fun requestCoverFixForCurrentDetails(expectedBook: Any): Boolean {
        val expectedBookId = callLong(expectedBook, "getId")
        if (expectedBookId <= 0L) return false
        val details = currentDetailsContext?.takeIf { it.bookId == expectedBookId }
            ?: overviewContexts[expectedBookId]?.takeIf { it.viewModelRef.get() != null && it.repositoryRef.get() != null }
            ?: return false
        if (expectedBookId != details.bookId || !isAccountCurrent(details) ||
            callLong(expectedBook, "getUid") != details.identity.accountId) return false
        val uuid = callString(expectedBook, "getUuid")
        if (details.localBookUuid != null && details.localBookUuid != uuid) return false
        currentDetailsContext = details
        cacheLocalBook(expectedBook)
        fixAssociationCover(details.copy(localBookId = expectedBookId, localBookUuid = uuid))
        return true
    }

    fun releaseMemory(reason: String) {
        currentDetailsContext = null
        overviewContexts.clear()
        localBooksByCloudId.clear()
        completedCoverFixes.clear()
        HookRuntime.log("$LOG_PREFIX book details memory released: $reason")
    }

    private fun fixAssociationCover(details: DetailContext) {
        val bookId = details.bookId
        val activity = details.activityRef.get() ?: return
        com.reamicro.fix.reload.ModuleResources.thread {
            val result = runCatching {
                checkDetailsCurrent(details)
                check(details.identity.accountId > 0L && details.identity.token.isNotBlank()) { "请先登录阅微" }
                val repository = details.repositoryRef.get() ?: error("BookRepository unavailable")
                val bookshelf = binding.field("shelf").get(repository) ?: error("BookshelfRepository unavailable")
                val book = findLocalBookForDetails(bookshelf, details) ?: error("Book not found: $bookId")
                checkDetailsCurrent(details)
                val coverUrl = resolveDetailsCoverUrl(repository, details, book)
                checkDetailsCurrent(details)
                if (!isRemoteCoverUrl(coverUrl)) error("Cover unavailable")
                val uuid = callString(book, "getUuid")
                val dedupeKey = "${details.identity.generation}|${details.identity.accountId}|$uuid|$bookId|$coverUrl"
                val previousUpload = withDetailsCurrent(details) {
                    completedCoverFixes.putIfAbsent(dedupeKey, false)
                }
                when (previousUpload) {
                    true -> CoverFixUploadResult(0L)
                    false -> CoverFixUploadResult(0L, pending = true)
                    null -> runCatching {
                            val id = postAssociationCover(repository, book, coverUrl, details)
                            withDetailsCurrent(details) { completedCoverFixes[dedupeKey] = true }
                            CoverFixUploadResult(id)
                        }.onFailure { completedCoverFixes.remove(dedupeKey, false) }.getOrThrow()
                }
            }
            activity.runOnModuleUiThread {
                if (!isDetailsCurrent(details)) return@runOnModuleUiThread
                result.onSuccess { upload ->
                    val message = if (upload.pending) "封面正在上传" else "已上传封面"
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    if (!upload.pending) HookRuntime.logAlways("$LOG_PREFIX association cover uploaded manually: " +
                        "bookId=$bookId, userBookId=${upload.userBookId}")
                }.onFailure { error ->
                    val message = when (error.message) {
                        "Cover unavailable" -> "未找到可上传的封面"
                        "请先登录阅微" -> "请先登录阅微"
                        else -> "封面修复失败"
                    }
                    HookRuntime.log("$LOG_PREFIX manual cover upload failed: ${error.javaClass.simpleName}")
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
            details.viewModelRef.get()?.let { binding.method("currentState").invoke(it) }
        }.getOrNull() ?: return ""
        runCatching {
            binding.property(state, "relationPublisher")?.let { publisher ->
                publisherCoverUrl(listOf(publisher), publisherName).takeIf(::isRemoteCoverUrl)?.let { return it }
            }
        }
        return runCatching {
            binding.property(state, "book")
                ?.let { callString(it, "getCover").trim() }
                ?.takeIf(::isRemoteCoverUrl)
                .orEmpty()
        }.getOrDefault("")
    }

    private fun detailStatePublishers(details: DetailContext): List<Any> =
        runCatching {
            details.viewModelRef.get()
                ?.let { binding.method("currentState").invoke(it) }
                ?.let(::publishersFrom)
                ?: emptyList()
        }.getOrElse {
            HookRuntime.log("$LOG_PREFIX failed to read details publishers: ${it.javaClass.simpleName}")
            emptyList()
        }

    private fun fetchCloudInfoPublishers(repository: Any, details: DetailContext): List<Any> = runCatching {
        publishersFrom(binding.unwrap(invokeSuspendBlocking(binding.method("cloudInfo"), repository, details.bookId, details.compat)))
    }.getOrElse {
        HookRuntime.log("$LOG_PREFIX cloud publishers unavailable: ${it.javaClass.simpleName}")
        emptyList()
    }

    private fun publishersFrom(target: Any?): List<Any> {
        if (target == null) return emptyList()
        return (binding.property(target, "publishers") as? Iterable<*>)?.filterNotNull().orEmpty()
    }

    private fun publisherCoverUrl(publishers: List<Any>, publisherName: String): String {
        val covers = publishers.mapNotNull { publisher ->
            val cover = callString(publisher, "getCover").trim()
            if (!isRemoteCoverUrl(cover)) return@mapNotNull null
            val name = callString(publisher, "getPublisher")
            name.trim() to cover
        }
        return covers.firstOrNull { (name, _) ->
            publisherName.isNotEmpty() && name.equals(publisherName, ignoreCase = true)
        }?.second ?: covers.firstOrNull()?.second.orEmpty()
    }

    private fun findLocalBookForDetails(bookshelf: Any, details: DetailContext): Any? {
        details.localBookId?.let { localId ->
            val book = findLocalBookById(bookshelf, localId) ?: error("Local book no longer exists: $localId")
            check(callLong(book, "getId") == localId && callLong(book, "getUid") == details.identity.accountId &&
                (details.localBookUuid == null || callString(book, "getUuid") == details.localBookUuid)) {
                "Local book identity changed"
            }
            check(details.compat || callLong(book, "getCloudId") == details.bookId) {
                "Book association changed; reopen book details"
            }
            return book
        }
        findLocalBookById(bookshelf, details.bookId)?.let { book ->
            if (callLong(book, "getUid") == details.identity.accountId &&
                (details.compat || callLong(book, "getCloudId") == details.bookId)) return book
        }
        localBooksByCloudId[details.bookId]?.takeIf {
            cachedAccountGeneration == details.identity.generation && callLong(it, "getUid") == details.identity.accountId
        }?.let { return it }
        if (!details.compat) {
            findLocalBookByCloudIdFromSearch(bookshelf, details)?.let { return it }
        }
        return null
    }

    private fun findLocalBookById(bookshelf: Any, bookId: Long): Any? {
        val dao = requireNotNull(binding.field("dao").get(bookshelf))
        return invokeSuspendBlocking(binding.method("find"), dao, bookId)?.also(::cacheLocalBook)
    }

    private fun findLocalBookByCloudIdFromSearch(bookshelf: Any, details: DetailContext): Any? {
        val state = runCatching {
            details.viewModelRef.get()?.let { binding.method("currentState").invoke(it) }
        }.getOrNull()
        val title = state?.let { callString(it, "getTitle") }.orEmpty()
        val author = state?.let { callString(it, "getAuthor") }.orEmpty()
        val queries = listOf(title, author, "").map { it.trim() }.distinct()
        for (query in queries) {
            val book = searchLocalBooks(bookshelf, query)
                .firstOrNull { callLong(it, "getUid") == details.identity.accountId &&
                    callLong(it, "getCloudId") == details.bookId }
            if (book != null) {
                cacheLocalBook(book)
                return book
            }
        }
        return null
    }

    private fun searchLocalBooks(bookshelf: Any, query: String): List<Any> = runCatching {
        val result = binding.unwrap(invokeSuspendBlocking(binding.method("search"), bookshelf, query))
        (result as? Iterable<*>)?.filterNotNull()?.onEach(::cacheLocalBook).orEmpty()
    }.getOrElse {
        HookRuntime.log("$LOG_PREFIX local book search failed: ${it.javaClass.simpleName}")
        emptyList()
    }

    private fun postAssociationCover(
        repository: Any, book: Any, coverUrl: String, details: DetailContext,
    ): Long {
        checkDetailsCurrent(details)
        val contract = binding
        val api = requireNotNull(contract.field("api").get(repository))
        val request = contract.request(callString(book, "getTitle"), callString(book, "getAuthor"),
            callString(book, "getUuid").replace("-", ""), coverUrl)
        val registration = pendingRequests.register(request,
            withCurrent = { action -> withDetailsCurrent(details, action) },
            bind = { builder -> contract.bindAuthorization(builder, details.identity.token) })
        return registration.use {
            checkDetailsCurrent(details)
            val envelope = com.reamicro.fix.core.HostCoroutines.invokeTimed(classLoader, contract.method("post"),
                api, 120_000L, request) ?: error("Cover upload returned no envelope")
            checkDetailsCurrent(details)
            val data = contract.method("data").invoke(null, envelope) ?: error("Cover upload returned no data")
            check(contract.type("response").isInstance(data)) { "Unexpected cover response" }
            contract.field("response#id").getLong(data)
        }
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

    private fun cacheLocalBook(book: Any) {
        runCatching {
            val identity = AccountHostRuntime.storageSnapshot(activityProvider()) ?: return@runCatching
            if (callLong(book, "getUid") != identity.accountId) return@runCatching
            val cloudId = callLong(book, "getCloudId")
            AccountHostRuntime.withCurrentStorage(identity) {
                synchronized(localBooksByCloudId) {
                    if (cachedAccountGeneration != identity.generation) {
                        localBooksByCloudId.clear()
                        completedCoverFixes.clear()
                        cachedAccountGeneration = identity.generation
                    }
                    if (cloudId > 0L) localBooksByCloudId[cloudId] = book
                }
            }
        }
    }

    private fun detailContext(
        id: Long, compat: Boolean, vm: Any, repository: Any?, isOverview: Boolean = false,
        localBookUuid: String? = null,
    ): DetailContext? {
        val activity = activityProvider()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return null
        val identity = AccountHostRuntime.storageSnapshot(activity) ?: return null
        return DetailContext(id, compat, WeakReference(vm), WeakReference(repository), identity,
            WeakReference(activity), isOverview = isOverview, localBookUuid = localBookUuid)
    }

    private fun isAccountCurrent(details: DetailContext): Boolean {
        val activity = details.activityRef.get() ?: return false
        return com.reamicro.fix.reload.ModuleResources.accepting && activityProvider() === activity &&
            !activity.isFinishing && !activity.isDestroyed && AccountHostRuntime.isCurrentStorage(details.identity, activity)
    }

    private fun isDetailsCurrent(details: DetailContext): Boolean =
        isAccountCurrent(details) && details.viewModelRef.get() != null && details.repositoryRef.get() != null &&
            currentDetailsContext?.pageKey === details.pageKey &&
            (!details.isOverview || overviewContexts[details.bookId]?.pageKey === details.pageKey)

    private fun checkDetailsCurrent(details: DetailContext) {
        if (!isDetailsCurrent(details)) throw java.util.concurrent.CancellationException("关联页面或账号已变化，请重新操作")
    }

    private fun <T> withDetailsCurrent(details: DetailContext, action: () -> T): T =
        AccountHostRuntime.withCurrentStorage(details.identity, details.activityRef.get()) {
            checkDetailsCurrent(details)
            action()
        }

    private fun limitedBookCache(): LinkedHashMap<Long, Any> =
        object : LinkedHashMap<Long, Any>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean =
                size > MAX_CACHED_LOCAL_BOOKS
        }

    private fun callLong(target: Any, methodName: String): Long =
        (binding.property(target, propertyName(methodName)) as Number).toLong()

    private fun callString(target: Any, methodName: String): String =
        binding.property(target, propertyName(methodName))?.toString().orEmpty()

    private fun propertyName(getter: String): String = getter.removePrefix("get").replaceFirstChar(Char::lowercaseChar)

    private data class DetailContext(
        val bookId: Long,
        val compat: Boolean,
        val viewModelRef: WeakReference<Any>,
        val repositoryRef: WeakReference<Any>,
        val identity: AccountHostRuntime.Identity,
        val activityRef: WeakReference<Activity>,
        val pageKey: Any = Any(),
        val isOverview: Boolean = false,
        val localBookId: Long? = null,
        val localBookUuid: String? = null,
    )

    private data class CoverFixUploadResult(val userBookId: Long, val pending: Boolean = false)

    private companion object {
        const val MAX_CACHED_LOCAL_BOOKS = 32
        const val MAX_COMPLETED_COVER_FIXES = 64
        const val LOG_PREFIX = "ReaMicro LSP"
    }
}
