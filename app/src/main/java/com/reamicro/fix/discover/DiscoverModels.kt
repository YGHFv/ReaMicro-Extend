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
 */
internal data class DiscoverSource(
    val source: OnlineSourceEntry,
    val kinds: List<DiscoverKind>,
) {
    val name: String get() = source.name
    val hasKinds: Boolean get() = kinds.isNotEmpty()
}

/** 当前发现页的选中状态：哪个源的哪个分类。 */
internal data class DiscoverSelection(
    val sourceId: String,
    val kindTitle: String,
) {
    companion object {
        val NONE = DiscoverSelection("", "")
    }
}
