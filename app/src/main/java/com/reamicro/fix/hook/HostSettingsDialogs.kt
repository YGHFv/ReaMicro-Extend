package com.reamicro.fix.hook

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.TextView
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostAndroidViews
import com.reamicro.fix.core.HostDialogBindings
import com.reamicro.fix.core.HostDialogCancellation
import com.reamicro.fix.core.HostTypographyBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.ArrayDeque

/** Android forms occupy the native sentence editor's content slot; the host owns its window. */
internal object HostSettingsDialogs {
    data class Action(val title: String, val onClick: () -> Unit)

    private object TitleTag
    private object TitleRowTag
    private object CardTag
    private val parents = WeakHashMap<Activity, WeakReference<Any>>()
    private val applicationThemeContents = WeakHashMap<Any, Boolean>()
    private val dialogs = linkedSetOf<NativeDialog>()
    private var retirementFailure: String? = null
    @Volatile private var installed = false
    private var rendering = 0
    private val sentences = ThreadLocal<ArrayDeque<NativeDialog>>()

    fun markTitle(view: TextView) { view.tag = TitleTag }
    fun markTitleRow(view: ViewGroup) { view.tag = TitleRowTag }
    fun markCard(view: View) { view.tag = CardTag }
    val ready: Boolean get() = installed

