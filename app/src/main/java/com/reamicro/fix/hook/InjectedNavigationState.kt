package com.reamicro.fix.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Looper
import com.reamicro.fix.core.HostCompositionBindings
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.hook.settings.InjectedRoute
import com.reamicro.fix.hook.settings.InjectedRouteCodec
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap

/** A route belongs to one native entry, including while that entry is animating out. */
internal class InjectedNavigationState(private val hook: ReaMicroSettingsHook) {
    private data class Page(val route: InjectedRoute, var entry: WeakReference<Any>)
    private data class SavedPage(val id: String, val route: InjectedRoute)
    private data class PendingPage(
        val route: InjectedRoute,
        val previousIds: Set<String>,
        var id: String? = null,
    )
    private val pages = linkedMapOf<String, Page>()
    private var activity = WeakReference<Activity>(null)
    private var saved: List<SavedPage> = emptyList()
    private var pendingPage: PendingPage? = null
    private var nativeSnapshot: Bundle? = null
    private var restoredController = WeakReference<Any>(null)
    private val rememberDepth = ThreadLocal.withInitial { 0 }
    private val restoringFactory = ThreadLocal.withInitial { false }
    private val ui get() = requireNotNull(HostUiBindings.current)

    fun route(entry: Any): InjectedRoute? {
        val id = ui.navigationEntryId(entry) ?: return null
        pages[id]?.let { it.entry = WeakReference(entry); return it.route }
        return saved.firstOrNull { it.id == id }?.route?.also {
            pages[id] = Page(it, WeakReference(entry))
        } ?: pendingPage?.takeIf { id !in it.previousIds && (it.id == null || it.id == id) }?.let {
            it.id = id
            pages[id] = Page(it.route, WeakReference(entry))
            it.route
        }
    }

    fun current(): InjectedRoute? {
        val controller = hook.currentSettingsNavController ?: return null
        return runCatching { ui.navigationEntries(controller).lastOrNull()?.let(::route) }.getOrNull()
    }

    fun capture(scope: Any) {
        val controller = runCatching { HostNavigation.controller(scope) }.getOrNull() ?: return
        hook.lastKnownNavGraphScope = scope
        hook.currentSettingsNavGraphScope = scope
        hook.currentSettingsNavController = controller
        hook.activityProvider()?.let(::load)
        entries(controller)?.let { values ->
            values.forEach(::route)
            finishRestore(controller, values)
        }
    }

    fun navigate(scope: Any, target: InjectedRoute): Boolean {
        capture(scope)
        val controller = HostNavigation.controller(scope)
        val entries = entries(controller)?.takeIf { it.isNotEmpty() } ?: return false
        if (nativeSnapshot != null || restoredController.get() != null) return false
        check(pendingPage == null) { "Native settings navigation is already in progress" }
        val pending = PendingPage(target, entries.mapNotNull(ui::navigationEntryId).toSet())
        pendingPage = pending
        val previousNavigating = hook.navigatingModuleRoute.get()
        hook.navigatingModuleRoute.set(true)
        try {
            HostNavigation.navigate(hook.classLoader, scope, ui.value("aboutRoute"))
            val entry = ui.navigationEntries(controller).lastOrNull()
                ?: error("Native navigation did not produce an entry")
            val id = requireNotNull(ui.navigationEntryId(entry))
            check(id !in pending.previousIds) { "Native navigation reused an existing entry" }
            check(pending.id == null || pending.id == id) { "Native navigation rendered a different entry" }
            pages[id] = Page(target, WeakReference(entry))
            synchronize(controller)
            return true
        } finally {
            pendingPage = null
            hook.navigatingModuleRoute.set(previousNavigating)
        }
    }

    fun synchronize(controller: Any?) {
        if (controller == null || controller !== hook.currentSettingsNavController) return
        val entries = entries(controller) ?: return
        for (entry in entries) route(entry)
        hook.bookStructurePages.synchronize(entries.mapNotNull(::route))
        pages.entries.removeAll { it.value.entry.get() == null }
        if (current() != InjectedRoute.FontLibrary) hook.clearPendingDeleteFontSelection()
        finishRestore(controller, entries)
        if (nativeSnapshot == null && restoredController.get() == null) save(entries)
    }

    fun prepareReload(target: Activity) {
        load(target)
        val composition = requireNotNull(HostCompositionBindings.current)
        check(composition.type("activity").isInstance(target))
        val controller = composition.field("navCache").get(null)
            ?: hook.currentSettingsNavController
            ?: return
        val entries = requireNotNull(entries(controller)) { "宿主导航状态尚未就绪" }
        entries.forEach(::route)
        val snapshot = composition.saveNavigation(controller)
        check(snapshot != null || entries.isEmpty()) { "宿主导航保存未产生返回栈" }
        nativeSnapshot = snapshot
        restoredController.clear()
        save(entries, snapshot)
    }

