package com.reamicro.fix.online

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.reamicro.fix.discover.DiscoverKind
import com.script.ScriptBindings
import com.script.rhino.runScriptWithContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.help.JsExtensions
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.getExploreInfoMap
import io.legado.app.platform.LegadoPlatform
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.currentCoroutineContext

/** Mirrors SourceLoginViewModel's form/action bindings, without owning another UI or account store. */
internal fun EnhancedOnlineParser.loginForm(source: OnlineSourceEntry): EnhancedSourceForm =
    run(source) { op -> sourceLoginForm(op.upstream, null) }

internal fun EnhancedOnlineParser.loginAction(
    source: OnlineSourceEntry, values: Map<String, String>, key: String, longClick: Boolean,
): EnhancedSourceForm = run(source) { op ->
    val form = sourceLoginForm(op.upstream, values)
    val row = form.rows.firstOrNull { it.key == key } ?: error("书源登录操作已变化")
    val updated = form.values.toMutableMap()
    val java = SourceUiJs(op.upstream, updated, form.rows)
    if (row.action.startsWith("http://") || row.action.startsWith("https://")) {
        LegadoPlatform.adapter.openUrl(row.action, null, source.sourceUrl, source.name)
    } else if (row.action.isNotBlank()) {
        evalSourceUi(op.upstream, "${op.upstream.getLoginJs().orEmpty()}\n${row.action}") {
            put("java", java); put("result", updated.toMutableMap())
            put("book", null); put("chapter", null); put("isLongClick", longClick)
        }
    }
    sourceLoginForm(op.upstream, updated)
}

internal fun EnhancedOnlineParser.submitLogin(source: OnlineSourceEntry, values: Map<String, String>) {
    run(source) { op ->
        if (values.isEmpty()) op.upstream.removeLoginInfo()
        else {
            op.upstream.putLoginInfo(GSON.toJson(values))
            val login = op.upstream.getLoginJs()
            if (login != null) {
                val java = SourceUiJs(op.upstream, values.toMutableMap(), sourceLoginForm(op.upstream, values).rows)
                evalSourceUi(op.upstream, "$login\n" +
                    "if(typeof login=='function'){login.apply(this);}else{throw('Function login not implements!!!')}") {
                    put("java", java); put("result", values.toMutableMap())
                    put("book", null); put("chapter", null); put("isLongClick", false)
                }
            }
        }
    }
}

internal fun EnhancedOnlineParser.browserLogin(source: OnlineSourceEntry, url: String) {
    run(source) { op ->
        val headers = runScriptWithContext(currentCoroutineContext()) {
            op.upstream.getHeaderMap(LegadoPlatform.userAgent, true)
        }
        LegadoPlatform.adapter.browser(LegadoPlatform.BrowserRequest(
            url, title = source.name, headers = headers, sourceKey = source.sourceUrl,
            isRule = true, refetchAfterSuccess = false, timeoutMs = 300_000,
        ))
    }
}

internal fun EnhancedOnlineParser.discoverControls(source: OnlineSourceEntry): EnhancedSourceForm =
    run(source) { op -> discoverForm(op.upstream) }

internal fun EnhancedOnlineParser.exploreControl(
    source: OnlineSourceEntry, key: String, value: String?, longClick: Boolean = false,
): List<DiscoverKind> = run(source) { op ->
    val info = getExploreInfoMap(source.sourceUrl)
    if (value != null) {
        info[key] = value
        info.saveNow()
    }
    val kinds = op.upstream.exploreKinds()
    val selected = kinds.firstOrNull { it.title == key } ?: error("发现分类已变化")
    val java = SourceUiJs(op.upstream, mutableMapOf(), emptyList())
    selected.action?.takeIf { it.isNotBlank() }?.let { action ->
        evalSourceUi(op.upstream, action) {
            put("java", java); put("infoMap", info)
        }
    }
    if (info.needSave) info.saveNow()
    if (java.refreshRequested) op.upstream.clearExploreKindsCache()
    discoverForm(op.upstream).rows.map { row ->
        DiscoverKind(row.title, row.url, upstreamJson = row.upstreamJson, control = row)
    }
}

private suspend fun sourceLoginForm(source: BookSource, supplied: Map<String, String>?): EnhancedSourceForm {
    val values = supplied?.toMutableMap()
        ?: runScriptWithContext(currentCoroutineContext()) { source.getLoginInfoMap() }
    suspend fun eval(script: String): String = evalSourceUi(source, "${source.getLoginJs().orEmpty()}\n$script") {
        put("result", values.toMutableMap()); put("book", null); put("chapter", null)
    }.toString()
    val raw = source.loginUi.orEmpty()
    if (raw.isBlank()) {
        val url = source.loginUrl?.let { NetworkUtils.getAbsoluteURL(source.bookSourceUrl, it) }
        return EnhancedSourceForm(emptyList(), values, url)
    }
    val json = when {
        raw.startsWith("@js:") -> eval(raw.substring(4))
        raw.startsWith("<js>") -> eval(raw.substring(4, raw.lastIndexOf("<")))
        else -> raw
    }
    val rows = GSON.fromJson(json, Array<RowUi>::class.java).map { row ->
        val title = displayTitle(row.name, row.viewName, ::eval)
        val options = row.chars?.filterNotNull().orEmpty()
        if (row.type in setOf("select", "toggle") && values[row.name].isNullOrEmpty()) {
            values[row.name] = row.default ?: options.firstOrNull().orEmpty()
        }
        EnhancedSourceControl(
            row.name, title, row.type, options, row.default.orEmpty(),
            values[row.name].orEmpty(), row.action.orEmpty(), GSON.toJson(row.style),
        )
    }
    return EnhancedSourceForm(rows, values)
}

