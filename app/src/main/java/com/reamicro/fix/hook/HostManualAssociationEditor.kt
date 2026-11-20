package com.reamicro.fix.hook

import android.app.Activity
import android.app.Dialog
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.R
import com.reamicro.fix.association.ManualAssociationService
import com.reamicro.fix.association.model.BookSource
import com.reamicro.fix.association.model.ManualAssociationDraft
import com.reamicro.fix.core.InjectedModuleContext
import com.reamicro.fix.reload.ModuleUiRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host-styled metadata draft; the original host candidate screen still confirms the association. */
internal class HostManualAssociationEditor(
    private val activity: Activity,
    initial: ManualAssociationDraft,
    sources: List<BookSource>,
    private val isCurrent: () -> Boolean,
    private val prepareCover: (String, () -> Unit) -> String?,
    private val onClosed: () -> Unit,
    private val onPreview: (ManualAssociationDraft, String?) -> Unit,
) : Dialog(InjectedModuleContext.create(activity), R.style.EpubFullScreenDialog) {
    private enum class Selection { PLATFORM, STATUS }
    private data class Choice(val id: String, val title: String)

    private val availableSources = sources.distinctBy { it.id }
    private val service = ManualAssociationService { availableSources }
    private var draft by mutableStateOf(initial.copy(source = availableSources.firstOrNull { it == initial.source }
        ?: availableSources.firstOrNull() ?: initial.source))
    private var selection by mutableStateOf<Selection?>(null)
    private var submitted by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)
    private var configurationEpoch by mutableIntStateOf(0)
    @Volatile private var closed = false
    private var compose: ComposeView? = null
    private var callbackRegistered = false
    private val owner = ModuleComposeOwner(activity) { dismiss() }
    private val configuration = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { configurationEpoch++ }
        override fun onLowMemory() = Unit
    }

    override fun show() {
        if (isShowing || closed) return
        if (activity.isFinishing || activity.isDestroyed || !isCurrent()) { release(); return }
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
            setOnDismissListener { release() }
            owner.start()
            val view = ComposeView(context)
            compose = view
            view.apply {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                owner.attach(this)
                setContent { this@HostManualAssociationEditor.Content() }
            }
            setContentView(view)
            window?.decorView?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                findViewById<View>(android.R.id.content)?.apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(owner)
                }
            }
            activity.registerComponentCallbacks(configuration)
            callbackRegistered = true
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window?.setWindowAnimations(0)
            super.show()
            window?.let { com.reamicro.fix.ui.DialogWindowStyle.apply(it, activity.window) }
        } catch (error: Throwable) {
            if (isShowing) runCatching { dismiss() }.onFailure(error::addSuppressed)
            runCatching { release() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun dismiss() {
        try { release() } finally { super.dismiss() }
    }

    private fun release() {
        if (closed) return
        closed = true
        val view = compose
        compose = null
        selection = null
        draft = draft.copy(title = "", author = "", intro = "", words = "", status = "", coverUrl = "")
        try {
            view?.disposeComposition()
        } finally {
            try { owner.close() } finally {
                if (callbackRegistered) {
                    callbackRegistered = false
                    activity.unregisterComponentCallbacks(configuration)
                }
                onClosed()
            }
        }
    }

    private fun preview(scope: CoroutineScope) {
        if (closed || submitted) return
        val normalized = draft.normalized()
        val validation = service.validate(normalized)
        if (!validation.isValid) { problem = validation.firstError; return }
        submitted = true
        problem = null
        scope.launch {
            try {
                val jobContext = currentCoroutineContext()
                val staged = withContext(ModuleUiRuntime.io) {
                    prepareCover(normalized.coverUrl) {
                        jobContext.ensureActive()
                        check(!closed && !activity.isFinishing && !activity.isDestroyed && isCurrent()) {
                            "关联页面已变化，请重新打开手动编辑"
                        }
                    }
                }
                check(!closed && !activity.isFinishing && !activity.isDestroyed && isCurrent()) {
                    "关联页面已变化，请重新打开手动编辑"
                }
                onPreview(normalized, staged)
                dismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!closed) problem = error.message ?: "生成关联预览失败，请重试"
            } finally {
                if (!closed) submitted = false
            }
        }
    }

    private fun changed(next: ManualAssociationDraft) {
        if (closed || submitted) return
        draft = next
        problem = null
    }

    override fun cancel() {
        if (selection != null) selection = null else super.cancel()
    }

    @Composable
    private fun Content() {
        val config = LocalConfiguration.current
        val palette = HostEmbeddedStyle.palette(activity, HostEmbeddedStyle.isDark(activity))
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(configurationEpoch, config, scale, EmbeddedHostUi.typographyStyles) {
            BookImageDialogStyle.typography(activity, scale)
        }
        val look = remember(palette, scale, typography) { HostDialogLook(palette, scale, typography) }
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val scope = rememberCoroutineScope()
        val validation = service.validate(draft)
        val submit = { focus.clearFocus(); keyboard?.hide(); preview(scope) }
        val current = selection
        val formScroll = rememberScrollState()
        key(current) {
            if (current != null) {
                val choices = if (current == Selection.PLATFORM) availableSources.map { Choice(it.id, it.displayName) }
                    else ManualAssociationDraft.STATUS_OPTIONS.map { Choice(it, it.ifBlank { "未填写" }) }
                val selected = if (current == Selection.PLATFORM) draft.source.id else draft.status
                HostDialogSheet(look, if (current == Selection.PLATFORM) "选择平台" else "状态",
                    onDismiss = { selection = null }, choiceList = true, body = {
                        Column(Modifier.selectableGroup()) {
                            choices.forEachIndexed { index, choice ->
                                if (index > 0) HostDialogDivider(look, look.dp(54f))
                                val active = choice.id == selected
                                val tint = if (active) palette.primary else palette.text
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(active, remember { MutableInteractionSource() }, look.press,
                                        enabled = !submitted, role = Role.RadioButton) {
                                        if (current == Selection.PLATFORM) availableSources.firstOrNull { it.id == choice.id }
                                            ?.let { changed(draft.copy(source = it)) }
                                        else changed(draft.copy(status = choice.id))
                                        selection = null
                                    }.padding(start = look.dp(18f), end = look.dp(12f),
                                        top = look.dp(16f), bottom = look.dp(16f)),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.padding(end = look.dp(16f)).size(look.dp(20f))) {
                                        if (active) HostDialogIcon(HostEmbeddedIcons.Check, tint, Modifier.fillMaxSize())
                                    }
                                    HostDialogLabel(choice.title, typography.bodyLarge, tint, Modifier.weight(1f))
                                }
                            }
                        }
                    })
            } else {
                HostDialogSheet(look, "手动编辑", onDismiss = { dismiss() }, scroll = formScroll,
                    scrollToEndKey = problem, body = {
                    HostDialogCard(look) {
                        HostDialogField(look, "封面", draft.coverUrl, !submitted, "请输入封面地址", singleLine = true, compact = true,
                            keyboardType = KeyboardType.Uri, error = validation.coverError.takeIf { draft.coverUrl.isNotEmpty() },
                            onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(coverUrl = it)) }
                        HostDialogDivider(look, look.dp(16f))
                        HostDialogField(look, "书名", draft.title, !submitted, "请输入书名", singleLine = true, compact = true,
                            error = validation.titleError.takeIf { draft.title.isNotEmpty() },
                            onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(title = it)) }
                        HostDialogDivider(look, look.dp(16f))
                        HostDialogField(look, "作者", draft.author, !submitted, "请输入作者", singleLine = true, compact = true,
                            error = validation.authorError.takeIf { draft.author.isNotEmpty() },
                            onNext = { focus.moveFocus(FocusDirection.Next) }) { changed(draft.copy(author = it)) }
                    }
                    HostDialogCard(look) {
                        SelectionField(look, "平台", draft.source.displayName, validation.sourceError) {
                            focus.clearFocus(); keyboard?.hide(); selection = Selection.PLATFORM
                        }
                        HostDialogDivider(look, look.dp(16f))
                        HostDialogField(look, "字数", draft.words, !submitted, "请输入字数", singleLine = true, compact = true,
                            error = validation.wordsError.takeIf { draft.words.isNotEmpty() },
                            onNext = { focus.clearFocus(); keyboard?.hide() }) { changed(draft.copy(words = it)) }
                        HostDialogDivider(look, look.dp(16f))
                        SelectionField(look, "状态", draft.status.ifBlank { "请选择状态" },
                            validation.statusError.takeIf { draft.status.isNotEmpty() }) {
                            focus.clearFocus(); keyboard?.hide(); selection = Selection.STATUS
                        }
                    }
                    HostDialogCard(look) {
                        HostDialogField(look, "简介", draft.intro, !submitted, "请输入简介", minLines = 3, maxLines = 4, compact = true,
                            error = validation.introError.takeIf { draft.intro.isNotEmpty() }) { changed(draft.copy(intro = it)) }
                    }
                    problem?.let {
                        HostDialogLabel(it, typography.bodyMedium, palette.error,
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }, actions = {
                    HostDialogAction(look, if (submitted) "正在生成…" else "预览", HostEmbeddedIcons.ImageOutline,
                        palette.text, typography.labelLarge, enabled = validation.isValid && !submitted,
                        loading = submitted, onClick = submit)
                })
            }
        }
    }

    @Composable
    private fun SelectionField(look: HostDialogLook, label: String, value: String,
        error: String?, onClick: () -> Unit) {
        val fontScale = LocalDensity.current.fontScale
        val inline = fontScale < 1.5f
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, look.press, enabled = !submitted,
                role = Role.Button, onClickLabel = "选择$label", onClick = onClick)
            .padding(start = look.dp(16f), end = look.dp(12f),
                top = look.dp(if (inline) 0f else 12f), bottom = look.dp(if (inline) 0f else 12f))) {
            if (!inline) HostDialogLabel(label, look.type.bodyLarge, look.palette.caption)
            Row(Modifier.fillMaxWidth().heightIn(min = if (inline) 48.dp else 0.dp)
                .padding(top = look.dp(if (inline) 0f else 4f)),
                horizontalArrangement = Arrangement.spacedBy(look.dp(8f)), verticalAlignment = Alignment.CenterVertically) {
                if (inline) HostDialogLabel(label, look.type.bodyLarge, look.palette.caption,
                    Modifier.width(look.dp(40f * fontScale)))
                HostDialogLabel(value, look.type.bodyLarge, look.palette.text, Modifier.weight(1f))
                HostDialogIcon(HostEmbeddedIcons.NavigateNext, look.palette.surfaceHighest,
                    Modifier.padding(top = look.dp(2f)).size(look.dp(24f)))
            }
            error?.let { HostDialogLabel(it, look.type.bodySmall, look.palette.error,
                Modifier.padding(start = if (inline) look.dp(40f * fontScale + 8f) else 0.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}
