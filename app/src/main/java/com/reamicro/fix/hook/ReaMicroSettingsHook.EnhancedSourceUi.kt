package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.reamicro.fix.discover.DiscoverKind
import com.reamicro.fix.discover.DiscoverSource
import com.reamicro.fix.discover.DiscoverState
import com.reamicro.fix.hook.ReaMicroSettingsHook.SettingsDialogColors
import com.reamicro.fix.hook.settings.SettingsDialogButtonRole
import com.reamicro.fix.online.EnhancedOnlineParser
import com.reamicro.fix.online.EnhancedSourceControl
import com.reamicro.fix.online.EnhancedSourceForm
import com.reamicro.fix.online.OnlineParserMode
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.online.OnlineSourceStore
import com.reamicro.fix.online.OnlineSourceTask
import com.reamicro.fix.online.browserLogin
import com.reamicro.fix.online.exploreControl
import com.reamicro.fix.online.loginAction
import com.reamicro.fix.online.loginForm
import com.reamicro.fix.online.submitLogin
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.reload.HostGeneration
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.FutureTask

/** UI only: login/form rules execute in the upstream parser on a cancellable worker. */
internal fun ReaMicroSettingsHook.openEnhancedOnlineSourceLogin(
    source: OnlineSourceEntry,
    enableFlow: Boolean,
    onResult: (Boolean) -> Unit,
) {
    val activity = activityProvider() ?: return onResult(false)
    if (!OnlineParserMode.isEnhanced || !OnlineSourceStore.isCurrent(source)) return onResult(false)
    val colors = SettingsDialogColors(activity)
    val dialog = settingsDialog(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, "${source.name} 登录", colors))
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val status = settingsDialogHint(activity, "正在读取书源登录控件…", colors)
    card.addView(status)
    card.addView(body)
    val submit = settingsDialogButton(activity, "登录", colors).apply { isEnabled = false }
    val cancel = settingsDialogButton(activity, if (enableFlow) "跳过启用" else "取消", colors,
        SettingsDialogButtonRole.Neutral)
    card.addView(settingsDialogButtonRow(activity, listOf(submit, cancel)))
    var resolved = false
    fun resolve(success: Boolean) {
        if (resolved) return
        resolved = true
        onResult(success && OnlineSourceStore.isCurrent(source) && OnlineParserMode.isEnhanced)
    }
    val session = EnhancedSourceUiTask(source, activity, dialog) { error ->
        status.text = error.message ?: "书源登录执行失败"
        status.visibility = View.VISIBLE
        submit.isEnabled = true
    }
    var form: EnhancedSourceForm? = null
    val values = linkedMapOf<String, String>()
    var renderVersion = 0
    var controls: EnhancedSourceControls? = null
    lateinit var rebuild: (EnhancedSourceForm) -> Unit
    fun action(control: EnhancedSourceControl, longClick: Boolean) {
        if (control.action.isBlank()) return
        val before = renderVersion
        val snapshot = values.toMap()
        status.text = "正在执行 ${control.title}…"
        status.visibility = View.VISIBLE
        session.submit({ EnhancedOnlineParser.loginAction(source, snapshot, control.key, longClick) }) { result ->
            if (before != renderVersion) return@submit
            // Script results may update other controls. Preserve typing performed
            // since this particular action started.
            val updated = result.values.toMutableMap()
            values.forEach { (key, value) -> if (snapshot[key] != value) updated[key] = value }
            rebuild(result.copy(values = updated))
        }
    }
    rebuild = { next ->
        val focused = controls?.focusedInput()
        val dirty = controls?.dirtyBaseline().orEmpty()
        controls?.dispose()
        renderVersion++
        form = next
        values.clear()
        values.putAll(next.values)
        body.removeAllViews()
        status.visibility = View.GONE
        submit.isEnabled = true
        submit.text = if (next.webUrl != null) "打开登录网页" else "登录"
        if (next.webUrl != null) {
            body.addView(settingsDialogHint(activity, "在宿主网页中完成登录后，点击“完成验证”返回。", colors))
        } else {
            val host = EnhancedSourceControls(this, activity, body, colors, next.rows, values,
                onCommit = { control, _ -> action(control, false) },
                onAction = ::action,
                session = session,
                textDebounce = false, dirtyBaseline = dirty)
            controls = host
            host.render(focused)
            if (next.rows.isEmpty()) body.addView(settingsDialogHint(activity, "该书源未定义登录输入项。", colors))
        }
    }
    submit.setOnClickListener {
        val current = form ?: return@setOnClickListener
        val snapshot = values.toMap()
        controls?.cancelPendingCommits()
        submit.isEnabled = false
        status.text = if (current.webUrl != null) "请完成网页登录…" else "正在登录…"
        status.visibility = View.VISIBLE
        session.submit({
            if (current.webUrl != null) EnhancedOnlineParser.browserLogin(source, current.webUrl)
            else EnhancedOnlineParser.submitLogin(source, snapshot)
        }) {
            resolve(true)
            dialog.dismiss()
        }
    }
    cancel.setOnClickListener {
        resolve(enableFlow)
        dialog.dismiss()
    }
    dialog.setOnDismissListener {
        controls?.dispose()
        session.close()
        resolve(false)
    }
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity)
    session.submit({ EnhancedOnlineParser.loginForm(source) }, rebuild)
}