    fun clear() {
        pages.clear()
        saved = emptyList()
        pendingPage = null
        nativeSnapshot = null
        restoredController.clear()
        rememberDepth.remove()
        restoringFactory.remove()
        activity.clear()
    }

    private fun load(target: Activity) {
        if (activity.get() === target) return
        pages.clear()
        saved = emptyList()
        nativeSnapshot = null
        restoredController.clear()
        activity = WeakReference(target)
        val snapshot = target.intent?.getBundleExtra(EXTRA) ?: return
        if (snapshot.getInt("version") != VERSION) return
        saved = (0 until snapshot.getInt("count").coerceIn(0, 256)).mapNotNull { index ->
            val value = snapshot.getBundle("entry$index") ?: return@mapNotNull null
            val id = value.getString("id") ?: return@mapNotNull null
            val route = InjectedRouteCodec.decode(value) ?: return@mapNotNull null
            SavedPage(id, route)
        }
        nativeSnapshot = snapshot.getBundle("native")
    }

    fun beginRememberNavigation() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        rememberDepth.set(rememberDepth.get() + 1)
        hook.activityProvider()?.let(::load)
    }

    fun endRememberNavigation() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        rememberDepth.set((rememberDepth.get() - 1).coerceAtLeast(0))
    }

    fun beginNativeRestore(): Bundle? {
        if (rememberDepth.get() == 0 || restoringFactory.get()) return null
        val snapshot = nativeSnapshot ?: return null
        restoringFactory.set(true)
        return Bundle(snapshot)
    }

    fun finishNativeRestore(controller: Any?, error: Throwable?) {
        try {
            if (error != null) throw error
            check(controller != null &&
                requireNotNull(HostCompositionBindings.current).type("controller").isInstance(controller))
            restoredController = WeakReference(controller)
            nativeSnapshot = null
        } catch (failure: Throwable) {
            HookRuntime.logError("ReaMicro native saved navigation restore failed", failure)
        } finally {
            restoringFactory.set(false)
        }
    }

    /** Restore through the host Saver before the new graph consumes its pending entries. */
    fun restoreNavigationFactory(context: Context): Any? {
        if (rememberDepth.get() == 0 || restoringFactory.get()) return null
        val target = contextActivity(context) ?: hook.activityProvider() ?: return null
        if (target.isDestroyed || target.isFinishing) return null
        load(target)
        val snapshot = nativeSnapshot ?: return null
        restoringFactory.set(true)
        return try {
            requireNotNull(HostCompositionBindings.current)
                .restoreNavigation(context, Bundle(snapshot)).also {
                    restoredController = WeakReference(it)
                    nativeSnapshot = null
                }
        } catch (error: Throwable) {
            HookRuntime.logError("ReaMicro native settings navigation restore failed", error)
            null
        } finally {
            restoringFactory.set(false)
        }
    }

    private fun finishRestore(controller: Any, entries: List<Any>) {
        if (restoredController.get() !== controller || entries.isEmpty()) return
        val ids = entries.mapNotNull(ui::navigationEntryId).toSet()
        if (saved.any { it.id !in ids }) return
        saved = emptyList()
        restoredController.clear()
        save(entries)
    }

    private fun entries(controller: Any): List<Any>? =
        runCatching { ui.navigationEntries(controller) }.getOrNull()

    private fun contextActivity(context: Context): Activity? {
        val seen = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
        var current = context
        while (seen.add(current)) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    private fun save(entries: List<Any>, native: Bundle? = null) {
        val target = activity.get() ?: hook.activityProvider() ?: return
        val intent = target.intent ?: return
        val savedPages = entries.mapNotNull { entry ->
            val id = ui.navigationEntryId(entry) ?: return@mapNotNull null
            val route = pages[id]?.route ?: return@mapNotNull null
            InjectedRouteCodec.encode(route).apply { putString("id", id) }
        }
        if (savedPages.isEmpty() && native == null) {
            intent.removeExtra(EXTRA)
            return
        }
        intent.putExtra(EXTRA, Bundle().apply {
            putInt("version", VERSION)
            putInt("count", savedPages.size)
            native?.let { putBundle("native", it) }
            savedPages.forEachIndexed { index, value -> putBundle("entry$index", value) }
        })
    }

    companion object {
        private const val VERSION = 2
        private const val EXTRA = "com.reamicro.fix.native.settings.entries"
    }
}
