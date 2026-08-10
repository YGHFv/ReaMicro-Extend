package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.association.AssociationSearchService
import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.normalizedAssociationSearchKey
import com.reamicro.fix.association.normalizedAssociationTitleKey
import com.reamicro.fix.association.orderAssociationMatches
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.core.HostClasses
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
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
        hookUpdateUiState()
        hookSearchByThird()
        hookViewModelCleared()
    }

    private fun hookUpdateUiState() {
        runCatching {
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
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook BookPublish updateUiState: ${it.stackTraceToString()}")
        }
    }

    private fun hookSearchByThird() {
        runCatching {
            val viewModelClass = XposedHelpers.findClass(BOOK_PUBLISH_VIEW_MODEL_CLASS, classLoader)
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
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook searchByThird: ${it.stackTraceToString()}")
        }
    }

    private fun hookViewModelCleared() {
        runCatching {
            val viewModelClass = XposedHelpers.findClass(BOOK_PUBLISH_VIEW_MODEL_CLASS, classLoader)
            XposedBridge.hookAllMethods(viewModelClass, "onCleared", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (viewModelRef?.get() !== param.thisObject) return
                    viewModelRef = null
                    clearLatestSearchResults()
                }
            })
        }.onFailure {
            XposedBridge.log("$LOG_PREFIX failed to hook association ViewModel.onCleared: ${it.stackTraceToString()}")
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
        XposedHelpers.callMethod(
            state,
            "copy",
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
                    else XposedHelpers.callMethod(
                        state,
                        "copy",
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
            XposedHelpers.callMethod(
                state,
                "copy",
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
                runCatching { target.javaClass.getMethod(method).invoke(target) as? String }
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
            when (method.name) {
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
