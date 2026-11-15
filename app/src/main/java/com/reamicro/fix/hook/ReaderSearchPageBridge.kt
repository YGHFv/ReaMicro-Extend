package com.reamicro.fix.hook

import com.reamicro.fix.core.HostReaderPagerBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

internal class ReaderSearchPageBridge(private val reader: ReaderHook) {
    private val host get() = requireNotNull(HostReaderPagerBindings.current) { "Reader pager contract unavailable" }
    private var owner = WeakReference<Any>(null)
    private var pagerState = WeakReference<Any>(null)
    private var pagerModel = WeakReference<Any>(null)
    private var staticPage: Int? = null
    private var mode: Int? = null
    private class CurlScope(val owner: Any, val model: Any)
    private class CurlCapture(scope: CurlScope) {
        val owner = WeakReference(scope.owner)
        val model = WeakReference(scope.model)
        fun scope(): CurlScope? { val vm = owner.get() ?: return null; return model.get()?.let { CurlScope(vm, it) } }
    }
    private class PlatformScope(val curl: CurlScope, val nativeVersion: Int)
    private val curlScopes = ThreadLocal<ArrayDeque<CurlScope>>()
    private val platformScopes = ThreadLocal<ArrayDeque<PlatformScope>>()
    private val restarts = Collections.synchronizedMap(WeakHashMap<Any, CurlCapture>())
    private val curlVisualVersion = ReaderSearchVisualVersion()