private suspend fun discoverForm(source: BookSource): EnhancedSourceForm {
    val info = getExploreInfoMap(source.bookSourceUrl)
    val rows = source.exploreKinds().map { kind ->
        check(!kind.title.startsWith("ERROR:")) { kind.title }
        val title = displayTitle(kind.title, kind.viewName) { script ->
            evalSourceUi(source, script) { put("infoMap", info) }?.toString().orEmpty()
        }
        val options = kind.chars?.filterNotNull().orEmpty()
        if (kind.type in setOf("toggle", "select") && info[kind.title].isNullOrEmpty()) {
            info[kind.title] = kind.default ?: options.firstOrNull().orEmpty()
            info.saveNow()
        }
        EnhancedSourceControl(
            key = kind.title, title = title, type = kind.type,
            options = options, default = kind.default.orEmpty(),
            value = info[kind.title] ?: kind.default.orEmpty(), action = kind.action.orEmpty(),
            styleJson = GSON.toJson(kind.style), url = kind.url.orEmpty(), upstreamJson = GSON.toJson(kind),
        )
    }
    return EnhancedSourceForm(rows, info.get().toMap())
}

private suspend fun displayTitle(name: String, viewName: String?, eval: suspend (String) -> String): String {
    if (viewName.isNullOrBlank()) return name
    if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
        return viewName.substring(1, viewName.lastIndex)
    }
    return eval(viewName).ifBlank { name }
}

private suspend fun evalSourceUi(
    source: BookSource, script: String, bindings: ScriptBindings.() -> Unit,
): Any? = runScriptWithContext(currentCoroutineContext()) { source.evalJS(script, bindings) }

/** UI callbacks from the upstream SourceLoginJsExtensions; parsing remains in the upstream engine. */
internal class SourceUiJs(
    private val source: BookSource,
    private val values: MutableMap<String, String>,
    private val rows: List<EnhancedSourceControl>,
) : JsExtensions {
    var refreshRequested = false
        private set
    override fun getSource(): BaseSource = source
    override fun getTag(): String = source.bookSourceName
    fun put(key: String, value: String): String { source.put(key, value); return value }
    fun get(key: String): String = source.get(key)
    @JvmOverloads fun searchBook(key: String, searchScope: String? = null) =
        LegadoPlatform.adapter.sourceAction("searchBook", null, key, searchScope, source.getKey())
    fun addBook(bookUrl: String) =
        LegadoPlatform.adapter.sourceAction("addBook", bookUrl, null, null, source.getKey())
    fun showPhoto(src: String) =
        LegadoPlatform.adapter.sourceAction("photo", src, null, null, source.getKey())
    @JvmOverloads fun open(name: String, url: String? = null, title: String? = null, origin: String? = null) =
        LegadoPlatform.adapter.sourceAction(name, url, title, origin, source.getKey())
    @JvmOverloads fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? =
        io.legado.app.model.analyzeRule.AnalyzeRule(null, source = source).getStringList(rule, mContent, isUrl)
    @JvmOverloads fun getString(rule: String?, mContent: Any? = null, isUrl: Boolean = false): String =
        io.legado.app.model.analyzeRule.AnalyzeRule(null, source = source).getString(rule, mContent, isUrl)
    fun upLoginData(data: Map<String, Any?>?) {
        if (data == null) {
            values.clear()
            rows.forEach { values[it.key] = it.default }
        } else data.forEach { (key, value) -> values[key] = value?.toString().orEmpty() }
    }
    @JvmOverloads fun reLoginView(deltaUp: Boolean = false) { refreshRequested = true }
    fun refreshExplore() { refreshRequested = true }
    fun copyText(text: String) {
        (LegadoPlatform.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("书源", text))
    }
    @JvmOverloads fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        kotlinx.coroutines.runBlocking(com.script.rhino.rhinoContextOrNull?.coroutineContext
            ?: kotlin.coroutines.EmptyCoroutineContext) {
            // BottomWebViewDialog resolves source headers even for supplied HTML.
            // Its preload is spliced before head scripts, not evaluated as a result.
            val analyze = io.legado.app.model.analyzeRule.AnalyzeUrl(
                url, source = source, coroutineContext = currentCoroutineContext(),
            )
            val page = html ?: analyze.getStrResponseAwait().body
            check(!page.isNullOrEmpty()) { "html is NullOrEmpty" }
            LegadoPlatform.adapter.browser(LegadoPlatform.BrowserRequest(
                analyze.url, title = source.bookSourceName, html = page, headers = analyze.headerMap,
                sourceKey = source.bookSourceUrl, isRule = true, refetchAfterSuccess = false,
                awaitResult = false, timeoutMs = 300_000,
                preloadJavaScript = preloadJs.orEmpty(), viewConfig = config,
            ))
        }
    }
}