internal fun ReaMicroSettingsHook.openEnhancedDiscoverControls(
    source: DiscoverSource,
    focus: DiscoverKind? = null,
) {
    val activity = activityProvider() ?: return
    if (!OnlineParserMode.isEnhanced || !OnlineSourceStore.isCurrent(source.source)) return
    val colors = SettingsDialogColors(activity, discoverDialogPalette(activity))
    val dialog = settingsDialog(activity)
    val card = settingsDialogCard(activity, colors)
    card.addView(settingsDialogTitle(activity, source.name, colors))
    val status = settingsDialogHint(activity, "", colors).apply { visibility = View.GONE }
    card.addView(status)
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    card.addView(body)
    val close = settingsDialogButton(activity, "关闭", colors, SettingsDialogButtonRole.Neutral)
    card.addView(close)
    val session = EnhancedSourceUiTask(source.source, activity, dialog) {
        status.text = it.message ?: "发现控件执行失败"
        status.visibility = View.VISIBLE
    }
    val values = linkedMapOf<String, String>()
    var controls: EnhancedSourceControls? = null
    lateinit var rebuild: (List<DiscoverKind>) -> Unit
    fun execute(control: EnhancedSourceControl, value: String?, longClick: Boolean) {
        val snapshot = values.toMap()
        status.text = "正在更新 ${control.title}…"
        status.visibility = View.VISIBLE
        session.submit({ EnhancedOnlineParser.exploreControl(source.source, control.key, value, longClick) }) { kinds ->
            DiscoverState.updateKinds(source.source.id, source.source.revision, kinds, activity.applicationContext)
            val edited = values.filter { (key, current) -> snapshot[key] != current }
            rebuild(kinds.map { kind ->
                val row = kind.control
                if (row == null || row.key !in edited) kind
                else kind.copy(control = row.copy(value = edited.getValue(row.key)))
            })
        }
    }
    rebuild = { kinds ->
        val focused = controls?.focusedInput() ?: focus?.control?.key?.let {
            EnhancedSourceControls.InputFocus(it, -1, -1)
        }
        val dirty = controls?.dirtyBaseline().orEmpty()
        controls?.dispose()
        status.visibility = View.GONE
        body.removeAllViews()
        val rows = kinds.map { kind ->
            kind.control ?: EnhancedSourceControl(kind.title, kind.title, "url", url = kind.url)
        }
        values.clear()
        rows.forEach { values[it.key] = it.value }
        controls = EnhancedSourceControls(this, activity, body, colors, rows, values,
            onCommit = { control, value -> execute(control, value, false) },
            onAction = { control, longClick ->
                if (control.type == "url") {
                    val kind = kinds.firstOrNull { it.control?.key == control.key || it.title == control.key }
                    if (kind != null && kind.isBookList) {
                        dialog.dismiss()
                        DiscoverState.select(source.source.id, kind.title, activity.applicationContext, kind.key)
                    }
                } else execute(control, values[control.key], longClick)
            },
            session = session,
            textDebounce = true,
            dirtyBaseline = dirty,
        ).also { it.render(focused) }
    }
    close.setOnClickListener { dialog.dismiss() }
    dialog.setOnDismissListener { controls?.dispose(); session.close() }
    rebuild(source.kinds)
    showSettingsDialog(dialog, settingsDialogScroll(activity, card), activity, 0.94f, dismissOnThemeChange = true)
}

