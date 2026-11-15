package com.reamicro.fix.reload

import android.os.Looper
import android.view.View
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import com.reamicro.fix.core.HostMembers
import com.reamicro.fix.core.HostMethodParameters
import com.reamicro.fix.xposed.HookRuntime
import java.lang.reflect.Modifier
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/** 只管理模块 ClassLoader 的 UI 任务；关闭 owner 后仍等其子任务真正完成。 */
@OptIn(InternalCoroutinesApi::class)
internal object ModuleUiRuntime {
    private val mainHandler by lazy { ModuleResources.handler(Looper.getMainLooper()) }
    private val pendingNotifications = AtomicBoolean()
    private val serial = AtomicInteger()
    private val failureLock = Any()
    private var installed = false
    private var composeInstalled = false
    private var failure: String? = null
    private var snapshotObserver: ObserverHandle? = null
    private var snapshotStarted: AtomicBoolean? = null
    private val scopes = linkedSetOf<OwnedScope>()
    private val closingViews = linkedSetOf<View>()
    private val fontScopes = linkedSetOf<CoroutineScope>()
    private val runtimeHooks = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
    private var defaultExecutorThread: Field? = null
    private var composeMain: Lazy<*>? = null
    private var coroutineThreadLocal: ThreadLocal<*>? = null
    private var eventLoopActive: Method? = null
    private var eventLoopEmpty: Method? = null
    private var composeViews: Any? = null
    private var composeViewCount: Method? = null
    private var propertiesCallbackField: Field? = null
    private val propertiesCallback = Runnable { }
    @Volatile private var mainEventLoop: Any? = null
    private val observedRuntimeThreads = linkedSetOf<Thread>()
    private val members = HostMembers()
    private val snapshotNotification = Runnable {
        pendingNotifications.set(false)
        Snapshot.sendApplyNotifications()
    }
    private val ioExecutor by lazy {
        ModuleResources.pool(2, ThreadFactory { task ->
            Thread(task, "ReaMicroUiIO-${serial.incrementAndGet()}").apply { isDaemon = true }
        })
    }

