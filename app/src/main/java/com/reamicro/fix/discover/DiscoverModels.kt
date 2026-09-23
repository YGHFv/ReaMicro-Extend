package com.reamicro.fix.discover

import com.reamicro.fix.online.OnlineSourceEntry

/**
 * 「发现」页的分类条目。
 *
 * 对齐 Legado 的 `ExploreKind`：源里 `exploreUrl` 用 `分类名::地址` 的换行列表描述，
 * 地址可以是 `http(s)://...`，也可以是 `?type=xxx` 这种相对 baseUrl 的路径。
 */
internal data class DiscoverKind(
    /** 分类显示名，例如「玄幻」「完结榜」。 */
    val title: String,
    /** 原始地址片段，可能带 `{{page}}` 之类的模板变量。 */
    val url: String,
    /** 同一分类可挂多条地址（Legado 用 `-` 前缀续行），这里保留全部。 */
    val urls: List<String> = listOf(url),
) {
    /** 稳定 key：同名分类在不同源下要能区分。 */
    val key: String get() = "$title|${urls.joinToString(",")}"
}

/**
 * 发现页的书籍条目。
 *
 * 直接复用搜索链路的解析产物形态（书名/作者/封面/详情页），只是入口由关键词换成分类地址，
 * 规则由 `ruleSearch` 换成 `ruleExplore`。
 */
internal data class DiscoverBook(
    val name: String,
    val author: String,
    val coverUrl: String,
    val detailUrl: String,
    val intro: String,
    val kind: String = "",
    val lastChapter: String = "",
    val updateTime: String = "",
    val wordCount: String = "",
    /** 连载状态原文，如「完结」「连载中」；解析不到时为空，渲染层会隐藏这一枚标签。 */
    val status: String = "",
    /** 章节数原文，如「480」；渲染层再格式化为「480章」。 */
    val chapterCount: String = "",
) {
    val key: String get() = "$name|$author|$detailUrl"
}

/**
 * 书单的两种排布。
 *
 * 只影响 [DiscoverBook] 列表的渲染方式：列表是「封面 + 书名 + 作者 + 标签」的通栏行，
 * 网格是三列等宽封面块。选择结果按上下文持久化（见 `DiscoverState`）。
 */
internal enum class DiscoverLayout {
    /** 通栏行，信息更全，适合追更。 */
    LIST,

    /** 三列网格，同屏信息更多，适合找书。 */
    GRID,
    ;

    /** 与 [DiscoverLayout] 之间互相切换。 */
    fun toggled(): DiscoverLayout = if (this == LIST) GRID else LIST

    companion object {
        /** 从持久化值还原，未知值一律回退到列表。 */
        fun fromStorage(value: String?): DiscoverLayout =
            entries.firstOrNull { it.name == value } ?: LIST
    }
}

/** 一次分类书单的加载结果。 */
internal sealed class DiscoverLoadState {
    /** 尚未加载。 */
    object Idle : DiscoverLoadState()

    /** 加载中。 */
    object Loading : DiscoverLoadState()

    /** 加载成功。 */
    data class Loaded(val books: List<DiscoverBook>) : DiscoverLoadState()

    /** 加载失败，`message` 直接展示给用户。 */
    data class Failed(val message: String) : DiscoverLoadState()
}

/**
 * 一个书源在发现页里的完整条目：源本身 + 解析出来的分类列表。
 *
 * 源没有 `exploreUrl` 时 [kinds] 为空，发现页会跳过该源而不是展示一个空壳。
 * [filter] 是「多重标签筛选」的解析结果（晚风里那类 `@js:` 聚合源），为 null 表示
 * 该源只有平铺分类，配置弹窗里不显示筛选区。
 */
internal data class DiscoverSource(
    val source: OnlineSourceEntry,
    val kinds: List<DiscoverKind>,
    val filter: DiscoverFilter? = null,
) {
    val name: String get() = source.name
    val hasKinds: Boolean get() = kinds.isNotEmpty()
}

