package com.reamicro.fix.hook

import com.reamicro.fix.core.hostNameMatches

import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.ArrayDeque

internal class ReaderSearchPageBridge(private val reader: ReaderHook) {
    private var owner = WeakReference<Any>(null)
    private var pagerState = WeakReference<Any>(null)
    private var pagerModel = WeakReference<Any>(null)
    private var staticPage: Int? = null
    private var mode: Int? = null
    private val curlScopes = ThreadLocal.withInitial { ArrayDeque<Any>() }
    private val curlVisualVersion = ReaderSearchVisualVersion()

    fun install() {
        for ((klass, name, mode) in listOf(
            Triple("SwipePagerKt", "SwipePagerEffects", 0),
            Triple("OverlayPagerKt", "OverlayPagerEffects", 1),
            Triple("StaticPagerKt", "StaticPagerEffects", 2),
        )) observe(klass, name, { args -> bind(args[0], args[6], mode, args.getOrNull(1)) })
        observe("CurlPagerKt", "CurlPager", { args ->
            val receiver = args[0]
            curlScopes.get().addLast(receiver ?: this)
            val current = staticPage.takeIf { mode == 4 && owner.get() === receiver }
            bind(receiver, current, 4, args.getOrNull(1))
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
            val methods = reader.classLoader.hostClass(com.reamicro.fix.core.HostSymbols.resolveMethodOwner(
                "app.zhendong.reamicro.ui.reader.components.$klass", name))
                .declaredMethods.filter { it.hostNameMatches(name) }
            require(methods.isNotEmpty()) { "$klass.$name" }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { param.args?.let(before) }
                    override fun afterHookedMethod(param: MethodHookParam) { after?.invoke() }
                })
            }
            com.reamicro.fix.xposed.XposedBridge.log(
                "ReaMicro search pager hook $klass.$name methods=${methods.size}",
            )
        }
    }

    fun bind(vm: Any?, current: Any?, kind: Int, model: Any? = null) {
        val active = reader.currentViewModelRef?.get()
        if (reader.currentScrollElement != null && kind != 3) return
        // Compose 入口首参是 IntentReceiver，不是 ReaderViewModel；以宿主当前 VM 绑定，
        // 不能把接收器误当成 VM 后静默拒绝全部翻页窗口。
        val bound = active ?: vm ?: return
        owner = WeakReference(bound)
        staticPage = (current as? Number)?.toInt()
        pagerState = WeakReference(if (current is Number) null else current)
        pagerModel = WeakReference(model)
        mode = kind
        reader.currentSearchPagerMode = kind
        com.reamicro.fix.xposed.XposedBridge.log(
            "ReaMicro search pager bound mode=$kind page=${staticPage ?: "state"} type=${bound.javaClass.name}",
        )
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
        val index = staticPage ?: pagerState.get()?.let { reader.callNoArg(it, "getCurrentPage") as? Number }?.toInt() ?: return null
        // getVirtualPage 会为未映射页启动章节加载；跳转期间的旧页码只能查询，不能触发预取。
        val pages = XposedHelpers.getObjectField(vm, "virtualPages") as? Map<*, *> ?: return null
        return pages[index]
    }

    /** 滚动布局没有虚拟页时，宿主 pager state 的 progress 可能是当前 CFI。 */
    fun progressCfi(vm: Any): String? {
        if (owner.get() !== vm) return null
        val model = pagerModel.get() ?: return null
        return runCatching {
            val values = listOf("getProgress", "getBookProgress")
                .mapNotNull { reader.callNoArg(model, it) as? String }
            values.firstOrNull { it.startsWith("epubcfi(") }.also {
                com.reamicro.fix.xposed.XposedBridge.log(
                    "ReaMicro search pager progress=${values.joinToString(" | ")} selected=${it != null}",
                )
            }
        }.getOrNull()
    }
}
