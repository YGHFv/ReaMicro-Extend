package com.reamicro.fix.hook

import android.app.Activity
import com.reamicro.fix.association.AssociationSearchService
import com.reamicro.fix.association.model.BookSearchResult
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.provider.AssociationSearchProviderRegistry
import com.reamicro.fix.association.provider.ExternalSourceLoader
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
        hookSearchByThird()
        hookViewModelCleared()
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
                        if (hasManualEditFeature()) return
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
        if (keyword.isBlank()) {
            clearLatestSearchResults()
            injectSearchGroups(viewModel)
            return
        }
        val generation = searchGeneration.incrementAndGet()
        synchronized(searchResultsLock) {
            latestSearchResults = emptyList()
        }
        injectSearchGroups(viewModel)
        associationSearchService.searchProgressively(
            keyword = keyword,
            limitPerSource = 3,
            maxWaitMs = SOURCE_SEARCH_WAIT_TIMEOUT_MS,
            onProviderResults = { source, results, elapsedMs ->
                if (generation != searchGeneration.get()) return@searchProgressively
                synchronized(searchResultsLock) {
                    latestSearchResults = (latestSearchResults + results).distinctBy { it.stableId }
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
                }
            },
        )
    }

    private fun clearLatestSearchResults() {
        searchGeneration.incrementAndGet()
        synchronized(searchResultsLock) {
            latestSearchResults = emptyList()
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
        latestSearchResults
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
        val current = (XposedHelpers.callMethod(state, "getThirdPartyBooks") as? List<*>) ?: emptyList<Any>()
        val filtered = current.filter { thirdParty ->
            val publisher = runCatching {
                XposedHelpers.callMethod(thirdParty, "getPublisher") as? String
            }.getOrNull()
            !isSearchGroupPublisher(publisher)
        }
        val withSearchGroups = ArrayList<Any>(filtered.size + groups.size)
        groups.forEach { group ->
            withSearchGroups.add(
                thirdPartyBookFactory.createThirdPartyGroup(
                    publisher = group.publisher,
                    books = group.books,
                ),
            )
        }
        filtered.filterNotNullTo(withSearchGroups)
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

    private fun readBookTitle(viewModel: Any): String = runCatching {
        val state = XposedHelpers.callMethod(viewModel, "getCurrentState")
        val book = XposedHelpers.callMethod(state, "getBook")
        firstNonBlankString(book, "getTitle", "getName", "getBookName")
    }.getOrElse {
        XposedBridge.log("$LOG_PREFIX failed to read association keyword: ${it.message}")
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
                "toString" -> "ReaMicroAssociationSearchReducer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === (args as? Array<*>)?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun hasManualEditFeature(): Boolean =
        ExternalSourceLoader.loadFeatures(activityProvider()?.applicationContext)
            .any { feature -> "manual_edit" in feature.capabilities }

    private fun groupPublisher(sourceName: String): String =
        "$SEARCH_GROUP_PREFIX${sourceName.ifBlank { "未知来源" }}"

    private fun isSearchGroupPublisher(publisher: String?): Boolean =
        publisher?.startsWith(SEARCH_GROUP_PREFIX) == true

    private data class SearchGroup(
        val publisher: String,
        val books: List<Any>,
    )

    private companion object {
        const val BOOK_PUBLISH_VIEW_MODEL_CLASS = "app.zhendong.reamicro.ui.book.BookPublishViewModel"
        const val KOTLIN_FUNCTION1_CLASS = "kotlin.jvm.functions.Function1"
        const val SEARCH_GROUP_PREFIX = "关联补全-"
        const val SOURCE_SEARCH_WAIT_TIMEOUT_MS = 4_500L
        const val LOG_PREFIX = "ReaMicro LSP"
    }
}