/**
 * 多重筛选里的一个可选项：显示名 + 该选项贡献的查询参数（**未编码**的键值对）。
 *
 * 参数之所以按「每组一个维度键」裁剪而不是整条目照搬：聚合源的 groups 条目普遍带
 * `sort=cache_desc` 这类附带默认值（那是平铺标签行的语义——点「起点」就是「按缓存最多看起点」），
 * 但组合筛选的语义是源脚本里 `search({sort,platform,category,tag})`——每个维度只贡献自己的
 * 那一个键。照搬整条目会让后选的组把先选组的 sort 覆盖掉。
 */
internal data class DiscoverFilterOption(
    val title: String,
    val params: List<Pair<String, String>>,
)

/** 多重筛选的一个维度（如「排序」「平台」「标签」）。 */
internal data class DiscoverFilterGroup(
    val name: String,
    val options: List<DiscoverFilterOption>,
)

/**
 * 多重标签筛选的完整解析结果。
 *
 * 来自 Legado「JS 发现页」脚本：`var groups=[…]` 给出各维度与选项，`search(params)`
 * 给出地址前缀与固定追加参数（[prefix] / [extras]）。组合规则与脚本一致：各组选中项的
 * 参数按组序合并（同键后者胜、空值跳过），再追加 [extras]，整体拼到 [prefix] 后面。
 */
internal data class DiscoverFilter(
    val prefix: String,
    val extras: List<String>,
    val groups: List<DiscoverFilterGroup>,
) {
    /** 每组的默认选中：第一个选项（排序组是第一项，其余组是「全部」）。 */
    fun defaultSelection(): Map<String, String> =
        groups.associate { group -> group.name to group.options.first().title }

    /**
     * 把一份「组名 → 选项标题」的选中折成可请求的分类地址。
     *
     * 与脚本 `search()` 的 `for...in` 键序一致：按组序展开选中项参数，同键后者覆盖前者
     * （保持首次出现的位置），空值跳过，最后拼 [extras]。
     */
    fun buildUrl(selection: Map<String, String>): String {
        val keys = mutableListOf<String>()
        val values = LinkedHashMap<String, String>()
        groups.forEach { group ->
            val title = selection[group.name] ?: return@forEach
            val option = group.options.firstOrNull { it.title == title } ?: return@forEach
            option.params.forEach { (key, value) ->
                if (key.isBlank() || value.isBlank()) return@forEach
                if (key !in values) keys += key
                values[key] = value
            }
        }
        val parts = keys.map { key -> "$key=${encodeFilterValue(values.getValue(key))}" } + extras
        return prefix + parts.joinToString("&")
    }

    /**
     * 合成分类的显示名：取各组**非默认**选项的标题拼接；全是默认时叫「组合筛选」。
     *
     * 默认项（各组第一个）不参与命名——「全部」这类词堆出来既没有信息量也超长。
     */
    fun selectionTitle(selection: Map<String, String>): String {
        val picked = groups.mapNotNull { group ->
            val title = selection[group.name] ?: return@mapNotNull null
            if (title == group.options.firstOrNull()?.title) return@mapNotNull null
            title
        }
        return (if (picked.isEmpty()) FILTER_FALLBACK_TITLE else picked.joinToString("·"))
            .take(MAX_FILTER_KIND_TITLE)
    }
}

private const val FILTER_FALLBACK_TITLE = "组合筛选"

/** 合成分类名的截断长度，与 [DiscoverKind] 的分类名上限一致。 */
private const val MAX_FILTER_KIND_TITLE = 20

/** 与 JS `encodeURIComponent` 对齐：`URLEncoder` 会把空格编成 `+`，这里换回 `%20`。 */
private fun encodeFilterValue(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

/** 当前发现页的选中状态：哪个源的哪个分类。 */
internal data class DiscoverSelection(
    val sourceId: String,
    val kindTitle: String,
) {
    companion object {
        val NONE = DiscoverSelection("", "")
    }
}