    val main: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            Looper.myLooper() !== Looper.getMainLooper()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(mainHandler.post(block)) { "Module UI main looper has stopped" }
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            scheduleDelay(timeMillis, continuation)
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            timeout(timeMillis, block)
    }

    val io: CoroutineDispatcher = object : CoroutineDispatcher(), Delay {
        override fun dispatch(context: CoroutineContext, block: Runnable) { ioExecutor.execute(block) }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            scheduleDelay(timeMillis, continuation)
        }
        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            timeout(timeMillis, block)
    }

    private fun scheduleDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
        check(mainHandler.postDelayed(callback, timeMillis.coerceAtLeast(0))) { "Module UI main looper has stopped" }
        continuation.invokeOnCancellation { mainHandler.removeCallbacks(callback) }
    }

    private fun timeout(timeMillis: Long, callback: Runnable): DisposableHandle {
        check(mainHandler.postDelayed(callback, timeMillis.coerceAtLeast(0))) { "Module UI main looper has stopped" }
        return object : DisposableHandle {
            override fun dispose() { mainHandler.removeCallbacks(callback) }
        }
    }

    fun scope(label: String): OwnedScope {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Module UI owner requires the main thread" }
        check(ModuleResources.accepting) { "Module generation is retiring" }
        install()
        return OwnedScope(label).also { synchronized(scopes) { scopes += it } }
    }

    class OwnedScope internal constructor(val label: String) : AutoCloseable {
        private val job = SupervisorJob()
        private val errors = CoroutineExceptionHandler { _, error ->
            HookRuntime.logError("ReaMicro UI task failed: $label", error)
        }
        val scope = CoroutineScope(job + main + errors)
        @Volatile private var closed = false
        init {
            job.invokeOnCompletion {
                // Completion may run inside an immediate-dispatch event loop. Leave its stack
                // before releasing the owner so a concurrent reload sees a settled main thread.
                if (!mainHandler.post {
                    captureMainEventLoop()
                    synchronized(scopes) { scopes -= this }
                }) recordFailure("模块面板退出回调无法提交")
            }
        }
        internal val blocker: String
            get() = if (closed) "$label 的任务仍在退出" else "请先关闭$label"
        override fun close() {
            if (closed) return
            closed = true
            job.cancel()
        }
    }

    private fun install() {
        if (installed) return
        installed = true
        ModuleResources.blockWhile(::blocker)
        ModuleResources.cleanup(::retire)
        runCatching {
            val loader = requireNotNull(ModuleUiRuntime::class.java.classLoader)
            check(kotlinx.coroutines.Job::class.java.classLoader === loader) {
                "Coroutine runtime is shared with another ClassLoader"
            }
            prepareRuntimeInspection(loader)
        }.onFailure {
            synchronized(failureLock) {
                if (failure == null) failure = "模块协程运行时无法确认独立回收：${it.message ?: it.javaClass.simpleName}"
            }
            HookRuntime.logError("ReaMicro scoped coroutine inspection failed", it)
        }
    }

    /** 必须在第一个 ComposeView 创建组合之前运行，避免启动无法取消的库级全局协程。 */
    fun prepareCompose() {
        check(Looper.myLooper() === Looper.getMainLooper())
        install()
        if (composeInstalled) return
        composeInstalled = true
        runCatching {
            val loader = requireNotNull(ModuleUiRuntime::class.java.classLoader)
            check(Snapshot::class.java.classLoader === loader) { "Compose runtime is shared with another ClassLoader" }
            check(kotlinx.coroutines.Job::class.java.classLoader === loader) { "Coroutine runtime is shared with another ClassLoader" }
            val uiDispatcher = Class.forName("androidx.compose.ui.platform.AndroidUiDispatcher", false, loader)
            check(uiDispatcher.classLoader === loader)
            composeMain = requireNotNull(members.field(uiDispatcher, "Main\$delegate")).get(null) as Lazy<*>
            val manager = Class.forName("androidx.compose.ui.platform.GlobalSnapshotManager", false, loader)
            check(manager.classLoader === loader)
            val field = requireNotNull(members.field(manager, "started"))
            check(Modifier.isStatic(field.modifiers) && field.type == AtomicBoolean::class.java)
            val started = field.get(null) as AtomicBoolean
            check(started.compareAndSet(false, true)) { "Compose global snapshot manager already started" }
            snapshotStarted = started
            snapshotObserver = Snapshot.registerGlobalWriteObserver {
                if (pendingNotifications.compareAndSet(false, true)) {
                    if (!mainHandler.post(snapshotNotification)) pendingNotifications.set(false)
                }
            }
            installFontTracking(loader)
            isolateTextClassification(loader)
            isolateComposeDebugCallback(loader)
        }.onFailure {
            recordFailure("模块 Compose 运行时无法确认独立回收：${it.message ?: it.javaClass.simpleName}")
            HookRuntime.logError("ReaMicro scoped Compose initialization failed", it)
        }
    }

    private fun installFontTracking(loader: ClassLoader) {
        // Own library ABI, never a host obfuscated class. Track even resolvers created on configuration changes.
        val adapter = Class.forName("androidx.compose.ui.text.font.FontListFontFamilyTypefaceAdapter", false, loader)
        val cache = Class.forName("androidx.compose.ui.text.font.AsyncTypefaceCache", false, loader)
        check(adapter.classLoader === loader && cache.classLoader === loader)
        val constructor = requireNotNull(members.constructor(adapter, listOf(cache, CoroutineContext::class.java)))
        val scopeField = requireNotNull(members.field(adapter, "asyncLoadScope"))
        check(CoroutineScope::class.java.isAssignableFrom(scopeField.type))
        runtimeHooks += HookRuntime.withManagementHooks {
            HookRuntime.hookMethod(constructor, object : com.reamicro.fix.xposed.HookCallback() {
                override fun before(param: Call) {
                    val context = param.args.getOrNull(1) as? CoroutineContext
                    if (context == null) {
                        recordFailure("模块字体任务上下文无法确认")
                    } else {
                        // Preserve caller Job and handlers, replace only its dispatcher and timer.
                        param.args[1] = context + main
                    }
                }
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    try {
                        val scope = requireNotNull(scopeField.get(param.thisObject) as? CoroutineScope)
                        synchronized(fontScopes) { fontScopes += scope }
                    } catch (error: Throwable) {
                        recordFailure("模块字体任务无法确认回收")
                        HookRuntime.logError("ReaMicro font task tracking failed", error)
                    }
                }
            })
        }
        // These are the only callers of the adapter's primary constructor in the shipped library.
        val resolver = Class.forName("androidx.compose.ui.text.font.FontFamilyResolverImpl", false, loader)
        for (caller in adapter.declaredConstructors + resolver.declaredConstructors) {
            check(HookRuntime.deoptimize(caller)) { "Cannot deoptimize module font constructor" }
        }
    }

    private fun isolateTextClassification(loader: ClassLoader) {
        val implementation = Class.forName(
            "androidx.compose.foundation.text.selection.PlatformSelectionBehaviorsImpl", false, loader,
        )
        val selectedText = Class.forName(
            "androidx.compose.foundation.text.selection.SelectedTextType", false, loader,
        )
        val locales = Class.forName("androidx.compose.ui.text.intl.LocaleList", false, loader)
        val factory = Class.forName(
            "androidx.compose.foundation.text.selection.PlatformSelectionBehaviors_androidKt", false, loader,
        )
        check(listOf(implementation, selectedText, locales, factory).all { it.classLoader === loader })
        val parameters = listOf(CoroutineContext::class.java, android.content.Context::class.java, selectedText, locales)
        val constructor = requireNotNull(members.constructor(implementation, parameters))
        runtimeHooks += HookRuntime.withManagementHooks {
            HookRuntime.hookMethod(constructor, object : com.reamicro.fix.xposed.HookCallback() {
                override fun before(param: Call) {
                    val context = param.args.getOrNull(0) as? CoroutineContext
                    if (context == null) {
                        recordFailure("模块文字选择任务上下文无法确认")
                    } else {
                        // withContext keeps the composition's Job; only its worker/timer changes.
                        param.args[0] = context + io
                    }
                }
            })
        }
        check(HookRuntime.deoptimize(constructor)) { "Cannot deoptimize module text-classifier constructor" }
        val callers = factory.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.returnType == implementation &&
                it.parameterTypes.toList() == parameters
        }
        check(callers.size == 1) { "Module text-classifier factory is ambiguous" }
        check(HookRuntime.deoptimize(callers.single())) { "Cannot deoptimize module text-classifier factory" }
    }

    private fun prepareRuntimeInspection(loader: ClassLoader) {
        val executor = Class.forName("kotlinx.coroutines.DefaultExecutor", false, loader)
        val local = Class.forName("kotlinx.coroutines.ThreadLocalEventLoop", false, loader)
        val loop = Class.forName("kotlinx.coroutines.EventLoop", false, loader)
        check(listOf(executor, local, loop).all { it.classLoader === loader })
        defaultExecutorThread = requireNotNull(members.field(executor, "_thread")).also {
            check(Modifier.isStatic(it.modifiers) && it.type == Thread::class.java)
        }
        coroutineThreadLocal = requireNotNull(members.field(local, "ref")).get(null) as ThreadLocal<*>
        eventLoopActive = requireNotNull(members.method(loop, "isActive", HostMethodParameters.Exact(emptyList())))
        eventLoopEmpty = requireNotNull(members.method(loop, "isUnconfinedQueueEmpty", HostMethodParameters.Exact(emptyList())))
    }

    private fun isolateComposeDebugCallback(loader: ClassLoader) {
        // This library's debug.layout observer is otherwise registered permanently in
        // Android SystemProperties. Claim its initialization slot before the first view
        // attaches; initial layout-debug state still comes from the normal attach path.
        val view = Class.forName("androidx.compose.ui.platform.AndroidComposeView", false, loader)
        check(view.classLoader === loader)
        val callback = requireNotNull(members.field(view, "systemPropertiesChangedRunnable"))
        check(Modifier.isStatic(callback.modifiers) && callback.type == Runnable::class.java)
        check(callback.get(null) == null) { "Compose system-properties observer already registered" }
        val views = requireNotNull(members.field(view, "composeViews"))
        check(Modifier.isStatic(views.modifiers))
        val list = requireNotNull(views.get(null))
        val size = requireNotNull(members.method(list.javaClass, "getSize", HostMethodParameters.Exact(emptyList())))
        check(size.returnType == Int::class.javaPrimitiveType)
        check(synchronized(list) { size.invoke(list) == 0 }) { "Compose view already attached" }
        callback.set(null, propertiesCallback)
        propertiesCallbackField = callback
        composeViews = list
        composeViewCount = size
    }

    /** Called on main after owner cancellation; the reference is checked again immediately before retirement. */
    fun captureMainEventLoop() {
        check(Looper.myLooper() === Looper.getMainLooper())
        mainEventLoop = coroutineThreadLocal?.get()
    }

    fun awaitViewDetached(view: View) {
        check(Looper.myLooper() === Looper.getMainLooper())
        if (!view.isAttachedToWindow) return
        synchronized(closingViews) { if (!closingViews.add(view)) return }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                if (!mainHandler.post { synchronized(closingViews) { closingViews -= view } }) {
                    recordFailure("模块面板窗口退出回调无法提交")
                }
            }
        }
        view.addOnAttachStateChangeListener(listener)
    }

    fun recordFailure(reason: String) {
        install()
        synchronized(failureLock) { if (failure == null) failure = reason }
    }

    fun blocker(): String? {
        synchronized(failureLock) { failure?.let { return it } }
        synchronized(scopes) { scopes.firstOrNull()?.let { return it.blocker } }
        synchronized(closingViews) {
            if (closingViews.isNotEmpty()) return "模块面板窗口仍在退出"
        }
        if (pendingNotifications.get()) return "模块界面快照仍在提交"
        synchronized(fontScopes) {
            if (fontScopes.any { scope -> scope.coroutineContext[kotlinx.coroutines.Job]?.children?.any { !it.isCompleted } == true }) {
                return "模块字体任务仍在退出"
            }
        }
        if (composeMain?.isInitialized() == true) return "模块界面仍使用未托管的全局调度器"
        if (propertiesCallbackField?.get(null)?.let { it !== propertiesCallback } == true) {
            return "模块界面的系统属性监听发生变化"
        }
        composeViews?.let { list ->
            if (synchronized(list) { composeViewCount?.invoke(list) != 0 }) {
                return "模块 Compose 窗口仍在退出"
            }
        }
        mainEventLoop?.let { loop ->
            if (eventLoopActive?.invoke(loop) == true || eventLoopEmpty?.invoke(loop) == false) {
                return "模块界面的线程本地任务仍在退出"
            }
        }
        // A default dispatcher thread proves an unowned library path escaped this protocol.
        // Do not shut down host/global schedulers or infer safety from an empty UI.
        return moduleRuntimeThread()?.let { "模块协程仍有未托管线程：${it.name}" }
    }

    private fun moduleRuntimeThread(): Thread? {
        val loader = ModuleUiRuntime::class.java.classLoader
        synchronized(observedRuntimeThreads) {
            (defaultExecutorThread?.get(null) as? Thread)?.let { observedRuntimeThreads += it }
            observedRuntimeThreads += Thread.getAllStackTraces().keys.filter { thread ->
                thread.javaClass.classLoader === loader && thread.javaClass.name.startsWith("kotlinx.coroutines.")
            }
            observedRuntimeThreads.removeAll { !it.isAlive }
            return observedRuntimeThreads.firstOrNull()
        }
    }

    private fun retire() {
        captureMainEventLoop()
        check(blocker() == null) { "Module UI runtime has not retired" }
        synchronized(fontScopes) {
            fontScopes.forEach { it.cancel() }
            check(fontScopes.all { it.coroutineContext[kotlinx.coroutines.Job]?.isCompleted != false })
            fontScopes.clear()
        }
        snapshotObserver?.dispose()
        snapshotObserver = null
        mainHandler.removeCallbacks(snapshotNotification)
        runtimeHooks.asReversed().forEach { it.unhook() }
        runtimeHooks.clear()
        // Coroutine immediate dispatch stores an event loop on Android's surviving main Thread.
        // Only this library's ThreadLocal is touched; owned IO Thread objects are joined by ModuleResources.
        coroutineThreadLocal?.remove()
        coroutineThreadLocal = null
        mainEventLoop = null
        eventLoopActive = null
        eventLoopEmpty = null
        defaultExecutorThread = null
        composeMain = null
        composeViews = null
        composeViewCount = null
        propertiesCallbackField = null
        // Keep started claimed until this ClassLoader is discarded; no global manager may restart.
        snapshotStarted = null
    }
}