/** Retires queued work, network requests, delayed commits and old UI results as one owner. */
private class EnhancedSourceUiTask(
    private val source: OnlineSourceEntry,
    private val activity: Activity,
    private val dialog: Dialog,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    companion object {
        private val executor = ModuleResources.pool(2) { task ->
            Thread(task, "ReaMicroSourceUi").apply { isDaemon = true }
        }
    }
    val main = ModuleResources.handler(Looper.getMainLooper())
    private var epoch = 0
    @Volatile private var closed = false
    private var task: FutureTask<Unit>? = null
    private var sourceTask: OnlineSourceTask? = null
    private val delayed = linkedSetOf<Runnable>()
    private val childDialogs = linkedSetOf<Dialog>()
    private val generation = OnlineParserMode.generation
    private val sourceObserver = OnlineSourceStore.observeChanges {
        if (!OnlineSourceStore.isCurrent(source)) main.post { dialog.dismiss() }
    }
    private val modeObserver = OnlineParserMode.observeChanges { main.post { dialog.dismiss() } }

    init {
        val weak = WeakReference(this)
        ModuleResources.cleanup {
            weak.get()?.let { owner ->
                HostGeneration.onMain { owner.dialog.dismiss(); owner.close() }
            }
        }
    }

    fun own(popup: Dialog) {
        if (!current()) { popup.dismiss(); return }
        childDialogs += popup
        popup.setOnDismissListener { childDialogs -= popup }
    }

    fun current(): Boolean = !closed && !activity.isFinishing && !activity.isDestroyed &&
        ModuleResources.accepting && generation == OnlineParserMode.generation && OnlineSourceStore.isCurrent(source)

    fun later(delay: Long, action: () -> Unit): Runnable {
        lateinit var runnable: Runnable
        runnable = Runnable {
            delayed.remove(runnable)
            if (current()) action()
        }
        delayed += runnable
        main.postDelayed(runnable, delay)
        return runnable
    }
    fun remove(runnable: Runnable?) {
        if (runnable == null) return
        main.removeCallbacks(runnable)
        delayed.remove(runnable)
    }

    fun <T> submit(work: () -> T, publish: (T) -> Unit) {
        if (!current()) return
        epoch++
        val id = epoch
        sourceTask?.close()
        task?.cancel(true)
        executor.purge()
        val operation = OnlineSourceTask(source)
        sourceTask = operation
        val next = FutureTask<Unit> {
            val outcome = runCatching { operation.run(work) }
            main.post {
                if (!current() || epoch != id || !operation.isCurrent) return@post
                // An obsolete owner is rejected above. A user's browser cancel
                // belongs to the current form and must restore its submit button.
                outcome.onSuccess(publish).onFailure(onError)
            }
        }
        task = next
        runCatching { executor.execute(next) }.onFailure(onError)
    }

    override fun close() {
        if (closed) return
        closed = true
        epoch++
        sourceTask?.close()
        sourceTask = null
        task?.cancel(true)
        task = null
        delayed.forEach(main::removeCallbacks)
        delayed.clear()
        sourceObserver.close()
        modeObserver.close()
        childDialogs.toList().forEach { it.dismiss() }
        childDialogs.clear()
        executor.purge()
    }
}