    @Synchronized fun install(loader: ClassLoader, activityProvider: () -> Activity?): Boolean {
        if (installed) return true
        return HookInstallReport.install("ReaMicroSettingsHook", "nativeSettingsDialogs") {
            val ui = requireNotNull(HostUiBindings.current)
            val native = requireNotNull(HostDialogBindings.current)
            requireNotNull(HostTypographyBindings.current)
            requireNotNull(com.reamicro.fix.core.HostIntegrationBindings.androidView)
            requireNotNull(com.reamicro.fix.core.HostRuntimeBindings.coroutines)
            val textParameters = ui.method("text").parameterTypes
            val titleStyleSlot = textParameters.indices.single {
                textParameters[it] == ui.field("typography.labelLarge").type
            }
            val textComposerSlot = textParameters.indices.single {
                textParameters[it] == ui.composerClass
            }
            val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
            val themedContent = HostThemedPopupContent { composer -> capture(activityProvider(), composer) }
            try {
                handles += HookRuntime.hookMethod(native.method("theme"), object : HookCallback() {
                    override fun before(param: Call) {
                        if (rendering != 0) return
                        val slot = native.number("themeContent")
                        val original = param.args.getOrNull(slot)?.takeIf(native.type("function2")::isInstance) ?: return
                        if (applicationThemeContents.containsKey(original)) return
                        val content = themedContent.wrap(original)
                        if (content !== original) native.themedContentArguments(param.args, content).copyInto(param.args)
                    }
                })
                native.themeCallers().forEach(HookRuntime::deoptimize)
                HookRuntime.deoptimize(native.method("theme"))
                handles += HookRuntime.hookMethod(ui.method("text"), object : HookCallback() {
                    override fun before(param: Call) {
                        HostSentenceActionContent.label?.let {
                            param.args[0] = it
                            return
                        }
                        val owner = sentences.get()?.peekLast() ?: return
                        when (param.args.getOrNull(0)) {
                            "句子查询模板" -> {
                                owner.titleContent?.let { content ->
                                    val composer = requireNotNull(param.args[textComposerSlot])
                                    val modifier = param.args.getOrNull(1) ?: ui.value("modifier")
                                    HostAndroidViews(loader).render(
                                        ui.function(ui.type("function1")) { content }, modifier, composer,
                                    )
                                    param.result = null
                                    return
                                }
                                param.args[0] = owner.titleText
                                owner.titleStyle?.let { role ->
                                    val typography = requireNotNull(HostTypographyBindings.current)
                                    val composer = requireNotNull(param.args[textComposerSlot])
                                    param.args[titleStyleSlot] = typography.field("typography.$role")
                                        .get(typography.typography(composer))
                                }
                            }
                            "固定内容", "《书名》里的“查询内容”" -> param.result = null
                        }
                    }
                })
                handles += HookRuntime.hookMethod(native.method("sentenceField"), object : HookCallback() {
                    override fun before(param: Call) {
                        val owner = sentences.get()?.peekLast() ?: return
                        val composer = requireNotNull(param.args[native.number("sentenceFieldComposer")])
                        val modifier = requireNotNull(param.args[native.number("sentenceFieldModifier")])
                        val content = requireNotNull(owner.form)
                        HostAndroidViews(loader).render(
                            ui.function(ui.type("function1")) { content }, modifier, composer,
                        )
                        param.result = null
                    }
                })
                handles += HookRuntime.hookMethod(native.method("sentenceButton"), object : HookCallback() {
                    override fun before(param: Call) {
                        val owner = sentences.get()?.peekLast() ?: return
                        if (owner.renderingActions) return
                        val slot = owner.actionSlot++
                        val first = if (slot == 0) 0 else if (slot == 1) 1 else owner.actions.size
                        val end = if (slot == 0) minOf(1, owner.actions.size) else owner.actions.size
                        if (first < end) {
                            val composer = requireNotNull(param.args[native.number("sentenceButtonComposer")])
                            val modifier = param.args[1] ?: ui.value("modifier")
                            owner.renderingActions = true
                            try {
                                for (index in first until end) {
                                    val action = owner.actions[index]
                                    HostAndroidViews(loader).render(
                                        ui.function(ui.type("function1")) {
                                            TextView(owner.owner).apply {
                                                text = action.title
                                                gravity = android.view.Gravity.CENTER
                                                com.reamicro.fix.ui.applyDialogActionStyle(this)
                                                isClickable = true
                                                setOnClickListener { if (owner.acceptsUpdates) action.onClick() }
                                            }
                                        }, modifier, composer,
                                    )
                                }
                            } finally {
                                owner.renderingActions = false
                            }
                        }
                        param.result = null
                    }
                })
                // The merged host callback can have precompiled calls to these content slots.
                HookRuntime.deoptimize(native.method("sentenceBody"))
                handles += HookRuntime.hookMethod(native.method("nativeDialog"), object : HookCallback() {
                    override fun before(param: Call) {
                        val dismiss = param.args.getOrNull(0) ?: return
                        val scope = HostDialogCancellation.enterRender(dismiss) ?: return
                        param.setObjectExtra("ReaMicro.nativeDialogRender", scope)
                    }
                    override fun after(param: Call) {
                        (param.getObjectExtra("ReaMicro.nativeDialogRender") as? AutoCloseable)?.close()
                    }
                })
                handles += HookRuntime.hookMethod(native.constructor("window"), object : HookCallback() {
                    override fun after(param: Call) {
                        if (param.throwable != null) return
                        val window = param.thisObject ?: return
                        val dismiss = param.args.getOrNull(0) ?: return
                        HostDialogCancellation.bindWindow(window, dismiss)
                        (window as? Dialog)?.window?.let { actual ->
                            activityProvider()?.let { inheritScanSystemBars(actual, it) }
                        }
                    }
                })
                // 宿主在每次重组时重新写入窗口参数，必须在该入口后统一覆盖系统栏与遮罩。
                val updateWindow = native.type("window").declaredMethods.single {
                    it.returnType == Void.TYPE && it.parameterTypes.toList() ==
                        native.constructor("window").parameterTypes.take(2) +
                        native.constructor("window").parameterTypes[3]
                }
                handles += HookRuntime.hookMethod(updateWindow, object : HookCallback() {
                    override fun after(param: Call) {
                        val dialog = param.thisObject as? Dialog ?: return
                        val activity = activityProvider() ?: return
                        dialog.window?.let { inheritScanSystemBars(it, activity) }
                    }
                })
                HookRuntime.deoptimize(updateWindow)
                handles += HookRuntime.hookMethod(Dialog::class.java.getDeclaredMethod("show"), object : HookCallback() {
                    override fun after(param: Call) {
                        val dialog = param.thisObject as? Dialog ?: return
                        // 模块 Material 弹窗和底部工作表另有内容遮罩，仅统一窗口边界。
                        val name = dialog.javaClass.name
                        if (dialog.javaClass.classLoader !== HostSettingsDialogs::class.java.classLoader ||
                            !(name.endsWith(".DialogWrapper") || name.endsWith(".ModalBottomSheetDialog"))) return
                        val activity = activityProvider() ?: return
                        dialog.window?.let { com.reamicro.fix.ui.DialogWindowStyle.apply(it, activity.window) }
                    }
                })
                handles += HookRuntime.hookMethod(native.method("windowTouch"), object : HookCallback() {
                    override fun before(param: Call) {
                        val window = param.thisObject ?: return
                        val dismiss = native.field("windowDismiss").get(window) ?: return
                        val scope = HostDialogCancellation.enterTouch(window, dismiss) ?: return
                        param.setObjectExtra("ReaMicro.nativeDialogTouch", scope)
                    }
                    override fun after(param: Call) {
                        (param.getObjectExtra("ReaMicro.nativeDialogTouch") as? AutoCloseable)?.close()
                    }
                })
                native.renderCallers().forEach(HookRuntime::deoptimize)
                HookRuntime.deoptimize(native.method("nativeDialog"))
                ModuleResources.blockWhile {
                    synchronized(dialogs) {
                        retirementFailure ?: if (dialogs.isNotEmpty()) "请先关闭模块设置弹窗并等待退出完成" else null
                    }
                }
                ModuleResources.cleanup {
                    var failure: Throwable? = null
                    fun retire(action: () -> Unit) {
                        try { action() } catch (error: Throwable) {
                            val previous = failure
                            if (previous == null) failure = error
                            else if (previous !== error) previous.addSuppressed(error)
                        }
                    }
                    handles.asReversed().forEach { handle -> retire { handle.unhook() } }
                    val active = synchronized(dialogs) { dialogs.toList() }
                    active.forEach { dialog -> retire { dialog.dismiss() } }
                    retire { HostDialogCancellation.clear() }
                    retire { synchronized(parents) { parents.clear() } }
                    retire { applicationThemeContents.clear() }
                    retire { themedContent.clear() }
                    retire { sentences.remove() }
                    installed = false
                    failure?.let { throw it }
                }
                installed = true
                true
            } catch (error: Throwable) {
                handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
                throw error
            }
        }
    }

