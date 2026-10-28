package com.reamicro.fix.hook

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.reamicro.fix.core.HostAndroidViews
import com.reamicro.fix.discover.DiscoverBook
import com.reamicro.fix.discover.DiscoverLayout
import com.reamicro.fix.discover.DiscoverLoadState
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.discover.DiscoverState
import com.reamicro.fix.hook.settings.FUNCTION1_CLASS

/** B4 的发现页复用原生 View 互操作，避免调用已内联删除的 Row/Column/Spacer 门面。 */
internal fun ReaMicroSettingsHook.renderB4DiscoverContent(innerPaddings: Any, composer: Any) {
    discoverVersionValue()
    val factory = functionProxy("B4DiscoverFactory", FUNCTION1_CLASS) { args ->
        val context = args?.firstOrNull() as? Context ?: error("发现页上下文不可用")
        B4DiscoverView(context, this)
    }
    val update = functionProxy("B4DiscoverUpdate", FUNCTION1_CLASS) { args ->
        (args?.firstOrNull() as? B4DiscoverView)?.refresh()
        targetUnit()
    }
    HostAndroidViews(classLoader).render(factory, pageModifier(innerPaddings), composer, update)
}

private class B4DiscoverView(context: Context, private val hook: ReaMicroSettingsHook) : ScrollView(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var renderedVersion = -1
    private var palette = requireNotNull(EmbeddedHostUi.snapshot(context))

    init {
        isFillViewport = true
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int) = EmbeddedHostUi.dp(context, value.toFloat()).toInt()

    private fun label(value: String, caption: Boolean = false) = TextView(context).apply {
        text = value
        textSize = if (caption) 12f else 16f
        setTextColor(if (caption) palette.captionArgb else palette.roles.getValue("OnBackground"))
        EmbeddedHostUi.uiTypeface(context)?.let { typeface = it }
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun button(value: String, action: () -> Unit) = label(value).apply {
        setTextColor(palette.roles.getValue("Primary"))
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { action() }
    }

    fun refresh() {
        if (DiscoverState.sources.isEmpty() && renderedVersion < 0) {
            DiscoverState.refreshSources(context.applicationContext)
        }
        val nextPalette = requireNotNull(EmbeddedHostUi.snapshot(context))
        if (renderedVersion == DiscoverState.version && nextPalette == palette) return
        palette = nextPalette
        renderedVersion = DiscoverState.version
        content.removeAllViews()
        val activity = hook.activityProvider() ?: return
        val toolbar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        toolbar.addView(button("筛选管理") { hook.openDiscoverConfigDialog(activity) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(button(if (DiscoverState.layout == DiscoverLayout.GRID) "切换列表" else "切换宫格") {
            DiscoverState.toggleLayout(context.applicationContext)
            refresh()
        })
        content.addView(toolbar)
        val source = DiscoverState.sources.firstOrNull { it.source.id == DiscoverState.selection.sourceId }
        if (source == null) {
            content.addView(label("暂无发现内容"))
            content.addView(label("当前书源都没有配置发现页（exploreUrl）", caption = true))
            return
        }
        content.addView(button(source.name + " · " + DiscoverState.selection.kindTitle) {
            hook.openDiscoverKindDialog(activity)
        })
        when (val state = DiscoverState.state) {
            DiscoverLoadState.Idle, DiscoverLoadState.Loading -> content.addView(label("正在加载…"))
            is DiscoverLoadState.Failed -> {
                content.addView(label("加载失败"))
                content.addView(label(state.message, caption = true))
                content.addView(button("重试") { DiscoverState.reload(context.applicationContext) })
            }
            is DiscoverLoadState.Loaded -> {
                val books = state.books.distinctBy { it.key }
                if (books.isEmpty()) content.addView(label("暂无内容"))
                if (DiscoverState.layout == DiscoverLayout.GRID) {
                    books.chunked(3).forEach { rowBooks ->
                        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                        repeat(3) { index ->
                            val view = rowBooks.getOrNull(index)?.let { bookCell(source, it, grid = true) }
                                ?: LinearLayout(context)
                            row.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        }
                        content.addView(row)
                    }
                } else books.forEach { content.addView(bookCell(source, it, grid = false)) }
                if (DiscoverState.hasMore) {
                    content.addView(button(if (DiscoverState.loadingMore) "正在加载更多…" else "加载更多") {
                        if (!DiscoverState.loadingMore) DiscoverState.loadMore(context.applicationContext)
                    })
                }
            }
        }
    }

    private fun bookCell(source: DiscoverSource, book: DiscoverBook, grid: Boolean): LinearLayout {
        val cell = LinearLayout(context).apply {
            orientation = if (grid) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { hook.openDiscoverBook(source, book) }
            contentDescription = book.name
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = HostSurfaceDrawable(palette.contentArgb, palette.borderVariantArgb, dp(4).toFloat(), 0f)
            if (book.coverUrl.isNotBlank()) loadDiscoverCover(this, source.source, book.coverUrl, book.detailUrl)
        }
        if (grid) {
            cell.addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156)))
            cell.addView(label(book.name).apply { maxLines = 4 })
        } else {
            cell.addView(cover, LinearLayout.LayoutParams(dp(72), dp(102)))
            cell.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(book.name))
                addView(label(book.author.ifBlank { "未知作者" }, caption = true))
                addView(label(hook.discoverTagLine(book, source), caption = true))
                if (book.lastChapter.isNotBlank()) addView(label("最新：" + book.lastChapter, caption = true))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return cell
    }
}
