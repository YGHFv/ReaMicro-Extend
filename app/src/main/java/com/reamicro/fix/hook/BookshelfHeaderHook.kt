package com.reamicro.fix.hook

import com.reamicro.fix.core.B2NativeUi
import com.reamicro.fix.core.hostAbiMethod
import com.reamicro.fix.core.hostAbiMethodByArity
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.core.hostMethodsNamed
import com.reamicro.fix.core.hostReleaseField
import com.reamicro.fix.core.hostReleaseMethod
import com.reamicro.fix.core.hostReleaseMethodByArity
import com.reamicro.fix.hook.discover.B4DiscoverVector
import com.reamicro.fix.hook.discover.DiscoverUiIcons
import com.reamicro.fix.settings.ModuleSettingsSnapshot
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

/** Shelf actions belong beside selection; the native sync node keeps its own lifecycle. */
internal class BookshelfHeaderHook(
    private val loader: ClassLoader,
    private val drive: WebDavDriveHook,
    private val settings: () -> ModuleSettingsSnapshot,
    private val grid: () -> Boolean,
    private val onPage: (Boolean) -> Unit,
    private val update: () -> Unit,
    private val toggleStyle: () -> Unit,
) {
    private val ui = B2NativeUi(loader)
    private val header = BookshelfHeaderScope()
    private val syncLayouts = BookBackupScrollScope()
    private val handles = ArrayList<XposedInterface.HookHandle>()
    @Volatile private var closed = false
    private val modifierType by lazy { loader.hostClass("androidx.compose.ui.Modifier") }
    private val circle by lazy { loader.hostClass("ReaMicroShelf233.Circle").hostReleaseField("a").get(null) }
    private val clip by lazy {
        loader.hostClass("ReaMicroShelf233.Clip").hostReleaseMethod(
            "a", modifierType, loader.hostClass("androidx.compose.ui.graphics.Shape"),
        )
    }
    private val opacity by lazy {
        loader.hostClass("ReaMicroShelf233.Scale").hostReleaseMethod("b", modifierType, Float::class.javaPrimitiveType)
    }
    private val vectorIcon by lazy {
        loader.hostClass("androidx.compose.material3.IconKt").hostMethodsNamed("Icon-ww6aTOc")
            .single { it.parameterCount == 7 && it.parameterTypes[0] ==
                loader.hostClass("androidx.compose.ui.graphics.vector.ImageVector") }
    }
    private val hiddenSyncModifier by lazy { clip.invoke(null, size(ui.modifier(), 0f), circle) }
    private val updateIcon by lazy {
        B4DiscoverVector.build(loader, "Shelf.Update", listOf(0xff000000L to
            "M17.65,6.35 L15,9 L22,9 L22,2 L19.07,4.93 C17.26,3.12 14.76,2 12,2 C6.48,2 2,6.48 2,12 L4,12 C4,7.58 7.58,4 12,4 C14.21,4 16.21,4.9 17.65,6.35 Z M6.35,17.65 L9,15 L2,15 L2,22 L4.93,19.07 C6.74,20.88 9.24,22 12,22 C17.52,22 22,17.52 22,12 L20,12 C20,16.42 16.42,20 12,20 C9.79,20 7.79,19.1 6.35,17.65 Z"),
            24f, 24f, false)
    }

    fun install() {
        val tabs = loader.hostClass("ReaMicroShelf233.Header")
        val parent = tabs.hostReleaseMethodByArity("b", 15)
        val sync = tabs.hostReleaseMethod(
            "c", loader.hostClass("ReaMicroShelf233.SyncState"), loader.hostClass("ReaMicroShelf233.LanState"),
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            loader.hostClass("kotlin.jvm.functions.Function0"), loader.hostClass("androidx.compose.runtime.Composer"),
            Int::class.javaPrimitiveType,
        )
        try {
            installShelfActions(parent)
            installSelectionSpacing()
            installSyncVisibility(sync)
            installSkippedCompositionGuard()
            listOf(parent, sync).forEach(XposedBridge::deoptimize)
        } catch (error: Throwable) {
            closeForReload()
            throw error
        }
    }

    private fun installShelfActions(parent: Method) {
        handles += XposedBridge.hookMethod(parent, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (closed) return
                val shelfVisible = param.args[3] == true
                val composer = param.args[13] ?: return
                onPage(shelfVisible)
                param.setObjectExtra(HEADER_FRAME, header.enter(
                    composer, shelfVisible, param.args[4] == true, selectionAvailable = param.args[2] == true,
                ))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.getObjectExtra(HEADER_FRAME) as? BookshelfHeaderScope.Frame)?.let(header::leave)
            }
        })
    }

    private fun installSkippedCompositionGuard() {
        // MT: skipToGroupEnd can run child restart callbacks before returning. Their Icon or
        // materialize calls belong to those child scopes, not to the skipped parent body.
        val skip = loader.hostClass("androidx.compose.runtime.Composer").hostAbiMethod("V")
        handles += XposedBridge.hookMethod(skip, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (closed) return
                val composer = param.thisObject ?: return
                param.setObjectExtra(HEADER_SKIP, header.enterSkip(composer))
                param.setObjectExtra(SYNC_SKIP, syncLayouts.enterSkip(composer))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.getObjectExtra(HEADER_SKIP) as? BookshelfHeaderScope.Frame)?.let(header::leaveSkip)
                (param.getObjectExtra(SYNC_SKIP) as? BookBackupScrollScope.Frame)?.let(syncLayouts::leaveSkip)
            }
        })
    }

    private fun installSelectionSpacing() {
        val selection = loader.hostClass("ReaMicroShelf233.SelectionIcon").hostReleaseField("b")
        val then = modifierType.hostAbiMethod("d", modifierType)
        handles += XposedBridge.hookMethod(vectorIcon, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (closed || param.args[0] !== selection.get(null)) return
                val composer = param.args[4] ?: return
                if (!header.claimSelectionSpacing(composer) || !settings().moduleEnabled) return
                // MT: Sync and every added action have an outer 8 UDP end gap; TaskAlt has none.
                // Prefix the same gap outside its original 36 UDP size/clip/click modifier.
                val gap = ui.padding(ui.modifier(), 0f, 0f, udp(BookshelfHeaderGeometry.GAP), 0f)
                param.args[2] = then.invoke(gap, param.args[2])
            }
        })
    }

    private fun installSyncVisibility(sync: Method) {
        handles += XposedBridge.hookMethod(sync, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (closed) return
                val composer = param.args[6] ?: return
                param.setObjectExtra(SYNC_FRAME, syncLayouts.enter(composer))
                // Reserve the header's direct Sync call before nested child restarts can run.
                param.setObjectExtra(ACTIONS_FRAME, header.claimActions(composer))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.getObjectExtra(SYNC_FRAME) as? BookBackupScrollScope.Frame)?.let(syncLayouts::leave)
                if (closed || param.throwable != null || param.getObjectExtra(ACTIONS_FRAME) == null) return
                val composer = param.args[6] ?: return
                // MT: Sync returns to the native Row immediately after selection. Appending here
                // keeps sync before the extra actions; its hidden zero-size root leaves no empty slot.
                appendShelfActions(composer, param.args[4] as Float)
            }
        })
        val materialize = loader.hostClass("ReaMicroHost.Materialize").hostAbiMethodByArity("h", 2)
        handles += XposedBridge.hookMethod(materialize, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (closed) return
                val composer = param.args[0] ?: return
                if (!syncLayouts.claimOuterLayout(composer)) return
                // Read in the native restart scope, including when sync recomposes without its header.
                // Replace only the root modifier: no size, padding, click target or visible overflow.
                // Keeping the original body preserves its balanced Compose groups and restart callback.
                if (BookshelfCompletionPolicy.hideSync(settings())) param.args[1] = hiddenSyncModifier
            }
        })
    }

    private fun appendShelfActions(composer: Any, alpha: Float) {
        // A tracked host composable keeps state/style changes working on independent restarts.
        val actions = ui.lambda(ACTIONS_GROUP, drive.functionProxy(
            "BookshelfSelectionActions", "kotlin.jvm.functions.Function2",
        ) { args ->
            args?.getOrNull(0)?.let { renderShelfActions(it, alpha) }
            drive.targetUnit()
        })
        loader.hostClass("kotlin.jvm.functions.Function2")
            .hostMethod("invoke", Any::class.java, Any::class.java).invoke(actions, composer, 0)
    }

    private fun renderShelfActions(composer: Any, alpha: Float) {
        if (closed) return
        for (action in BookshelfCompletionPolicy.actions(settings())) when (action) {
            BookshelfCompletionPolicy.Action.UPDATE ->
                renderAction("批量更新", requireNotNull(updateIcon), composer, alpha, 8f, update)
            BookshelfCompletionPolicy.Action.STYLE -> {
                val isGrid = grid()
                val icon = if (isGrid) DiscoverUiIcons.layoutGrid(loader) else DiscoverUiIcons.layoutList(loader)
                val label = if (isGrid) "当前宫格，点击切换为列表" else "当前列表，点击切换为宫格"
                renderAction(label, requireNotNull(icon), composer, alpha,
                    BookshelfHeaderGeometry.stylePadding(isGrid), toggleStyle)
            }
        }
    }

    private fun renderAction(label: String, icon: Any, composer: Any, alpha: Float, inset: Float, action: () -> Unit) {
        val scheme = ui.theme(composer, "getColorScheme")
        val color = loader.hostClass("app.zhendong.reamicro.arch.theme.ThemeKt")
            .hostMethod("getOnBackgroundVariant", scheme.javaClass).invoke(null, scheme) as Long
        val click = drive.functionProxy(label, "kotlin.jvm.functions.Function0") { action(); drive.targetUnit() }
        val faded = requireNotNull(opacity.invoke(null, ui.modifier(), alpha))
        val spaced = ui.padding(faded, 0f, 0f, udp(BookshelfHeaderGeometry.GAP), 0f)
        val clipped = requireNotNull(clip.invoke(null, spaced, circle))
        val clickable = size(ui.clickable(clipped, click), udp(BookshelfHeaderGeometry.BUTTON_SIZE))
        val inner = udp(1) * inset
        val iconModifier = ui.padding(clickable, inner, inner, inner, inner)
        vectorIcon.invoke(null, icon, label, iconModifier, color, composer, 0, 0)
    }

    private fun udp(value: Int): Float = loader.hostClass("ReaMicroShelf233.Unit")
        .hostReleaseMethod("e", Int::class.javaPrimitiveType).invoke(null, value) as Float

    private fun size(modifier: Any, value: Float): Any = requireNotNull(
        loader.hostClass("ReaMicroHost.Size").hostAbiMethod(
            "o", modifierType, Float::class.javaPrimitiveType,
        ).invoke(null, modifier, value),
    )

    fun closeForReload() {
        closed = true
        handles.asReversed().forEach { runCatching { it.unhook() } }
        handles.clear()
    }

    private companion object {
        const val ACTIONS_GROUP = 0x524d5341
        const val HEADER_FRAME = "ReaMicro.ShelfHeader.frame"
        const val ACTIONS_FRAME = "ReaMicro.ShelfHeader.actions"
        const val SYNC_FRAME = "ReaMicro.ShelfSync.frame"
        const val HEADER_SKIP = "ReaMicro.ShelfHeader.skip"
        const val SYNC_SKIP = "ReaMicro.ShelfSync.skip"
    }
}