    /** Must run while a themed host composable is executing, before leaving its slot. */
    fun capture(hook: ReaMicroSettingsHook, composer: Any) =
        capture(hook.activityProvider(), composer)

    private fun capture(activity: Activity?, composer: Any) {
        if (rendering != 0) return
        if (activity == null) return
        val native = HostDialogBindings.current ?: return
        val ui = HostUiBindings.current ?: return
        if (!native.type("composer").isInstance(composer)) return
        runCatching {
            ui.instance("startGroup", composer, 0x524D5043)
            try {
                val context = native.rememberContext(composer)
                native.onCommit(ui.callback(ui.type("function0")) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        synchronized(parents) { parents[activity] = WeakReference(context) }
                    }
                }, composer)
            } finally {
                ui.instance("endGroup", composer, false)
            }
            EmbeddedHostUi.captureTypography(composer)
        }.onFailure { HookRuntime.logError("ReaMicro native popup parent capture failed", it) }
    }

    fun create(activity: Activity): Dialog {
        check(ready) { "Native popup hooks unavailable" }
        return NativeDialog(activity)
    }

    /** Results must outlive the route which happened to compose while the scan was running. */
    fun useApplicationTheme(dialog: Dialog) {
        check(dialog is NativeDialog && !dialog.isShowing)
        dialog.applicationTheme = true
    }

    fun inheritSystemBars(dialog: Dialog) {
        check(dialog is NativeDialog && !dialog.isShowing)
        dialog.inheritSystemBars = true
    }

    /** Resolve Android content inside this window's host theme, including the first cold scan. */
    fun bindTheme(dialog: Dialog, apply: (HostThemeColors.Snapshot) -> Unit) {
        check(dialog is NativeDialog && !dialog.isShowing)
        dialog.themeObserver = apply
    }

    fun setActions(dialog: Dialog, actions: List<Action>) {
        check(dialog is NativeDialog && !dialog.isShowing)
        dialog.actions = actions.toList()
    }

    fun setTitleStyle(dialog: Dialog, role: String) {
        check(dialog is NativeDialog && !dialog.isShowing)
        requireNotNull(HostTypographyBindings.current).field("typography.$role")
        dialog.titleStyle = role
    }

    fun backgroundWindow(activity: Activity): Window =
        synchronized(dialogs) {
            dialogs.lastOrNull { it.owner === activity && it.acceptsUpdates }?.renderedWindow()
        } ?: activity.window

    fun acceptsUpdates(dialog: Dialog): Boolean = dialog is NativeDialog && dialog.acceptsUpdates

    fun dismissActive() {
        synchronized(dialogs) { dialogs.toList() }.forEach { it.dismiss() }
    }

    fun show(dialog: Dialog, content: View, dismissOnThemeChange: Boolean) {
        check(dialog is NativeDialog) { "Settings dialogs must be created by settingsDialog(activity)" }
        dialog.prepare(content, dismissOnThemeChange)
        dialog.show()
    }

    private fun renderSentence(owner: NativeDialog, callback: Any, composer: Any, flags: Int) {
        owner.actionSlot = 0
        val stack = sentences.get() ?: ArrayDeque<NativeDialog>().also(sentences::set)
        stack.addLast(owner)
        try {
            requireNotNull(HostDialogBindings.current).method("sentenceBody").invoke(callback, composer, flags)
        } finally {
            stack.pollLast()
            if (stack.isEmpty()) sentences.remove()
        }
    }

    /** Keep the native surface clear of system bars while long forms scroll within it. */
    private class LimitedBody(private val activity: Activity, content: View) : FrameLayout(activity) {
        init {
            val scroll = if (content is ScrollView) content else ScrollView(activity).apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                addView(content)
            }
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val decor = activity.window.decorView
            val fullHeight = decor.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val insets = decor.rootWindowInsets
            val (top, bottom) = if (android.os.Build.VERSION.SDK_INT >= 30 && insets != null) {
                val system = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                system.top to system.bottom
            } else {
                @Suppress("DEPRECATION")
                (insets?.systemWindowInsetTop ?: 0) to (insets?.systemWindowInsetBottom ?: 0)
            }
            val available = (fullHeight - top - bottom).coerceAtLeast(1)
            val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
            val reserved = (96f * scale * resources.displayMetrics.density).toInt()
            val maximum = (available * .78f).toInt().minus(reserved).coerceAtLeast(
                minOf(available, (48f * resources.displayMetrics.density).toInt()),
            )
            val allowed = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
                else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(allowed, MeasureSpec.AT_MOST))
        }
    }

    private data class PreparedTitle(val text: String, val content: View? = null)

    private fun prepareContent(view: View): PreparedTitle? {
        var title: PreparedTitle? = null
        fun visit(current: View) {
            if (current.tag === CardTag) {
                current.background = null
                current.setPadding(0, 0, 0, 0)
            }
            if (current !is ViewGroup) return
            for (child in (0 until current.childCount).map(current::getChildAt)) {
                if (title == null && child is ViewGroup && child.tag === TitleRowTag) {
                    // 带操作入口的标题必须整行移入宿主标题区，不能只提取左侧文字。
                    val label = (0 until child.childCount).map(child::getChildAt)
                        .filterIsInstance<TextView>().first { it.tag === TitleTag }
                    title = PreparedTitle(label.text.toString(), child)
                    current.removeView(child)
                    child.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                } else if (title == null && child is TextView && child.tag === TitleTag) {
                    title = PreparedTitle(child.text.toString())
                    current.removeView(child)
                } else visit(child)
            }
        }
        visit(view)
        return title
    }

    private class NativeDialog(
        val owner: Activity,
    ) : Dialog(owner) {
        var titleText = "提示"; private set
        var titleStyle: String? = null
        var titleContent: View? = null; private set
        var form: View? = null; private set
        var actions: List<Action> = emptyList()
        var applicationTheme = false
        var inheritSystemBars = false
        var themeObserver: ((HostThemeColors.Snapshot) -> Unit)? = null
        var actionSlot = 0
        var renderingActions = false
        private var root: ViewGroup? = null
        private var visibleState: Any? = null
        private var showing = false
        private var closing = false
        private var showDelivered = false
        private var nativeDecor: View? = null
        private val main = ModuleResources.handler(Looper.getMainLooper())
        private var finishPosted = false
        val acceptsUpdates: Boolean get() = showing && !closing
        private val cancellation = HostDialogCancellation()
        private var showListener: DialogInterface.OnShowListener? = null
        private var dismissListener: DialogInterface.OnDismissListener? = null
        private var cancelListener: DialogInterface.OnCancelListener? = null
        private var callbacks: ComponentCallbacks? = null
        private var dismissForConfiguration = false
        private val finishClose = Runnable {
            finishPosted = false
            if (closing) finish()
        }
        private val nativeWindowListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { scheduleFinish() }
        }
        private val formListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                renderedWindow()?.let { inheritScanSystemBars(it, owner) }
                val decor = view.rootView
                if (nativeDecor !== decor) {
                    nativeDecor?.removeOnAttachStateChangeListener(nativeWindowListener)
                    nativeDecor = decor
                    decor.addOnAttachStateChangeListener(nativeWindowListener)
                }
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(activity: Activity) {
                if (activity === owner) finish()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }

        fun renderedWindow(): Window? = cancellation.boundWindows().asSequence()
            .filterIsInstance<Dialog>().mapNotNull { it.window }
            .firstOrNull { it.decorView.isAttachedToWindow }

        fun prepare(content: View, dismissOnThemeChange: Boolean) {
            val title = prepareContent(content)
            titleText = title?.text ?: titleText
            titleContent = title?.content
            form = LimitedBody(owner, content)
            dismissForConfiguration = dismissOnThemeChange
        }

        override fun setContentView(view: View) { prepare(view, false) }
        override fun setContentView(view: View, params: ViewGroup.LayoutParams?) {
            view.layoutParams = params
            setContentView(view)
        }
        override fun setContentView(layoutResId: Int) {
            setContentView(owner.layoutInflater.inflate(layoutResId, null))
        }
        override fun getWindow(): Window? = null
        override fun setTitle(title: CharSequence?) { titleText = title?.toString().orEmpty() }
        override fun setTitle(titleId: Int) { titleText = owner.getString(titleId) }
        override fun setCancelable(flag: Boolean) { cancellation.setCancelable(flag) }
        override fun setCanceledOnTouchOutside(cancel: Boolean) { cancellation.setCanceledOnTouchOutside(cancel) }
        override fun setOnShowListener(listener: DialogInterface.OnShowListener?) { showListener = listener }
        override fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) { dismissListener = listener }
        override fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) { cancelListener = listener }
        override fun isShowing() = showing
        override fun hide() { dismiss() }
        override fun onBackPressed() { if (cancellation.allowsBack()) cancel() }

        override fun show() {
            check(Looper.myLooper() === Looper.getMainLooper()) { "Native popup requires the main thread" }
            if (showing || owner.isFinishing || owner.isDestroyed) return
            check(ModuleResources.accepting) { "Module generation is retiring" }
            val native = requireNotNull(HostDialogBindings.current) { "Native popup contract unavailable" }
            val ui = requireNotNull(HostUiBindings.current)
            check(native.type("activity").isInstance(owner))
            val parent = if (applicationTheme) null else synchronized(parents) { parents[owner]?.get() }
            val content = requireNotNull(form)
            check(content.parent == null) { "Settings form is already attached" }
            val mount = native.constructor("root").newInstance(owner) as ViewGroup
            if (parent != null) native.method("setParent").invoke(mount, parent)
            val state = requireNotNull(ui.call("state", true))
            visibleState = state
            val dismiss = ui.callback(ui.type("function0")) {
                if (cancellation.allowsDismiss()) cancel()
            }
            val sentence = native.sentenceContent(dismiss)
            val slot = ui.lambda(0x524D5046, ui.callback(ui.type("function2")) { args ->
                val composer = args?.getOrNull(0)?.takeIf(ui.composerClass::isInstance) ?: return@callback
                renderSentence(this, sentence, composer, (args.getOrNull(1) as? Number)?.toInt() ?: 0)
            })
            val render = ui.callback(ui.type("function2")) render@{ args ->
                val composer = args?.getOrNull(0)?.takeIf(ui.composerClass::isInstance) ?: return@render
                ui.instance("startGroup", composer, 0x524D5044)
                rendering++
                try {
                    if (ui.stateValue(state) == true) {
                        themeObserver?.let { apply ->
                            runCatching {
                                EmbeddedHostUi.captureTypography(composer)
                                HostThemeColors.readerSnapshot(requireNotNull(ui.call("colorScheme", composer)))?.let(apply)
                            }.onFailure { HookRuntime.logError("ReaMicro native dialog theme unavailable", it) }
                        }
                        native.renderSentence(dismiss, slot, composer)
                    }
                    val committedVisible = ui.stateValue(state) == true
                    native.onCommit(ui.callback(ui.type("function0")) commit@{
                        if (!showing || root !== mount) return@commit
                        if (!committedVisible) {
                            scheduleFinish()
                        } else if (!showDelivered && !closing) {
                            showDelivered = true
                            showListener?.onShow(this)
                        }
                    }, composer)
                } finally {
                    rendering--
                    ui.instance("endGroup", composer, false)
                }
            }
            val body = ui.lambda(0x524D5052, render)
            if (parent == null) applicationThemeContents[body] = true
            // Cold queries can finish on any route after the Activity's theme has already
            // composed. Give this window its own instance of that same application theme
            // until a parent has been captured; no navigation or Activity recreation is needed.
            val rootContent = if (parent != null) body else ui.lambda(
                0x524D5054,
                ui.callback(ui.type("function2")) themed@{ args ->
                    val composer = args?.getOrNull(0)?.takeIf(ui.composerClass::isInstance) ?: return@themed
                    rendering++
                    try {
                        native.renderThemed(body, composer)
                    } finally {
                        rendering--
                    }
                },
            )
            root = mount
            showing = true
            closing = false
            showDelivered = false
            synchronized(dialogs) { dialogs += this }
            try {
                cancellation.track(dismiss)
                content.addOnAttachStateChangeListener(formListener)
                owner.application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
                native.method("setContent").invoke(mount, rootContent)
                (owner.window.decorView as ViewGroup).addView(mount, FrameLayout.LayoutParams(0, 0))
                if (dismissForConfiguration) {
                    val callback = object : ComponentCallbacks {
                        override fun onConfigurationChanged(newConfig: Configuration) {
                            ModuleDialogTheme.invalidate()
                            dismiss()
                        }
                        override fun onLowMemory() = Unit
                    }
                    callbacks = callback
                    owner.registerComponentCallbacks(callback)
                }
            } catch (error: Throwable) {
                runCatching { finish() }.onFailure(error::addSuppressed)
                throw error
            }
        }

        override fun cancel() { close(true) }
        override fun dismiss() { close(false) }

        private fun close(cancelled: Boolean) {
            if (Looper.myLooper() !== Looper.getMainLooper()) {
                if (!main.post { close(cancelled) } && showing) {
                    synchronized(dialogs) { retirementFailure = "宿主弹窗关闭回调无法提交" }
                }
                return
            }
            if (!showing || closing) return
            closing = true
            // Match Dialog.cancel(): notify cancellation once, before native dismissal starts.
            try {
                if (cancelled) cancelListener?.onCancel(this)
            } finally {
                if (owner.isDestroyed || root?.isAttachedToWindow != true) {
                    finish()
                } else {
                    val state = requireNotNull(visibleState)
                    try {
                        requireNotNull(HostUiBindings.current).setState(state, false)
                        // This private composition owns the host Dialog's disposal effect. A queued
                        // disposal also completes when the Activity's frame clock has paused.
                        scheduleFinish()
                    } catch (error: Throwable) {
                        runCatching { finish() }.onFailure(error::addSuppressed)
                        throw error
                    }
                }
            }
        }

        private fun scheduleFinish() {
            if (!closing || finishPosted) return
            finishPosted = true
            // A queue turn leaves the host's applyChanges stack before disposing its parent.
            if (!main.post(finishClose)) {
                finishPosted = false
                synchronized(dialogs) { retirementFailure = "宿主弹窗退出回调无法提交" }
            }
        }

        private fun finish() {
            if (!showing) return
            showing = false
            closing = false
            fun release(action: () -> Unit) {
                runCatching(action).onFailure {
                    synchronized(dialogs) { retirementFailure = "宿主弹窗资源未能完整回收" }
                    HookRuntime.logError("ReaMicro native popup disposal failed", it)
                }
            }
            main.removeCallbacks(finishClose)
            finishPosted = false
            callbacks?.let { release { owner.unregisterComponentCallbacks(it) } }
            callbacks = null
            release { owner.application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks) }
            release { form?.removeOnAttachStateChangeListener(formListener) }
            release { nativeDecor?.removeOnAttachStateChangeListener(nativeWindowListener) }
            nativeDecor = null
            visibleState = null
            root?.let { mount ->
                release { requireNotNull(HostDialogBindings.current).method("dispose").invoke(mount) }
                release { (mount.parent as? ViewGroup)?.removeView(mount) }
            }
            root = null
            titleContent = null
            themeObserver = null
            actions = emptyList()
            cancellation.release()
            synchronized(dialogs) { dialogs -= this }
            HookRuntime.logAlways("ReaMicro native popup dismissed")
            dismissListener?.onDismiss(this)
        }
    }
}
