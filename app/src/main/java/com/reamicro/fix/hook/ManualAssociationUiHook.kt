package com.reamicro.fix.hook

import android.app.Activity
import android.os.Looper
import android.widget.Toast
import com.reamicro.fix.association.ManualAssociationService
import com.reamicro.fix.association.AssociationBookIdentity
import com.reamicro.fix.association.AssociationOwnedState
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.association.model.AssociationPlatforms
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostAssociationBindings
import com.reamicro.fix.core.HostCoroutines
import com.reamicro.fix.core.HostManualEditBindings
import com.reamicro.fix.core.HostNavigation
import com.reamicro.fix.core.HostRuntimeBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.HostGeneration
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** Only owns a draft candidate group. The host still confirms and persists associations. */
internal class ManualAssociationUiHook(
    private val loader: ClassLoader,
    private val activity: () -> Activity?,
    private val settings: () -> ModuleSettingsSnapshot,
) {
    private class Target(
        val vm: WeakReference<Any>,
        val activity: WeakReference<Activity>,
        val identity: AccountHostRuntime.Identity,
        val book: AssociationBookIdentity,
    ) {
        val bookId get() = book.id
        @Volatile var preview: Any? = null
        @Volatile var candidate: Any? = null
        var draft: ManualAssociationDraft? = null
        @Volatile var coverDraft: String? = null
        var originalCover: String? = null
        var editor = WeakReference<HostManualAssociationEditor>(null)
    }
    private data class Page(
        val bookId: Long, val scope: Any,
        val activity: WeakReference<Activity>, val identity: AccountHostRuntime.Identity?,
    )
    private lateinit var host: HostManualEditBindings.Contract
    private lateinit var association: HostAssociationBindings.Contract
    private lateinit var ui: HostUiBindings.Symbols
    private val service = ManualAssociationService()
    private val factory = ReaMicroThirdPartyBookFactory(loader)
    private val editAction = HostBookEditAction(loader)
    private val coverPicker = ManualAssociationCoverLoader(loader)
    private val pages = ThreadLocal<ArrayDeque<Page>>()
    private val toolbars = ThreadLocal<ArrayDeque<Target>>()
    private val renderGuard = ThreadLocal<Boolean>()
    private val targets = ArrayList<Target>()
    @Volatile private var current: Target? = null
    private var currentScope = WeakReference<Any>(null)
    private var currentBookId: Long? = null
    private val publishing = ThreadLocal<Boolean>()
    private val candidateFrame = ThreadLocal<Target?>()
    private class Confirmation(
        val target: Target, val activity: Activity, val repository: Any,
        val selection: ManualAssociationCoverStore.Selection, val executionToken: Long, val job: Any,
    ) {
        val completed = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile var publication: ManualAssociationCoverLoader.Published? = null
    }
    private val confirmation = java.util.concurrent.atomic.AtomicReference<Confirmation?>()
    private val main = ModuleResources.handler(Looper.getMainLooper())
    @Volatile private var retired = false

    internal fun invalidateExecution() {
        coverPicker.invalidateExecution()
        confirmation.get()?.let { pending ->
            runCatching {
                HostCoroutines.cancelJob(loader, pending.job,
                    java.util.concurrent.CancellationException("Manual association was disabled"))
            }.onFailure { HookRuntime.logError("ReaMicro manual association cancellation failed", it) }
        }
    }

    internal fun onModuleEnabledChanged(enabled: Boolean) {
        if (!enabled) HostGeneration.onMain { retirePage() }
    }

    fun install(): Boolean = HookInstallReport.install("ManualAssociationUiHook", "nativePublisherEditor") {
        host = requireNotNull(HostManualEditBindings.publisher)
        association = requireNotNull(HostAssociationBindings.current)
        ui = requireNotNull(HostUiBindings.current)
        val navigation = requireNotNull(HostRuntimeBindings.navigation)
        check(host.type("vm") == association.type("vm") && host.type("scope") == navigation.scope &&
            host.method("scaffold") == ui.method("scaffold") && host.method("topBar") == ui.method("topBar"))
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            coverPicker.install()
            ModuleResources.blockWhile { if (confirmation.get() != null) "请等待手动关联保存完成" else null }
            repeat(host.number("candidateRender.count")) { index ->
                val renderer = host.method("candidateRender.$index")
                handles += HookRuntime.hookMethod(renderer, object : HookCallback() {
                    override fun before(param: Call) {
                        param.setObjectExtra("reamicro.manual.candidate", candidateFrame.get())
                        val candidate = param.args.getOrNull(host.number("candidateRender.$index.candidate"))
                        candidateFrame.set(current?.takeIf { it.candidate === candidate && candidate != null })
                    }
                    override fun after(param: Call) {
                        val previous = param.getObjectExtra("reamicro.manual.candidate") as? Target
                        if (previous == null) candidateFrame.remove() else candidateFrame.set(previous)
                    }
                })
                HookRuntime.deoptimize(renderer)
            }
            handles += HookRuntime.hookMethod(host.method("relate"), object : HookCallback() {
                override fun before(param: Call) {
                    val target = current ?: return
                    if (param.args.getOrNull(1) !== target.candidate || !isOwnerCurrent(target)) return
                    val coverDraft = target.coverDraft ?: return
                    val ownerActivity = target.activity.get() ?: return
                    val repository = param.thisObject ?: return
                    val selected = coverPicker.acquire(coverDraft)
                    if (selected == null) {
                        param.throwable = IllegalStateException("封面草稿已失效，请重新选择")
                        return
                    }
                    val job = runCatching { HostCoroutines.continuationJob(loader, requireNotNull(param.args[2])) }.getOrNull()
                    if (job == null) {
                        selected.close()
                        param.throwable = IllegalStateException("关联任务已结束，请重新打开")
                        return
                    }
                    val pending = Confirmation(target, ownerActivity, repository, selected, coverPicker.executionToken(), job)
                    if (!confirmation.compareAndSet(null, pending)) {
                        selected.close()
                        param.throwable = IllegalStateException("手动关联正在保存，请稍候")
                        return
                    }
                    try {
                        param.setObjectExtra("reamicro.manual.confirmation", pending)
                        HostCoroutines.observeContinuation(loader, param, 2, onCompleted = { _, success -> complete(pending, success) })
                    } catch (error: Throwable) {
                        complete(pending, false)
                        param.throwable = error
                    }
                }
                override fun after(param: Call) {
                    try {
                        val pending = param.getObjectExtra("reamicro.manual.confirmation") as? Confirmation ?: return
                        if (param.throwable != null || param.result !== HostCoroutines.suspended(loader)) {
                            val failure = requireNotNull(HostRuntimeBindings.coroutines).type("failure")
                            complete(pending, param.throwable == null && !failure.isInstance(param.result))
                        }
                    } finally { HostCoroutines.finishInvocationContinuations(loader, param) }
                }
            })
            handles += HookRuntime.hookMethod(host.method("relatedCover"), object : HookCallback() {
                override fun before(param: Call) {
                    val pending = confirmation.get() ?: return
                    val book = param.args.getOrNull(0) ?: return
                    if (pending.repository !== param.thisObject || pending.completed.get() ||
                        nativeBookIdentity(book) != pending.target.book) return
                    try {
                        param.result = pending.selection.publishOnce {
                            coverPicker.publish(pending.activity, pending.target.identity,
                                pending.target.book, pending.selection, book, pending.executionToken).also {
                                pending.publication = it
                            }.book
                        }
                    } catch (error: Throwable) { param.throwable = error }
                }
            })
            for (constructor in association.vmConstructors()) handles += HookRuntime.hookMethod(constructor, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null) param.thisObject?.let(::capture)
                }
            })
            handles += HookRuntime.hookMethod(host.method("root"), object : HookCallback() {
                override fun before(param: Call) {
                    val ownerActivity = activity()?.takeUnless { it.isFinishing || it.isDestroyed }
                    val identity = ownerActivity?.let { AccountHostRuntime.storageSnapshot(it) }
                    val page = Page((param.args[1] as Number).toLong(), requireNotNull(param.args[0]),
                        WeakReference(ownerActivity), identity)
                    val stack = pages.get() ?: ArrayDeque<Page>().also(pages::set)
                    stack.addLast(page)
                    if (currentBookId != page.bookId || currentScope.get() !== page.scope ||
                        current?.let { !matchesPage(page, it) } == true) retirePage()
                    currentBookId = page.bookId
                    currentScope = WeakReference(page.scope)
                    synchronized(targets) {
                        targets.lastOrNull { it.bookId == page.bookId && matchesPage(page, it) && isOwnerCurrent(it) }
                    }?.let(::bind)
                }
                override fun after(param: Call) {
                    pages.get()?.let { stack -> stack.pollLast(); if (stack.isEmpty()) pages.remove() }
                }
            })
            // Native ViewModelStore supplies an existing VM after module hot reload too.
            handles += HookRuntime.hookMethod(host.method("acquireVm"), object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null && pages.get()?.peekLast() != null) param.result?.let(::capture)
                }
            })
            handles += HookRuntime.hookMethod(host.method("content"), object : HookCallback() {
                override fun before(param: Call) {
                    val callback = param.thisObject ?: return
                    val vm = host.field("content.vm").get(callback) ?: return
                    if (association.type("vm").isInstance(vm) && currentScope.get() != null &&
                        host.field("vm.bookId").getLong(vm) == currentBookId) capture(vm)
                }
            })
            handles += HookRuntime.hookMethod(host.method("candidateImage"), object : HookCallback() {
                override fun before(param: Call) {
                    val target = candidateFrame.get() ?: return
                    val coverPath = target.coverDraft ?: target.originalCover ?: return
                    if (target.preview == null || !canRender(target) || !isOwnerCurrent(target)) return
                    val slot = host.number("candidateImage.data")
                    val path = param.args.getOrNull(slot) as? String ?: return
                    val expected = target.candidate?.let { association.property("candidate", "cover", it) } as? String
                    if (path != expected) return
                    val ownerActivity = target.activity.get() ?: return
                    val file = coverPicker.imageSource(ownerActivity, target.book, coverPath) ?: return
                    // Cache paths remain private. Only this owned candidate image uses the draft.
                    param.args[slot] = file
                    val changed = host.number("candidateImage.changed")
                    val defaults = host.number("candidateImage.defaults")
                    param.args[changed] = (param.args[changed] as Int) and 14.inv()
                    param.args[defaults] = (param.args[defaults] as Int) and 1.inv()
                }
            })
            handles += HookRuntime.hookMethod(host.method("scaffold"), object : HookCallback() {
                override fun before(param: Call) {
                    val page = pages.get()?.peekLast() ?: return
                    val slot = host.number("scaffold.toolbar")
                    val original = param.args[slot] ?: return
                    val content = ui.function(ui.type("function2")) { args ->
                        // SubcomposeLayout invokes this later, outside the root's stack frame.
                        val target = current?.takeIf { currentBookId == page.bookId && currentScope.get() === page.scope &&
                            matchesPage(page, it) && isOwnerCurrent(it) }
                        val stack = if (target == null) null else (toolbars.get() ?: ArrayDeque<Target>().also(toolbars::set))
                        if (target != null) stack!!.addLast(target)
                        try { ui.method("invoke2").invoke(original, args?.getOrNull(0), args?.getOrNull(1) ?: 0) }
                        finally { if (stack != null) { stack.pollLast(); if (stack.isEmpty()) toolbars.remove() } }
                    }
                    param.args[slot] = ui.lambda(0x524D7152, content)
                    // This slot is supplied explicitly; its changed flags must be recomputed.
                    val changed = host.number("scaffold.changed")
                    val defaults = host.number("scaffold.defaults")
                    param.args[changed] = (param.args[changed] as Int) and (14 shl (3 * slot)).inv()
                    param.args[defaults] = (param.args[defaults] as Int) and (1 shl slot).inv()
                }
            })
            handles += HookRuntime.hookMethod(host.method("topBar"), object : HookCallback() {
                override fun before(param: Call) {
                    val target = toolbars.get()?.peekLast() ?: return
                    if (renderGuard.get() == true || !canRender(target)) return
                    val slot = host.number("topBar.actions")
                    val defaults = host.number("topBar.defaults")
                    val original = param.args[slot].takeIf { (param.args[defaults] as Int) and (1 shl slot) == 0 }
                    val content = ui.callback(ui.type("function3")) { args ->
                        val composer = requireNotNull(args?.getOrNull(1))
                        renderGuard.set(true)
                        try {
                            if (original != null) ui.method("invoke3").invoke(original, args?.getOrNull(0), composer, args?.getOrNull(2) ?: 0)
                            if (canRender(target)) {
                                ui.instance("startGroup", composer, 0x524D7153)
                                try { renderEdit(target, composer) } finally { ui.instance("endGroup", composer, false) }
                            }
                        } finally { renderGuard.remove() }
                    }
                    param.args[slot] = ui.lambda(0x524D7151, content)
                    param.args[defaults] = (param.args[defaults] as Int) and (1 shl slot).inv()
                    val changed = host.number("topBar.changed")
                    param.args[changed] = (param.args[changed] as Int) and (14 shl (3 * slot)).inv()
                }
            })
            fun observer(index: Int) = object : HookCallback() {
                override fun after(param: Call) {
                    if (publishing.get() == true || param.throwable != null || index == 1 && param.result != true) return
                    val target = current ?: return
                    val vm = target.vm.get() ?: return
                    if (param.thisObject !== association.field("vm.flow").get(vm)) return
                    val next = param.args[index] ?: return
                    if (!association.type("state").isInstance(next) || !matchesBook(target, next) || !isOwnerCurrent(target)) return
                    // Native state publication and confirmation stay intact. Publish only our difference.
                    runCatching { update(target) { merge(it, target) } }
                        .onFailure { HookRuntime.logError("ReaMicro manual candidate refresh stopped", it) }
                    if (association.property("state", "isRelateSuccess", next) == true) main.post {
                        if (!retired && current === target && isOwnerCurrent(target)) {
                            target.preview = null; target.candidate = null; target.draft = null; target.coverDraft = null
                            coverPicker.retain(emptySet())
                            val dialog = target.editor.get(); target.editor.clear(); dialog?.dismiss()
                        }
                    }
                }
            }
            handles += HookRuntime.hookMethod(association.method("cas"), observer(1))
            handles += HookRuntime.hookMethod(association.method("set"), observer(0))
            handles += HookRuntime.hookMethod(association.method("cleared"), object : HookCallback() {
                override fun before(param: Call) {
                    synchronized(targets) { targets.removeAll { it.vm.get() === param.thisObject || it.vm.get() == null } }
                    if (current?.vm?.get() === param.thisObject) retirePage()
                }
            })
            handles += HookRuntime.hookMethod(navigation.controllerPop, object : HookCallback() {
                override fun after(param: Call) {
                    val scope = currentScope.get() ?: return
                    if (param.result == true && HostNavigation.controller(scope) === param.thisObject) retirePage()
                }
            })
            handles += HookRuntime.hookMethod(navigation.navigate, object : HookCallback() {
                override fun after(param: Call) {
                    if (param.throwable == null && param.args.firstOrNull() === currentScope.get()) retirePage()
                }
            })
            listOf("root", "content", "toolbar", "acquireVm", "scaffold", "topBar", "candidateImage", "relate", "relatedCover")
                .map(host::method).forEach(HookRuntime::deoptimize)
            association.vmConstructors().forEach(HookRuntime::deoptimize)
            listOf(association.method("update"), navigation.pop, navigation.navigate).forEach(HookRuntime::deoptimize)
            ModuleResources.cleanup {
                retired = true
                // Retirement already runs on main; never enqueue into a stopped generation handler.
                HostGeneration.onMain { retirePage(); synchronized(targets) { targets.clear() } }
                pages.remove(); toolbars.remove(); renderGuard.remove(); publishing.remove(); candidateFrame.remove()
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        true
    }

    private fun bookIdentity(state: Any): AssociationBookIdentity? = runCatching {
        val book = association.property("state", "book", state) ?: return@runCatching null
        nativeBookIdentity(book)
    }.getOrNull()

    private fun nativeBookIdentity(book: Any): AssociationBookIdentity? = runCatching {
        AssociationBookIdentity((association.property("book", "uid", book) as Number).toLong(),
            (association.property("book", "id", book) as Number).toLong(),
            (association.property("book", "uuid", book) as String))
    }.getOrNull()

    private fun complete(pending: Confirmation, success: Boolean) {
        if (!pending.completed.compareAndSet(false, true)) return
        try {
            pending.publication?.let {
                it.publication.finish(success)
                if (!success) HostNativeCoverCache.invalidate(pending.activity, pending.target.book.uuid, it.path)
            }
        } catch (error: Throwable) {
            HookRuntime.logError("ReaMicro manual cover completion requires inspection", error)
        } finally {
            confirmation.compareAndSet(pending, null)
            pending.selection.close()
        }
    }
    private fun matchesBook(target: Target, state: Any): Boolean = bookIdentity(state) == target.book

    private fun isOwnerCurrent(target: Target): Boolean {
        val ownerActivity = target.activity.get() ?: return false
        if (activity() !== ownerActivity || ownerActivity.isFinishing || ownerActivity.isDestroyed ||
            !AccountHostRuntime.isCurrentStorage(target.identity, ownerActivity)) return false
        val vm = target.vm.get() ?: return false
        if (host.field("vm.bookId").getLong(vm) != target.bookId) return false
        val flow = association.field("vm.flow").get(vm) ?: return false
        val state = association.method("get").invoke(flow) ?: return false
        return association.type("state").isInstance(state) && matchesBook(target, state)
    }

    private fun matchesPage(page: Page, target: Target): Boolean =
        page.activity.get() === target.activity.get() && page.identity?.accountId == target.identity.accountId &&
            page.identity?.generation == target.identity.generation

    private fun capture(vm: Any) {
        if (retired || !association.type("vm").isInstance(vm)) return
        val ownerActivity = activity()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val identity = AccountHostRuntime.storageSnapshot(ownerActivity) ?: return
        val id = host.field("vm.bookId").getLong(vm)
        val flow = association.field("vm.flow").get(vm) ?: return
        val state = association.method("get").invoke(flow) ?: return
        if (!association.type("state").isInstance(state)) return
        val book = bookIdentity(state)?.takeIf { it.id == id && it.uid == identity.accountId } ?: return
        if (!AccountHostRuntime.isCurrentStorage(identity, ownerActivity)) return
        val target = synchronized(targets) {
            targets.removeAll { it.vm.get() == null || it.activity.get() == null ||
                !AccountHostRuntime.isCurrentStorage(it.identity, ownerActivity) }
            targets.firstOrNull { it.vm.get() === vm && it.activity.get() === ownerActivity &&
                it.identity.generation == identity.generation && it.book == book }
                ?: Target(WeakReference(vm), WeakReference(ownerActivity), identity, book).also(targets::add)
        }
        if (Looper.myLooper() == Looper.getMainLooper() && currentBookId == id && currentScope.get() != null) bind(target)
    }
    private fun bind(target: Target) {
        if (!isOwnerCurrent(target) || current === target) return
        clearPreview()
        current = target
        runCatching { update(target) { merge(it, target) } }
            .onFailure { HookRuntime.logError("ReaMicro manual candidate binding stopped", it) }
    }
    private fun canRender(target: Target): Boolean = !retired && settings().canUseManualEdit && current === target &&
        currentScope.get() != null && currentBookId == target.bookId && isOwnerCurrent(target)
    private fun usable(target: Target): Boolean {
        if (!canRender(target)) return false
        val vm = target.vm.get() ?: return false
        val flow = association.field("vm.flow").get(vm) ?: return false
        val state = association.method("get").invoke(flow) ?: return false
        return matchesBook(target, state) && association.property("state", "isRelateSuccess", state) != true &&
            association.property("state", "relating", state) != true
    }
    private fun renderEdit(target: Target, composer: Any) {
        BookImageDialogStyle.capture(loader, composer)
        editAction.render("手动编辑", composer) { open(target) }
    }
    private fun open(target: Target) {
        val activity = target.activity.get() ?: return
        HostGeneration.onMain {
            if (activity.isFinishing || activity.isDestroyed || !canRender(target)) return@onMain
            if (!usable(target)) {
                Toast.makeText(activity, "书籍正在加载或关联，请稍后重试", Toast.LENGTH_SHORT).show()
                return@onMain
            }
            val vm = requireNotNull(target.vm.get())
            val state = requireNotNull(association.method("get").invoke(association.field("vm.flow").get(vm)))
            check(matchesBook(target, state)) { "关联图书已变化" }
            val book = requireNotNull(association.property("state", "book", state))
            fun text(name: String) = association.property("book", name, book) as? String ?: ""
            target.originalCover = text("cover")
            val remoteCover = text("cover").takeIf(ManualAssociationDraft::isCoverAddress).orEmpty()
            val initial = target.draft ?: ManualAssociationDraft(title = text("title"), author = text("author"),
                source = AssociationPlatforms.normalizeSource(text("publisher")) ?: service.selectableSources.first(),
                coverUrl = remoteCover)
            target.editor.get()?.dismiss()
            val dialog = HostManualAssociationEditor(activity, initial, service.selectableSources, { usable(target) },
                prepareCover = { url, checkCurrent -> coverPicker.download(activity, url, checkCurrent) },
                onClosed = { coverPicker.retain(setOfNotNull(target.coverDraft)) }) { next, staged ->
                check(usable(target)) { "关联页面已变化，请重新打开" }
                val draft = next.normalized()
                // 出版信息只提交网络地址，临时图片仅供预览和确认后的本地封面保存。
                val candidate = service.buildCandidate(draft)
                val nativeCandidate = factory.create(candidate, remoteCover)
                val group = factory.createThirdPartyGroup(ManualAssociationPreview.GROUP_PREFIX + candidate.source.displayName,
                    listOf(nativeCandidate))
                val previous = target.preview
                val previousCandidate = target.candidate
                val previousDraft = target.draft
                val previousCover = target.coverDraft
                AccountHostRuntime.withCurrentStorage(target.identity, activity) {
                    check(usable(target)) { "关联页面已变化，请重新打开" }
                    target.preview = group
                    target.candidate = nativeCandidate
                    target.draft = draft
                    target.coverDraft = staged
                }
                try {
                    check(update(target) { merge(it, target) } && usable(target)) { "账号或关联图书已变化，请重新打开" }
                } catch (error: Throwable) {
                    target.preview = previous; target.candidate = previousCandidate
                    target.draft = previousDraft; target.coverDraft = previousCover
                    coverPicker.retain(setOfNotNull(previousCover))
                    throw error
                }
                if (usable(target)) Toast.makeText(activity, "已生成候选，请选择后确认关联", Toast.LENGTH_SHORT).show()
            }
            target.editor = WeakReference(dialog)
            dialog.show()
        }
    }
    private fun merge(state: Any, target: Target): Any {
        if (!matchesBook(target, state) || !isOwnerCurrent(target)) return state
        // Do not alter the native in-flight confirmation state.
        if (association.property("state", "relating", state) == true) return state
        val groups = (association.property("state", "thirdPartyBooks", state) as? List<*>).orEmpty()
        val success = association.property("state", "isRelateSuccess", state) == true
        val group = target.preview?.takeIf { !success && current === target && settings().canUseManualEdit && !retired }
        val next = ManualAssociationPreview.merge(groups, group) {
            it?.let { value -> association.property("group", "publisher", value) as? String }.orEmpty()
        }
        return if (groups == next) state else association.copyState(state, mapOf("thirdPartyBooks" to next))
    }
    private fun update(target: Target, requirePage: Boolean = true, change: (Any) -> Any): Boolean {
        val vm = target.vm.get() ?: return false
        val ownerActivity = target.activity.get() ?: return false
        val flow = association.field("vm.flow").get(vm) ?: return false
        val previousPublishing = publishing.get()
        publishing.set(true)
        return try {
            AssociationOwnedState.update(
                isCurrent = { (!requirePage || !retired && current === target) &&
                    association.field("vm.flow").get(vm) === flow && isOwnerCurrent(target) },
                read = { association.method("get").invoke(flow)?.takeIf(association.type("state")::isInstance) },
                matches = { matchesBook(target, it) },
                reduce = change,
                compareAndSet = { before, after -> association.method("cas").invoke(flow, before, after) == true },
                commit = { action -> AccountHostRuntime.withCurrentStorage(target.identity, ownerActivity) { action() } },
            )
        } finally {
            if (previousPublishing == null) publishing.remove() else publishing.set(previousPublishing)
        }
    }
    private fun clearPreview() {
        coverPicker.cancel()
        val previous = current
        val oldPreview = previous?.preview
        current = null
        previous?.preview = null; previous?.candidate = null; previous?.draft = null; previous?.coverDraft = null
        previous?.originalCover = null
        val dialog = previous?.editor?.get(); previous?.editor?.clear()
        HostGeneration.onMain {
            dialog?.dismiss()
            if (previous != null && oldPreview != null && isOwnerCurrent(previous)) update(previous, requirePage = false) { state ->
                if (association.property("state", "relating", state) == true) return@update state
                val groups = (association.property("state", "thirdPartyBooks", state) as? List<*>).orEmpty()
                val next = groups.filterNot { it === oldPreview }
                if (next == groups) state else association.copyState(state, mapOf("thirdPartyBooks" to next))
            }
        }
    }
    private fun retirePage() {
        currentBookId = null; currentScope.clear()
        clearPreview()
    }
}
