package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.ArrayDeque

internal class ReaderSearchPageBridge(private val reader: ReaderHook) {
    private var owner = WeakReference<Any>(null)
    private var dependencies = WeakReference<Any>(null)
    private var pagerState = WeakReference<Any>(null)
    private var staticPage: Int? = null
    private var mode: Int? = null
    private val curlScopes = ThreadLocal.withInitial { ArrayDeque<Any>() }
    private val curlVisualVersion = ReaderSearchVisualVersion()

    fun install() {
        for ((klass, name, mode) in listOf(
            Triple("SwipePagerKt", "SwipePagerEffects", 0),
            Triple("OverlayPagerKt", "OverlayPagerEffects", 1),
            Triple("StaticPagerKt", "StaticPagerEffects", 2),
        )) observe(klass, name, { args -> bind(args[0], args[5], args[6], mode) })
        observe("CurlPagerKt", "CurlPager", { args ->
            val receiver = args[0]
            curlScopes.get().addLast(receiver ?: this)
            val current = staticPage.takeIf { mode == 4 && owner.get() === receiver }
            bind(receiver, args[7], current, 4)
        }, { curlScopes.get().pollLast() })
        observe("CurlPager_androidKt", "PlatformCurlPager", { args ->
            if (mode == 4 && owner.get() === curlScopes.get().peekLast() && owner.get() != null) {

                staticPage = args[0] as? Int
                applyCurlVisualVersion(args)
            }
        })
    }

    private fun observe(klass: String, name: String, before: (Array<Any?>) -> Unit, after: (() -> Unit)? = null) {
        HookInstallReport.install("ReaderHook", "pager.$klass.$name") {
            val methods = reader.classLoader.loadClass("app.zhendong.reamicro.ui.reader.components.$klass")
                .declaredMethods.filter { it.name == name }
            require(methods.isNotEmpty()) { "$klass.$name" }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args?.let(before) }
                    override fun afterHookedMethod(param: MethodHookParam) { after?.invoke() }
                })
            }
        }
    }

    fun bind(vm: Any?, deps: Any?, current: Any?, kind: Int) {
        if (vm == null) return
        val active = reader.currentViewModelRef?.get()
        if (active != null && active !== vm) return
        owner = WeakReference(vm)
        dependencies = WeakReference(deps)
        staticPage = (current as? Number)?.toInt()
        pagerState = WeakReference(if (current is Number) null else current)
        mode = kind
        reader.currentSearchPagerMode = kind
    }

    fun applyCurlVisualVersion(args: Array<Any?>) {
        if (reader.searchPaintUnavailable) return
        runCatching {
            val epoch = reader.searchPaintEpoch ?: ReaderSearchPaintEpoch(reader.classLoader).also { reader.searchPaintEpoch = it }
            val previous = curlVisualVersion.version
            args[3] = curlVisualVersion.update(args[3] as Int, epoch.version())
            if (curlVisualVersion.version != previous) args[11] = searchPaintChangedFlags(args[11] as Int, 3)
        }.onFailure {
            reader.searchPaintUnavailable = true
            XposedBridge.log("ReaMicro search curl paint unavailable: ${it.javaClass.simpleName}")
        }
    }

    data class SettledSnapshot(val index: Int, val page: Any)

    fun settledSnapshot(vm: Any): SettledSnapshot? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null
        if (owner.get() !== vm || mode != reader.currentSearchPagerMode) return null
        val state = pagerState.get()
        if (state != null && reader.callNoArg(state, "isScrollInProgress") == true) return null
        if (state != null && reader.callNoArg(state, "getIsScrollInProgress") == true) return null
        val index = staticPage ?: (state?.let { reader.callNoArg(it, "getCurrentPage") } as? Number)?.toInt() ?: return null
        val pages = XposedHelpers.getObjectField(vm, "virtualPages") as? Map<*, *> ?: return null
        return pages[index]?.let { SettledSnapshot(index, it) }
    }

    fun settledPage(vm: Any): Any? = settledSnapshot(vm)?.page

    fun page(vm: Any): Any? {
        if (owner.get() !== vm || mode != reader.currentSearchPagerMode) return null
        val deps = dependencies.get() ?: return null
        val index = staticPage ?: pagerState.get()?.let { reader.callNoArg(it, "getCurrentPage") as? Number }?.toInt() ?: return null
        val lookup = reader.callNoArg(deps, "getGetVirtualPage") ?: return null
        return XposedHelpers.callMethod(lookup, "invoke", index)
    }
}
