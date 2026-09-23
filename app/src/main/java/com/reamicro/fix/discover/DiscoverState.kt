package com.reamicro.fix.discover

import android.content.Context
import com.reamicro.fix.online.OnlineSourceStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「发现」页的状态与后台加载。
 *
 * Compose 侧只读 [version] 触发重组、再拉 [snapshot] 取数据，和模块其它注入页一贯的
 * 「版本号 + 快照」模式一致（见 `profileBackgroundVersionValue` 一类实现）。
 *
 * 加载走单线程池串行执行：书源普遍有并发限流（`concurrentRate`），并发请求既容易被
 * 封也拿不到更快的首屏；串行还能保证 [setState] 的写入顺序与用户点击顺序一致。
 */
internal object DiscoverState {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ReaMicroDiscover").apply { isDaemon = true }
    }

    /** 每次状态变化自增，Compose 侧读它建立依赖。 */
    @Volatile
    var version: Int = 0
        private set

    /** 已解析出分类的源列表（顺序与 [OnlineSourceStore.list] 一致）。 */
    @Volatile
    var sources: List<DiscoverSource> = emptyList()
        private set

    /** 当前选中的「源 + 分类」。 */
    @Volatile
    var selection: DiscoverSelection = DiscoverSelection.NONE
        private set

    /** 当前选中分类的加载状态。 */
    @Volatile
    var state: DiscoverLoadState = DiscoverLoadState.Idle
        private set

    /**
     * 书单排布（列表 / 网格）。
     *
     * 属于用户偏好而不是会话状态，因此落到 SharedPreferences；读取时机与书源列表一致
     * （见 [refreshSources]），避免在 App 启动早期就去碰 Context。
     */
    @Volatile
    var layout: DiscoverLayout = DiscoverLayout.LIST
        private set

    /** 源列表是否正在刷新。 */
    @Volatile
    var refreshing: Boolean = false
        private set

    /** 已加载书单的缓存，键是「源 id + 分类 key」，避免来回切标签重复请求。 */
    private val cache = ConcurrentHashMap<String, CachedBooks>()

    /** 单个分类的已加载结果：书单 + 翻到第几页 + 还有没有下一页。 */
    private class CachedBooks(val state: DiscoverLoadState.Loaded, val page: Int, val hasMore: Boolean)

    /** 当前分类是否还有下一页（仅 Loaded 态有意义）。 */
    @Volatile
    var hasMore: Boolean = false
        private set

    /** 是否正在加载下一页。 */
    @Volatile
    var loadingMore: Boolean = false
        private set

    /** 每个分类已翻到的页码。 */
    private val loadedPages = ConcurrentHashMap<String, Int>()

    /** 防止过期请求的结果覆盖新选择：每次选择递增，回调时比对。 */
    private val requestToken = AtomicInteger(0)

    /** 排布偏好是否已经从磁盘读过；读盘只做一次。 */
    @Volatile
    private var layoutRestored = false

    /**
     * 组合筛选生成的合成分类（当前源）。
     *
     * 用户在配置弹窗里「打开当前筛选结果」后生成：以普通分类的身份排在标签行最前、
     * 参与选中与缓存（[selectionKey] 只看「源 + 分类名」，合成分类天然复用整套加载链路）。
     * 换源时按新源已存的筛选选择重建；从没筛过的源为 null。
     */
    @Volatile
    var filterKind: DiscoverKind? = null
        private set

    /** 每个源的「组名 → 选中项标题」，内存态；[persistFilters] 落盘。 */
    private val filterSelectionsBySource = ConcurrentHashMap<String, Map<String, String>>()

    /** 筛选选择是否已经从磁盘读过；读盘只做一次。 */
    @Volatile
    private var filtersRestored = false

    /**
     * 状态变化时的外部通知。
     *
     * UI 侧在第一次组合时注册它，用来把「版本号变了」推成一次 Compose 重组。
     * 之所以需要：`version` 只是普通 `@Volatile` 字段，Compose 追踪不到；只靠组合期间比对
     * 版本号的话，后台线程加载完成后再没有任何东西会触发下一次组合 —— 页面会永远停在
     * 第一帧的「正在加载…」，切标签也没有任何反应。
     */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private fun bump() {
        version += 1
        onChanged?.invoke()
    }

    /** 会话结束时清掉缓存，避免书源更新后一直读到旧书单。 */
    fun invalidate() {
        cache.clear()
        version += 1
    }

    /**
     * 刷新源与分类列表。
     *
     * 只解析 `exploreUrl`，不发网络请求，所以可以直接在调用线程跑完再吐 UI，
     * 不必占用加载线程池。
     */
    fun refreshSources(context: Context?) {
        restoreLayout(context)
        restoreFilters(context)
        val resolved = OnlineSourceStore.list(context)
            .map { source ->
                DiscoverSource(
                    source,
                    DiscoverRepository.parseKinds(source),
                    DiscoverRepository.parseFilter(source),
                )
            }
            .filter { it.hasKinds }
        sources = resolved
        // 之前选中的源/分类可能已经不在了，重新收敛一次。
        val current = resolveSelection(selection, resolved)
        val changed = current != selection
        selection = current
        filterKind = buildFilterKind(current.sourceId)
        val cached = cache[selectionKey(current)]
        state = cached?.state ?: DiscoverLoadState.Idle
        hasMore = cached?.hasMore ?: false
        bump()
        // 首次进入（或选中的分类刚被自动收敛到别的项）时，主动拉一次书单，
        // 否则页面停在 Idle 只会一直显示「正在加载…」。
        if (cached == null && current != DiscoverSelection.NONE && (changed || state == DiscoverLoadState.Idle)) {
            load(current, context)
        }
    }

    /** 选中某个分类；已有缓存就直接用，否则发起加载。 */
    fun select(sourceId: String, kindTitle: String, context: Context?) {
        val next = DiscoverSelection(sourceId, kindTitle)
        selection = next
        val cached = cache[selectionKey(next)]
        if (cached != null) {
            state = cached.state
            hasMore = cached.hasMore
            bump()
            return
        }
        load(next, context)
    }

    /** 强制重新加载当前分类。 */
    fun reload(context: Context?) {
        val current = selection
        if (current == DiscoverSelection.NONE) return
        cache.remove(selectionKey(current))
        load(current, context)
    }

    /**
     * 切换书源。
     *
     * 源与分类是一起选的（[DiscoverSelection] 同时持有两者），换源必须顺带把分类收到新源的
     * 第一个，否则会留下一个「源 A + 源 B 的分类」的越界组合——那边已有的收敛逻辑
     * （[resolveSelection]）只在刷新源列表时跑，点击换源这条路径不经过它。
     */
    fun selectSource(sourceId: String, context: Context?) {
        val entry = sources.firstOrNull { it.source.id == sourceId } ?: return
        val kind = entry.kinds.firstOrNull() ?: return
        filterKind = buildFilterKind(sourceId)
        select(sourceId, kind.title, context)
    }

    // ── 组合筛选 ────────────────────────────────────────────────────────────

    /**
     * 某源当前的筛选选择（「组名 → 选项标题」）。
     *
     * 以该源的默认选择为底、覆盖已存选择；已存的组名/选项在源更新后可能失效，
     * 失效项回落默认，保证 [DiscoverFilter.buildUrl] 拿到的永远是合法组合。
     */
    fun filterSelection(sourceId: String): Map<String, String> {
        val filter = sources.firstOrNull { it.source.id == sourceId }?.filter ?: return emptyMap()
        val stored = filterSelectionsBySource[sourceId].orEmpty()
        return filter.defaultSelection().mapValues { (groupName, default) ->
            val chosen = stored[groupName] ?: return@mapValues default
            val group = filter.groups.firstOrNull { it.name == groupName }
            if (group != null && group.options.any { it.title == chosen }) chosen else default
        }
    }

    /**
     * 应用一份筛选选择：落盘、生成合成分类并选中它（触发加载）。
     *
     * 合成分类的地址由各组选中项的参数按源脚本 `search({sort,platform,…})` 的语义拼出，
     * 加载与翻页完全复用普通分类的链路。
     */
    fun applyFilterSelection(sourceId: String, selections: Map<String, String>, context: Context?) {
        val source = sources.firstOrNull { it.source.id == sourceId } ?: return
        val filter = source.filter ?: return
        val resolved = filter.filterSelectionsResolved(selections)
        filterSelectionsBySource[sourceId] = resolved
        persistFilters(context)
        val kind = buildFilterKind(sourceId) ?: return
        filterKind = kind
        select(sourceId, kind.title, context)
    }

    /**
     * 重置某源的筛选：清掉已存选择与合成分类。
     *
     * 当前正好停在合成分类上时，顺带把选中收敛回该源的第一个平铺分类——
     * 否则标签行的合成分类消失了、书单却还停在筛选结果上。
     */
    fun resetFilterSelection(sourceId: String, context: Context?) {
        filterSelectionsBySource.remove(sourceId)
        persistFilters(context)
        val onFilterKind = selection.sourceId == sourceId && filterKind?.title == selection.kindTitle
        filterKind = null
        if (onFilterKind) {
            val first = sources.firstOrNull { it.source.id == sourceId }?.kinds?.firstOrNull()
            if (first != null) {
                select(sourceId, first.title, context)
                return
            }
        }
        bump()
    }

    /**
     * 标签行/分类弹窗实际要展示的分类列表：平铺分类 + （当前源有筛选时的）合成分类。
     *
     * 合成分类放在**最前**而不是末尾：标签行只容得下前几颗，追加在尾部会永远躲进「▾」
     * 弹窗里，应用筛选后页面上看不到任何已筛指示。
     */
    fun kindsFor(source: DiscoverSource): List<DiscoverKind> {
        val extra = filterKind ?: return source.kinds
        if (selection.sourceId != source.source.id) return source.kinds
        return listOf(extra) + source.kinds
    }

    /** 按已存选择为某源重建合成分类；没筛过或源不支持筛选时为 null。 */
    private fun buildFilterKind(sourceId: String): DiscoverKind? {
        val source = sources.firstOrNull { it.source.id == sourceId } ?: return null
        val filter = source.filter ?: return null
        val stored = filterSelectionsBySource[sourceId] ?: return null
        val resolved = filter.filterSelectionsResolved(stored)
        val url = filter.buildUrl(resolved)
        if (url.isBlank()) return null
        return DiscoverKind(title = filter.selectionTitle(resolved), url = url, urls = listOf(url))
    }

    /** 把选择收敛到当前组/选项集合内（源更新后旧选项可能已不存在）。 */
    private fun DiscoverFilter.filterSelectionsResolved(stored: Map<String, String>): Map<String, String> =
        defaultSelection().mapValues { (groupName, default) ->
            val chosen = stored[groupName] ?: return@mapValues default
            val group = groups.firstOrNull { it.name == groupName }
            if (group != null && group.options.any { it.title == chosen }) chosen else default
        }

    /** 首次拿到 Context 时读一次筛选选择；之后由内存值主导。 */
    private fun restoreFilters(context: Context?) {
        if (filtersRestored) return
        val app = context?.applicationContext ?: return
        filtersRestored = true
        val text = runCatching {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FILTERS, null)
        }.getOrNull() ?: return
        runCatching {
            val root = org.json.JSONObject(text)
            root.keys().forEach { sourceId ->
                val node = root.optJSONObject(sourceId) ?: return@forEach
                val selections = mutableMapOf<String, String>()
                node.keys().forEach { group ->
                    val title = node.optString(group, "")
                    if (group.isNotBlank() && title.isNotBlank()) selections[group] = title
                }
                if (selections.isNotEmpty()) filterSelectionsBySource[sourceId] = selections
            }
        }
    }

    private fun persistFilters(context: Context?) {
        val app = context?.applicationContext ?: return
        runCatching {
            val root = org.json.JSONObject()
            filterSelectionsBySource.forEach { (sourceId, selections) ->
                val node = org.json.JSONObject()
                selections.forEach { (group, title) -> node.put(group, title) }
                root.put(sourceId, node)
            }
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FILTERS, root.toString())
                .apply()
        }
    }

    /** 切换书单排布并持久化；值没变时不触发重组。 */
    fun setLayout(context: Context?, next: DiscoverLayout) {
        if (next == layout) return
        layout = next
        val app = context?.applicationContext
        if (app != null) {
            runCatching {
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAYOUT, next.name)
                    .apply()
            }
        }
        bump()
    }

    /** 列表 ⇄ 网格互切，返回切换后的排布。 */
    fun toggleLayout(context: Context?): DiscoverLayout {
        val next = layout.toggled()
        setLayout(context, next)
        return next
    }

    /** 只在首次拿到 Context 时读一次偏好；之后由内存值主导，避免每次进页面都读盘。 */
    private fun restoreLayout(context: Context?) {
        if (layoutRestored) return
        val app = context?.applicationContext ?: return
        layoutRestored = true
        layout = DiscoverLayout.fromStorage(
            runCatching {
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAYOUT, null)
            }.getOrNull(),
        )
    }

    private fun load(target: DiscoverSelection, context: Context?) {
        val source = sources.firstOrNull { it.source.id == target.sourceId } ?: return
        val kind = resolveKind(source, target.kindTitle) ?: return
        val key = selectionKey(target)
        val token = requestToken.incrementAndGet()
        state = DiscoverLoadState.Loading
        hasMore = false
        loadingMore = false
        loadedPages.remove(key)
        bump()
        executor.execute {
            val result = runCatching { DiscoverRepository.loadBooks(source.source, kind, page = 1) }
                .getOrElse { DiscoverLoadState.Failed(it.message.orEmpty().ifBlank { it.javaClass.simpleName }) }
            if (token != requestToken.get()) return@execute
            val loaded = result as? DiscoverLoadState.Loaded
            if (loaded != null) {
                // 返回非空就默认还有下一页；真翻到空页时「加载更多」会把 hasMore 收掉。
                cache[key] = CachedBooks(loaded, 1, loaded.books.isNotEmpty())
                loadedPages[key] = 1
            }
            // 用户可能已经切走了，只在选择未变时把结果吐给 UI；缓存照旧留着。
            if (selection == target) {
                state = result
                hasMore = loaded != null && loaded.books.isNotEmpty()
                bump()
            }
        }
    }

    /**
     * 加载当前分类的下一页并追加到书单。
     *
     * Legado 的发现页是滚动到底自动翻页，那需要 LazyListState（反射拿不到），所以翻页入口
     * 做成书单末尾的「加载更多」行。失败时保持已加载内容不变，用户可以再点。
     */
    fun loadMore(context: Context?) {
        val target = selection
        if (target == DiscoverSelection.NONE || loadingMore || !hasMore) return
        val loaded = state as? DiscoverLoadState.Loaded ?: return
        val source = sources.firstOrNull { it.source.id == target.sourceId } ?: return
        val kind = resolveKind(source, target.kindTitle) ?: return
        val key = selectionKey(target)
        val nextPage = (loadedPages[key] ?: 1) + 1
        val token = requestToken.incrementAndGet()
        loadingMore = true
        bump()
        executor.execute {
            val more = runCatching { DiscoverRepository.loadBooks(source.source, kind, page = nextPage) }
                .getOrElse { DiscoverLoadState.Failed(it.message.orEmpty().ifBlank { it.javaClass.simpleName }) }
            loadingMore = false
            val fresh = more as? DiscoverLoadState.Loaded
            if (fresh != null && fresh.books.isNotEmpty()) {
                val merged = DiscoverLoadState.Loaded((loaded.books + fresh.books).distinctBy { it.key })
                cache[key] = CachedBooks(merged, nextPage, true)
                loadedPages[key] = nextPage
                if (token == requestToken.get() && selection == target) {
                    state = merged
                    hasMore = true
                    bump()
                }
                return@execute
            }
            // 空页或失败：空页把入口收掉；失败保持 hasMore，用户可再点重试。
            if (fresh != null && token == requestToken.get() && selection == target) {
                hasMore = false
                bump()
            }
        }
    }

    /** 默认选中第一个源的第一个分类，让发现页一进来就有内容。 */
    private fun resolveSelection(
        current: DiscoverSelection,
        available: List<DiscoverSource>,
    ): DiscoverSelection {
        if (available.isEmpty()) return DiscoverSelection.NONE
        val matched = available.firstOrNull { it.source.id == current.sourceId }
        if (matched != null) {
            if (matched.kinds.any { it.title == current.kindTitle }) return current
            // 当前选中的是合成分类：源还在、筛选选择还在就仍然有效。
            if (buildFilterKind(matched.source.id)?.title == current.kindTitle) return current
        }
        val first = available.first()
        return DiscoverSelection(first.source.id, first.kinds.first().title)
    }

    /** 按名取分类：平铺分类优先，其次是当前源的合成分类。 */
    private fun resolveKind(source: DiscoverSource, kindTitle: String): DiscoverKind? =
        source.kinds.firstOrNull { it.title == kindTitle }
            ?: filterKind?.takeIf { source.source.id == selection.sourceId && it.title == kindTitle }

    private fun selectionKey(selection: DiscoverSelection): String =
        "${selection.sourceId}|${selection.kindTitle}"

    /** 排布偏好所在的偏好文件名，与其它模块偏好一样独立一份。 */
    private const val PREFS_NAME = "reamicro_discover"

    private const val KEY_LAYOUT = "book_layout"

    /** 组合筛选选择的持久化 key：JSON `{源 id: {组名: 选项标题}}`。 */
    private const val KEY_FILTERS = "filter_selections_v1"
}