/** Android controls live in the host's native settings dialog content slot. */
private class EnhancedSourceControls(
    private val hook: ReaMicroSettingsHook,
    private val activity: Activity,
    private val container: LinearLayout,
    private val colors: SettingsDialogColors,
    private val rows: List<EnhancedSourceControl>,
    private val values: MutableMap<String, String>,
    private val onCommit: (EnhancedSourceControl, String) -> Unit,
    private val onAction: (EnhancedSourceControl, Boolean) -> Unit,
    private val session: EnhancedSourceUiTask,
    private val textDebounce: Boolean,
    dirtyBaseline: Map<String, String> = emptyMap(),
) {
    data class InputFocus(val key: String, val start: Int, val end: Int)
    private var assembling = false
    private var disposed = false
    private val pending = hashMapOf<String, Runnable>()
    private val committed = values.toMutableMap().apply { putAll(dirtyBaseline) }
    private val inputs = linkedMapOf<String, android.widget.EditText>()

    fun focusedInput(): InputFocus? = inputs.entries.firstOrNull { it.value.hasFocus() }?.let {
        InputFocus(it.key, it.value.selectionStart, it.value.selectionEnd)
    }

    fun dirtyBaseline(): Map<String, String> =
        inputs.keys.filter { values[it].orEmpty() != committed[it].orEmpty() }
            .associateWith { committed[it].orEmpty() }

    fun cancelPendingCommits() {
        pending.values.forEach(session::remove)
        pending.clear()
    }

    fun dispose() {
        disposed = true
        cancelPendingCommits()
        inputs.values.forEach { it.onFocusChangeListener = null }
        inputs.clear()
    }

    fun render(focus: InputFocus? = null) {
        assembling = true
        try {
            rows.forEach { row ->
                when (row.type) {
                    "text", "password" -> addText(row, focus)
                    "button", "url", "toggle", "select" -> addButton(row)
                    else -> container.addView(hook.settingsDialogHint(activity, row.title, colors))
                }
            }
        } finally { assembling = false }
    }

    private fun commit(row: EnhancedSourceControl) {
        val value = values[row.key].orEmpty()
        if (disposed || assembling || committed[row.key] == value) return
        committed[row.key] = value
        session.remove(pending.remove(row.key))
        onCommit(row, value)
    }

    private fun addText(row: EnhancedSourceControl, focus: InputFocus?) {
        val input = hook.settingsDialogInput(activity, row.title, singleLine = true, colors = colors).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                if (row.type == "password") InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(values[row.key] ?: row.value)
            onFocusChangeListener = View.OnFocusChangeListener { _, focused -> if (!focused) commit(row) }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { commit(row); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (disposed) return
                    values[row.key] = s?.toString().orEmpty()
                    if (assembling || !textDebounce) return
                    session.remove(pending.remove(row.key))
                    // Same 600 ms action debounce as upstream ExploreKindMultiTypeItem.
                    pending[row.key] = session.later(600) { commit(row) }
                }
            })
        }
        inputs[row.key] = input
        container.addView(input)
        if (textDebounce && values[row.key].orEmpty() != committed[row.key].orEmpty()) {
            pending[row.key] = session.later(600) { commit(row) }
        }
        if (focus?.key == row.key) session.later(0) {
            if (!disposed) {
                input.requestFocus()
                val length = input.text?.length ?: 0
                if (focus.start >= 0 && focus.end >= 0) {
                    input.setSelection(focus.start.coerceAtMost(length), focus.end.coerceAtMost(length))
                }
            }
        }
    }

    private fun addButton(row: EnhancedSourceControl) {
        val options = row.options.ifEmpty { listOf("chars", "is null") }
        if (row.type in setOf("toggle", "select") && values[row.key].isNullOrEmpty()) {
            values[row.key] = row.default.ifBlank { options.first() }
        }
        val button = hook.settingsDialogButton(activity, label(row), colors)
        button.setOnClickListener {
            if (disposed || !session.current()) return@setOnClickListener
            when (row.type) {
                "toggle" -> {
                    val next = (options.indexOf(values[row.key]) + 1).mod(options.size)
                    values[row.key] = options[next]
                    committed[row.key] = options[next]
                    button.text = label(row)
                    onCommit(row, options[next])
                }
                "select" -> select(row, options, button)
                else -> onAction(row, false)
            }
        }
        button.setOnLongClickListener {
            if (disposed || !session.current() || row.action.isBlank()) false else { onAction(row, true); true }
        }
        container.addView(button)
    }

    private fun label(row: EnhancedSourceControl): String = when (row.type) {
        "select" -> "${row.title} ${values[row.key].orEmpty()}"
        "toggle" -> {
            val right = runCatching { JSONObject(row.styleJson).optString("layout_justifySelf") == "right" }.getOrDefault(false)
            if (right) row.title + values[row.key].orEmpty() else values[row.key].orEmpty() + row.title
        }
        else -> row.title
    }

    private fun select(row: EnhancedSourceControl, options: List<String>, button: TextView) {
        val popup = hook.settingsDialog(activity)
        session.own(popup)
        val card = hook.settingsDialogCard(activity, colors)
        card.addView(hook.settingsDialogTitle(activity, row.title, colors))
        options.forEach { option ->
            card.addView(hook.settingsDialogButton(activity, option, colors).apply {
                setOnClickListener {
                    popup.dismiss()
                    if (!session.current() || values[row.key] == option) return@setOnClickListener
                    values[row.key] = option
                    committed[row.key] = option
                    button.text = label(row)
                    onCommit(row, option)
                }
            })
        }
        hook.showSettingsDialog(popup, hook.settingsDialogScroll(activity, card), activity)
    }
}