    fun install() {
        val symbols = host
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        fun push(param: HookCallback.Call, scope: CurlScope) {
            val stack = curlScopes.get() ?: ArrayDeque<CurlScope>().also(curlScopes::set)
            stack.addLast(scope); param.setObjectExtra("reamicro.pager.curl", scope)
            val current = staticPage.takeIf { owner.get() === scope.owner && mode == 4 }
            bind(scope.owner, current, 4, scope.model)
        }
        fun pop(param: HookCallback.Call) {
            val scope = param.getObjectExtra("reamicro.pager.curl") as? CurlScope ?: return
            val stack = curlScopes.get() ?: return
            check(stack.pollLast() === scope)
            if (stack.isEmpty()) curlScopes.remove()
        }
        fun scope(vm: Any?, model: Any?): CurlScope? {
            if (!symbols.type("vm").isInstance(vm) || !symbols.type("model").isInstance(model)) return null
            if (reader.currentViewModelRef?.get()?.let { it !== vm } == true) return null
            return CurlScope(requireNotNull(vm), requireNotNull(model))
        }
        try {
            for ((role, kind) in listOf("swipe" to 0, "overlay" to 1, "static" to 2)) {
                handles += HookRuntime.hookMethod(symbols.method(role), object : HookCallback() {
                    override fun before(param: Call) {
                        val args = param.args ?: return
                        bind(args[symbols.index("$role.owner")], args[symbols.index("$role.current")], kind,
                            args[symbols.index("$role.model")])
                    }
                })
            }
            handles += HookRuntime.hookMethod(symbols.method("curl.root"), object : HookCallback() {
                override fun before(param: Call) {
                    val args = param.args ?: return
                    val current = scope(args[symbols.index("curl.root.owner")], args[symbols.index("curl.root.model")]) ?: return
                    push(param, current)
                }
                override fun after(param: Call) = pop(param)
            })
            // Merged lambdas are guarded by actual captured types, not R8 branch numbers.
            handles += HookRuntime.hookMethod(symbols.method("curl.content"), object : HookCallback() {
                override fun before(param: Call) {
                    val closure = param.thisObject ?: return
                    val current = scope(symbols.field("curl.content.owner").get(closure), symbols.field("curl.content.model").get(closure)) ?: return
                    if (curlScopes.get()?.peekLast() == null && reader.currentSearchPagerMode?.let { it != 4 } == true) return
                    push(param, current)
                }
                override fun after(param: Call) = pop(param)
            })
            handles += HookRuntime.hookMethod(symbols.method("curl.restart"), object : HookCallback() {
                override fun before(param: Call) {
                    if (reader.currentSearchPagerMode != 4) return
                    val current = restarts[param.thisObject]?.scope() ?: return
                    if (scope(current.owner, current.model) != null) push(param, current)
                }
                override fun after(param: Call) = pop(param)
            })
            handles += HookRuntime.hookMethod(symbols.method("curl.platform"), object : HookCallback() {
                override fun before(param: Call) {
                    val current = curlScopes.get()?.peekLast() ?: return
                    if (mode != 4 || reader.currentSearchPagerMode != 4 || owner.get() !== current.owner) return
                    val args = param.args ?: return
                    staticPage = args[symbols.index("curl.current")] as Int
                    val frame = PlatformScope(current, args[symbols.index("curl.version")] as Int)
                    val stack = platformScopes.get() ?: ArrayDeque<PlatformScope>().also(platformScopes::set)
                    stack.addLast(frame); param.setObjectExtra("reamicro.pager.platform", frame)
                    applyCurlVisualVersion(args, frame.nativeVersion)
                }
                override fun after(param: Call) {
                    val frame = param.getObjectExtra("reamicro.pager.platform") as? PlatformScope ?: return
                    val stack = platformScopes.get() ?: return
                    check(stack.pollLast() === frame)
                    if (stack.isEmpty()) platformScopes.remove()
                }
            })
            handles += HookRuntime.hookMethod(symbols.constructor("curl.restart"), object : HookCallback() {
                override fun before(param: Call) {
                    val frame = platformScopes.get()?.peekLast() ?: return
                    // Preserve the host version in restart captures to prevent injected epochs feeding back.
                    param.args?.set(symbols.index("curl.restart.version"), frame.nativeVersion)
                    param.setObjectExtra("reamicro.pager.restart", CurlCapture(frame.curl))
                }
                override fun after(param: Call) {
                    if (param.throwable != null) return
                    val closure = param.thisObject ?: return
                    val capture = param.getObjectExtra("reamicro.pager.restart") as? CurlCapture ?: return
                    restarts[closure] = capture
                }
            })
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() } }
            throw error
        }
        for (role in listOf("swipe", "overlay", "static", "curl.root", "curl.content", "curl.platform", "curl.restart"))
            HookRuntime.deoptimize(symbols.method(role))
        ModuleResources.cleanup {
            owner.clear(); pagerState.clear(); pagerModel.clear(); staticPage = null; mode = null
            curlScopes.remove(); platformScopes.remove(); restarts.clear()
        }
    }
    private fun bind(vm: Any?, current: Any?, kind: Int, model: Any?) {
        val symbols = host
        if (!symbols.type("vm").isInstance(vm) || !symbols.type("model").isInstance(model)) return
        if (reader.currentViewModelRef?.get()?.let { it !== vm } == true) return
        if (current != null && current !is Number && !symbols.type("pager").isInstance(current)) return
        owner = WeakReference(vm); staticPage = (current as? Number)?.toInt()
        pagerState = WeakReference(if (current is Number) null else current)
        pagerModel = WeakReference(model); mode = kind; reader.currentSearchPagerMode = kind
    }
    private fun applyCurlVisualVersion(args: Array<Any?>, nativeVersion: Int) {
        if (reader.searchPaintUnavailable) return
        runCatching {
            val epoch = reader.searchPaintEpoch ?: ReaderSearchPaintEpoch(reader.classLoader).also { reader.searchPaintEpoch = it }
            val previous = curlVisualVersion.version
            args[host.index("curl.version")] = curlVisualVersion.update(nativeVersion, epoch.version())
            if (curlVisualVersion.version != previous) {
                val flags = host.index("curl.flags")
                args[flags] = searchPaintChangedFlags(args[flags] as Int, host.index("curl.version") % 10)
            }
        }.onFailure {
            reader.searchPaintUnavailable = true
            HookRuntime.log("ReaMicro search curl paint unavailable: ${it.javaClass.simpleName}")
        }
    }
    data class SettledSnapshot(val index: Int, val page: Any)
    fun settledSnapshot(vm: Any): SettledSnapshot? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null
        if (owner.get() !== vm || mode != reader.currentSearchPagerMode) return null
        val state = pagerState.get()
        if (state != null && host.method("scrolling").invoke(state) == true) return null
        val index = staticPage ?: state?.let { (host.method("current").invoke(it) as Number).toInt() } ?: return null
        val pages = host.field("vm.pages").get(vm) as? Map<*, *> ?: return null
        return pages[index]?.takeIf { host.type("page").isInstance(it) }?.let { SettledSnapshot(index, it) }
    }
    fun settledPage(vm: Any): Any? = settledSnapshot(vm)?.page
    fun page(vm: Any): Any? {
        if (owner.get() !== vm || mode != reader.currentSearchPagerMode) return null
        val index = staticPage ?: pagerState.get()?.let { (host.method("current").invoke(it) as Number).toInt() } ?: return null
        // Querying an obsolete index during a jump must not trigger chapter loading.
        val pages = host.field("vm.pages").get(vm) as? Map<*, *> ?: return null
        return pages[index]?.takeIf { host.type("page").isInstance(it) }
    }
    fun progressCfi(vm: Any): String? {
        if (owner.get() !== vm || mode != reader.currentSearchPagerMode) return null
        val model = pagerModel.get() ?: return null
        return listOf("progress", "bookProgress").mapNotNull { host.field("model.$it").get(model) as? String }
            .firstOrNull { it.startsWith("epubcfi(") }
    }
}
