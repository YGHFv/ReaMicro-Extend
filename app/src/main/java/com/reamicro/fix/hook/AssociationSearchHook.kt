package com.reamicro.fix.hook

import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodOrNull
import com.reamicro.fix.core.HostSymbols


import android.app.Activity
import com.reamicro.fix.association.AssociationSearchService
import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.normalizedAssociationSearchKey
import com.reamicro.fix.association.normalizedAssociationTitleKey
import com.reamicro.fix.association.orderAssociationMatches
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

class AssociationSearchHook(
    private val classLoader: ClassLoader,
    private val activityProvider: () -> Activity?,
    private val settingsProvider: () -> ModuleSettingsSnapshot,
) {
    private val thirdPartyBookFactory = ReaMicroThirdPartyBookFactory(classLoader)
    private val searchGeneration = AtomicInteger(0)
    private val searchResultsLock = Any()
    private var viewModelRef: WeakReference<Any>? = null
    private var publishFlowRef: WeakReference<Any>? = null
    @Volatile private var observedPublishBookKey = ""
    @Volatile private var latestSearchResults: List<BookSearchResult> = emptyList()
    @Volatile private var latestSearchKeyword: String = ""
    @Volatile private var latestSearchAuthor: String = ""
    @Volatile private var holdSearchingGeneration: Int = 0

    private val associationSearchService = AssociationSearchService(
        providersProvider = {
            AssociationSearchProviderRegistry.providers(activityProvider()?.applicationContext)
        },
        enabledSourcesProvider = {
            val snapshot = settingsProvider()
            if (!snapshot.canRunAssociation) {
                emptySet()
            } else {
                val context = activityProvider()?.applicationContext
                snapshot.enabledAssociationSearchSources(
                    AssociationSearchProviderRegistry.searchSourceGroups(context),
                )
            }
        },
        onProviderError = { source, error ->
            XposedBridge.log("$LOG_PREFIX ${source.displayName} search failed: ${error.message}")
        },
    )

    fun install() {
        AssociationNetworkHook.install()
        hookUpdateUiState()
        hookSearchByThird()
        hookViewModelCleared()
    }

    private fun hookUpdateUiState() {
        HookInstallReport.install("AssociationSearchHook", "hookUpdateUiState") {
            val viewModelClass = XposedHelpers.findClass(BOOK_PUBLISH_VIEW_MODEL_CLASS, classLoader)
            val updateMethod = XposedHelpers.findMethodBestMatch(
                viewModelClass,
                "updateUiState",
                targetFunction1 { it },
            )
            XposedBridge.hookMethod(updateMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!viewModelClass.isInstance(param.thisObject)) return
                    val reducer = param.args?.getOrNull(0) ?: return
                    if (reducer.toString() == REDUCER_PROXY_NAME) return
                    param.args[0] = targetFunction1 { state ->
                        val nextState = runCatching {
                            XposedHelpers.callMethod(reducer, "invoke", state)
                        }.getOrElse {
                            XposedBridge.log("$LOG_PREFIX failed to invoke wrapped UiState reducer: ${it.stackTraceToString()}")
                            state
                        }
                        reconcileSearchState(nextState)
                    }
                }
            })
            XposedBridge.log("$LOG_PREFIX BookPublish updateUiState hook installed: ${updateMethod.declaringClass.name}")
        }
    }

    private fun hookSearchByThird() {
        HookInstallReport.install("AssociationSearchHook", "hookSearchByThird") {
            val viewModelClass = XposedHelpers.findClass(BOOK_PUBLISH_VIEW_MODEL_CLASS, classLoader)
            if (viewModelClass.hostMethodOrNull("searchByThird") == null && !HostSymbols.isIdentity) {
                hookPublishStateSearch(viewModelClass)
                return@install
            }
            XposedHelpers.findAndHookMethod(
                viewModelClass,
                "searchByThird",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val viewModel = param.thisObject ?: return
                        viewModelRef = WeakReference(viewModel)
                        val snapshot = settingsProvider()
                        if (!snapshot.canRunAssociation || !snapshot.canRunAssociationSearch) {
                            clearLatestSearchResults()
                            injectSearchGroups(viewModel)
                            return
                        }
                        refreshSearchResults(viewModel)
                    }
                },
            )
            XposedBridge.log("$LOG_PREFIX association search hook installed")
        }
    }

    private fun hookPublishStateSearch(viewModelClass: Class<*>) {
        val stateType = XposedHelpers.findClass("app.zhendong.reamicro.ui.book.BookPublishUIState", classLoader)
        val flowType = XposedHelpers.findClass("kotlinx.coroutines.flow.StateFlowImpl", classLoader)
        fun observer(nextIndex: Int, compareAndSet: Boolean) = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !== publishFlowRef?.get()) return
                val next = param.args?.getOrNull(nextIndex) ?: return
                if (!stateType.isInstance(next)) return
                param.args[nextIndex] = reconcileSearchState(next)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.throwable != null || (compareAndSet && param.result != true)) return
                if (param.thisObject !== publishFlowRef?.get()) return
                val vm = viewModelRef?.get() ?: return
                val next = param.args?.getOrNull(nextIndex) ?: return
                val book = runCatching { XposedHelpers.callMethod(next, "getBook") }.getOrNull() ?: return
                val title = firstNonBlankString(book, "getTitle", "getName")
                val author = firstNonBlankString(book, "getAuthor")
                val key = "$title\u0000$author"
                if (title.isBlank()) return
                synchronized(searchResultsLock) {
                    if (key == observedPublishBookKey) return
                    observedPublishBookKey = key
                }
                activityProvider()?.runOnUiThread {
                    if (viewModelRef?.get() === vm && settingsProvider().canRunAssociationSearch) refreshSearchResults(vm)
                }
            }
        }
        // searchByThird 被内联后，订阅这个 VM 独有的状态流，保留搜索触发与结果注入。
        XposedBridge.hookMethod(flowType.hostMethod("compareAndSet", Any::class.java, Any::class.java), observer(1, true))
        XposedBridge.hookMethod(flowType.hostMethod("setValue", Any::class.java), observer(0, false))
        XposedBridge.hookAllConstructors(viewModelClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val vm = param.thisObject ?: return
                clearLatestSearchResults()
                observedPublishBookKey = ""
                viewModelRef = WeakReference(vm)
                publishFlowRef = WeakReference(XposedHelpers.getObjectField(vm, "_uiState"))
                val title = readBookTitle(vm)
                if (title.isNotBlank()) {
                    observedPublishBookKey = "$title\u0000${readBookAuthor(vm)}"
                    activityProvider()?.runOnUiThread {
                        if (viewModelRef?.get() === vm && settingsProvider().canRunAssociationSearch) refreshSearchResults(vm)
                    }
                }
            }
        })
        XposedBridge.log("$LOG_PREFIX association search state-flow fallback installed")
    }

    private fun hookViewModelCleared() {
        HookInstallReport.install("AssociationSearchHook", "hookViewModelCleared") {
            val viewModelClass = XposedHelpers.findClass(BOOK_PUBLISH_VIEW_MODEL_CLASS, classLoader)
            XposedBridge.hookAllMethods(viewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (viewModelRef?.get() !== param.thisObject) return
                    viewModelRef = null
                    publishFlowRef = null
                    observedPublishBookKey = ""
                    clearLatestSearchResults()
                }
            })
        }
    }

    private fun refreshSearchResults(viewModel: Any) {
        val keyword = readBookTitle(viewModel)
        val author = readBookAuthor(viewModel)
        if (keyword.isBlank()) {
            clearLatestSearchResults()
            injectSearchGroups(viewModel)
            return
        }
        val generation = searchGeneration.incrementAndGet()
        synchronized(searchResultsLock) {
            latestSearchResults = emptyList()
            latestSearchKeyword = keyword
            latestSearchAuthor = author
        }
        holdSearchingGeneration = generation
        injectSearchGroups(viewModel)
        setSearchingState(viewModel, true)
        associationSearchService.searchProgressively(
            keyword = keyword,
            limitPerSource = 10,
            maxWaitMs = SOURCE_SEARCH_WAIT_TIMEOUT_MS,
            onProviderResults = { source, results, elapsedMs ->
                if (generation != searchGeneration.get()) return@searchProgressively
                synchronized(searchResultsLock) {
                    latestSearchResults = (latestSearchResults + results)
                        .distinctBy { it.stableId }
                        .orderAssociationMatches(latestSearchKeyword, latestSearchAuthor)
                }
                activityProvider()?.runOnUiThread {
                    if (generation == searchGeneration.get()) {
                        injectSearchGroups(viewModel)
                    }
                }
                XposedBridge.log("$LOG_PREFIX ${source.displayName} source results: ${results.size} in ${elapsedMs}ms")
            },
            onComplete = { results, elapsedMs ->
                if (generation == searchGeneration.get()) {
                    XposedBridge.log("$LOG_PREFIX association source search window: ${results.size} results in ${elapsedMs}ms")
                    activityProvider()?.runOnUiThread {
                        if (generation == searchGeneration.get()) {
                            holdSearchingGeneration = 0
                            setSearchingState(viewModel, false)
                        }
                    }
                }
            },
        )
    }

    private fun clearLatestSearchResults() {
        searchGeneration.incrementAndGet()
        holdSearchingGeneration = 0
        synchronized(searchResultsLock) {
            latestSearchResults = emptyList()
            latestSearchKeyword = ""
            latestSearchAuthor = ""
        }
    }

    private fun injectSearchGroups(viewModel: Any) {
        runCatching {
            val groups = buildSearchGroups()
            XposedHelpers.callMethod(
                viewModel,
                "updateUiState",
                targetFunction1 { state -> state?.let { appendSearchGroups(it, groups) } },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to inject association source groups: ${it.stackTraceToString()}")
        }
    }

    private fun buildSearchGroups(): List<SearchGroup> {
        val snapshot = settingsProvider()
        if (!snapshot.canRunAssociationSearch) return emptyList()
        val groupedBooks = linkedMapOf<String, MutableList<Any>>()
        val keyword = latestSearchKeyword
        val author = latestSearchAuthor
        latestSearchResults
            .orderAssociationMatches(keyword, author)
            .filter { snapshot.isSearchSourceEnabled(it.source) }
            .forEach { result ->
                runCatching {
                    groupedBooks
                        .getOrPut(groupPublisher(result.source.displayName)) { mutableListOf() }
                        .add(thirdPartyBookFactory.create(result))
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed to build source result: ${it.message}")
                }
            }
        return groupedBooks.map { (publisher, books) -> SearchGroup(publisher, books) }
    }

    private fun appendSearchGroups(state: Any, groups: List<SearchGroup>): Any = runCatching {
        val thirdPartyRaw = XposedHelpers.callMethod(state, "getThirdPartyBooks") as? List<*> ?: emptyList<Any>()
        val thirdPartyNotOurs = thirdPartyRaw.filterNotNull().filter { thirdParty ->
            val publisher = runCatching {
                XposedHelpers.callMethod(thirdParty, "getPublisher") as? String
            }.getOrNull()
            !isSearchGroupPublisher(publisher)
        }
        val searchGroups = groups.map { group ->
                thirdPartyBookFactory.createThirdPartyGroup(
                    publisher = group.publisher,
                    books = group.books,
                )
        }
        val withSearchGroups = (thirdPartyNotOurs + searchGroups).sortThirdPartyGroupsByMatch()
        copyPublishState(
            state,
            XposedHelpers.callMethod(state, "getBook"),
            XposedHelpers.callMethod(state, "getRecommend"),
            withSearchGroups,
            XposedHelpers.callMethod(state, "getSearching"),
            XposedHelpers.callMethod(state, "getRelating"),
            runCatching { XposedHelpers.callMethod(state, "isRelateSuccess") }.getOrElse {
                XposedHelpers.callMethod(state, "getIsRelateSuccess")
            },
        )
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX append association source groups failed: ${it.stackTraceToString()}")
        state
    }

    private fun setSearchingState(viewModel: Any, searching: Boolean) {
        runCatching {
            XposedHelpers.callMethod(
                viewModel,
                "updateUiState",
                targetFunction1 { state ->
                    if (state == null) return@targetFunction1 null
                    val current = runCatching { XposedHelpers.callMethod(state, "getSearching") as? Boolean }
                        .getOrNull() ?: false
                    if (current == searching) state
                    else copyPublishState(
                        state,
                        XposedHelpers.callMethod(state, "getBook"),
                        XposedHelpers.callMethod(state, "getRecommend"),
                        XposedHelpers.callMethod(state, "getThirdPartyBooks"),
                        searching,
                        XposedHelpers.callMethod(state, "getRelating"),
                        runCatching { XposedHelpers.callMethod(state, "isRelateSuccess") }.getOrElse {
                            XposedHelpers.callMethod(state, "getIsRelateSuccess")
                        },
                    )
                },
            )
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to set association searching=$searching: ${it.stackTraceToString()}")
        }
    }

    private fun reconcileSearchState(state: Any?): Any? {
        if (state == null) return state

        var nextState = state
        val shouldInjectGroups = latestSearchKeyword.isNotBlank()
        if (shouldInjectGroups) {
            nextState = appendSearchGroups(nextState, buildSearchGroups())
        }

        val shouldHoldSearching = holdSearchingGeneration == searchGeneration.get() && latestSearchKeyword.isNotBlank()
        if (shouldHoldSearching && readSearching(nextState) == false) {
            nextState = copySearching(nextState, true)
        }
        return nextState
    }

    private fun readSearching(state: Any): Boolean? =
        runCatching { XposedHelpers.callMethod(state, "getSearching") as? Boolean }.getOrNull()

    private fun copySearching(state: Any, searching: Boolean): Any =
        runCatching {
            copyPublishState(
                state,
                XposedHelpers.callMethod(state, "getBook"),
                XposedHelpers.callMethod(state, "getRecommend"),
                XposedHelpers.callMethod(state, "getThirdPartyBooks"),
                searching,
                XposedHelpers.callMethod(state, "getRelating"),
                runCatching { XposedHelpers.callMethod(state, "isRelateSuccess") }.getOrElse {
                    XposedHelpers.callMethod(state, "getIsRelateSuccess")
                },
            )
        }.getOrElse {
            XposedBridge.log("$LOG_PREFIX failed to copy association searching=$searching: ${it.stackTraceToString()}")
            state
        }

    private fun copyPublishState(state: Any, vararg values: Any?): Any {
        val original = runCatching { XposedHelpers.callMethod(state, "copy", *values) }
        if (original.isSuccess) return requireNotNull(original.getOrNull())
        val failure = original.exceptionOrNull()
        if (HostSymbols.isIdentity || (failure !is NoSuchMethodError && failure !is NoSuchMethodException)) {
            throw requireNotNull(failure)
        }
        // R8 删除 full copy 后，六个属性仍保留在主构造器，不能拿 copy$default 的掩码槽冒充。
        val constructor = state.javaClass.declaredConstructors.single { it.parameterTypes.size == values.size }
        constructor.isAccessible = true
        return constructor.newInstance(*values)
    }

    private fun List<Any>.sortThirdPartyGroupsByMatch(): List<Any> {
        val normalizedKeyword = latestSearchKeyword.normalizedAssociationTitleKey()
        val normalizedAuthor = latestSearchAuthor.normalizedAssociationSearchKey()
        if (normalizedKeyword.isBlank() || isEmpty()) return this
        return mapIndexed { index, thirdParty ->
            val publisher = runCatching {
                XposedHelpers.callMethod(thirdParty, "getPublisher") as? String
            }.getOrNull().orEmpty()
            val books = runCatching {
                XposedHelpers.callMethod(thirdParty, "getBooks") as? List<*>
            }.getOrNull().orEmpty().filterNotNull()
            val sortedBooks = books.sortThirdPartyBooksByMatch(normalizedKeyword, normalizedAuthor)
            val sortedGroup = if (sortedBooks == books) {
                thirdParty
            } else {
                thirdPartyBookFactory.createThirdPartyGroup(publisher, sortedBooks)
            }
            val bestRank = sortedBooks.minOfOrNull { book ->
                thirdPartyBookRank(book, normalizedKeyword, normalizedAuthor)
            } ?: Int.MAX_VALUE
            IndexedThirdPartyGroup(index, sortedGroup, bestRank)
        }.sortedWith(
            compareBy<IndexedThirdPartyGroup> { it.bestRank }
                .thenBy { it.index },
        ).map { it.group }
    }

    private fun List<Any>.sortThirdPartyBooksByMatch(
        normalizedKeyword: String,
        normalizedAuthor: String,
    ): List<Any> =
        mapIndexed { index, book -> index to book }
            .sortedWith(
                compareBy<Pair<Int, Any>> { (_, book) ->
                    thirdPartyBookRank(book, normalizedKeyword, normalizedAuthor)
                }.thenBy { it.first },
            )
            .map { it.second }

    private fun thirdPartyBookRank(
        book: Any,
        normalizedKeyword: String,
        normalizedAuthor: String,
    ): Int {
        val title = runCatching {
            XposedHelpers.callMethod(book, "getTitle") as? String
        }.getOrNull().orEmpty()
        val titleMatches = title.normalizedAssociationTitleKey() == normalizedKeyword
        val author = runCatching {
            XposedHelpers.callMethod(book, "getAuthor") as? String
        }.getOrNull().orEmpty()
        val authorMatches = normalizedAuthor.isNotBlank() &&
            author.normalizedAssociationSearchKey() == normalizedAuthor
        return when {
            titleMatches && authorMatches -> 0
            titleMatches -> 1
            authorMatches -> 2
            else -> 3
        }
    }

    private fun readBookTitle(viewModel: Any): String = runCatching {
        val state = XposedHelpers.callMethod(viewModel, "getCurrentState")
        val book = XposedHelpers.callMethod(state, "getBook")
        firstNonBlankString(book, "getTitle", "getName", "getBookName")
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to read association keyword: ${it.message}")
        ""
    }

    private fun readBookAuthor(viewModel: Any): String = runCatching {
        val state = XposedHelpers.callMethod(viewModel, "getCurrentState")
        val book = XposedHelpers.callMethod(state, "getBook")
        firstNonBlankString(book, "getAuthor", "getWriter", "getAuthors")
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to read association author: ${it.message}")
        ""
    }

    private fun firstNonBlankString(target: Any?, vararg methods: String): String {
        if (target == null) return ""
        return methods.asSequence()
            .map { method ->
                runCatching { XposedHelpers.callMethod(target, method) as? String }
                    .getOrNull()
                    .orEmpty()
                    .trim()
            }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun targetFunction1(block: (Any?) -> Any?): Any {
        val f1Class = XposedHelpers.findClass(KOTLIN_FUNCTION1_CLASS, classLoader)
        return Proxy.newProxyInstance(classLoader, arrayOf(f1Class)) { proxy, method, args ->
            when (com.reamicro.fix.core.HostSymbols.logicalMethodName(method)) {
                "invoke" -> block((args as? Array<*>)?.getOrNull(0))
                "toString" -> REDUCER_PROXY_NAME
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === (args as? Array<*>)?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun groupPublisher(sourceName: String): String =
        "$SEARCH_GROUP_PREFIX${sourceName.ifBlank { "未知来源" }}"

    private fun isSearchGroupPublisher(publisher: String?): Boolean =
        publisher?.startsWith(SEARCH_GROUP_PREFIX) == true

    private data class SearchGroup(
        val publisher: String,
        val books: List<Any>,
    )

    private data class IndexedThirdPartyGroup(
        val index: Int,
        val group: Any,
        val bestRank: Int,
    )

    private companion object {
        const val BOOK_PUBLISH_VIEW_MODEL_CLASS = HostClasses.Host.BOOK_PUBLISH_VIEW_MODEL
        const val KOTLIN_FUNCTION1_CLASS = HostClasses.Kotlin.FUNCTION1
        const val SEARCH_GROUP_PREFIX = "关联补全-"
        const val SOURCE_SEARCH_WAIT_TIMEOUT_MS = 4_500L
        const val LOG_PREFIX = "ReaMicro LSP"
        const val REDUCER_PROXY_NAME = "ReaMicroAssociationSearchReducer"
    }
}
