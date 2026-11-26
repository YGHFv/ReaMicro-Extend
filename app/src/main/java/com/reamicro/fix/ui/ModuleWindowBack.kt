package com.reamicro.fix.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.reamicro.fix.R
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.glass.GlassDialogDefaults
import top.yukonga.miuix.kmp.glass.glassDialogSurface
import top.yukonga.miuix.kmp.integration.dialogSurfaceModifier
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitionScope
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.RemovePlatformDialogDefaultEffects
import top.yukonga.miuix.kmp.utils.WindowNavigationEventScope
import top.yukonga.miuix.kmp.utils.getRoundedCorner
import top.yukonga.miuix.kmp.utils.platformDialogProperties

@Immutable
internal data class ModuleWindowGestureSettings(
    val predictiveMode: ModulePredictiveBackMode = ModulePredictiveBackMode.Off,
    val swipeBack: Boolean = true,
)

internal val LocalModuleWindowGestures = compositionLocalOf { ModuleWindowGestureSettings() }
internal val LocalModuleWindowCloseRequest = staticCompositionLocalOf<(() -> Unit)?> { null }

private data object WindowBackRoot : NavKey
private data object WindowBackPanel : NavKey
private val WindowAospTransition = object : NavTransition by ModuleAospBackTransition {
    override fun scrimFraction(scope: NavTransitionScope): Float =
        if (scope.gesture != null) ModuleAospBackTransition.scrimFraction(scope)
        else scope.relativeDepth.coerceIn(0f, 1f)
}

/**
 * The focused window uses public Miuix NavDisplay and the upstream AOSP example transition.
 * Drafts remain owned by the caller; this transient stack owns only the window animation.
 */
@Composable
internal fun ModuleAospWindowDialog(
    show: Boolean,
    title: String?,
    onClose: () -> Unit,
    dismissEnabled: Boolean = true,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var mounted by remember { mutableStateOf(show) }
    val backStack = remember { navBackStackOf(WindowBackRoot) }
    val currentOnClose by rememberUpdatedState(onClose)
    val currentShow by rememberUpdatedState(show)
    val currentDismissEnabled by rememberUpdatedState(dismissEnabled)
    val currentDismissFinished by rememberUpdatedState(onDismissFinished)
    val requestClose = {
        if (currentDismissEnabled && backStack.size > 1) {
            backStack.removeLastOrNull()
            currentOnClose()
        }
    }
    LaunchedEffect(show) {
        if (show) {
            mounted = true
            if (backStack.size == 1) backStack.add(WindowBackPanel)
        } else if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }
    if (!mounted) return

    val gestureSettings = LocalModuleWindowGestures.current
    val direction = LocalLayoutDirection.current
    val parentBackdrop = LocalModuleSceneBackdrop.current
    val glass = useModuleGlass()
    val dimAmount = if (glass) GlassDialogDefaults.ScrimColor.alpha else MiuixTheme.colorScheme.windowDimming.alpha
    Dialog(
        onDismissRequest = requestClose,
        properties = platformDialogProperties(),
    ) {
        RemovePlatformDialogDefaultEffects()
        WindowNavigationEventScope {
            ModulePredictiveBackScope(animated = gestureSettings.predictiveMode.enabled) {
                ModuleWindowPopupHost {
                    val keyboard = LocalSoftwareKeyboardController.current
                    val imeInsets = WindowInsets.ime
                    val density = LocalDensity.current
                    NavDisplay(
                        backStack = backStack,
                        onBack = {
                            requestClose()
                        },
                        transition = WindowAospTransition,
                        effects = NavDisplayEffects(
                            cornerClipRadius = getRoundedCorner(),
                            cornerClipMode = NavCornerClipMode.All,
                            dimAmount = dimAmount,
                            blockInputDuringTransition = true,
                        ),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        entry<WindowBackRoot> {
                            val transitionScope = LocalNavTransitionScope.current
                            LaunchedEffect(transitionScope) {
                                snapshotFlow { backStack.size == 1 && !transitionScope.isRunning }
                                    .collect { finished ->
                                        if (finished && !currentShow) {
                                            mounted = false
                                            currentDismissFinished?.invoke()
                                        }
                                    }
                            }
                        }
                        entry<WindowBackPanel>(
                            swipeDismiss = if (!gestureSettings.swipeBack || !dismissEnabled) NavSwipeDirection.None
                            else if (direction == LayoutDirection.Ltr) NavSwipeDirection.LeftToRight
                            else NavSwipeDirection.RightToLeft,
                        ) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                LocalModuleWindowCloseRequest provides requestClose,
                                LocalModuleWindowClosing provides !show,
                            ) {
                                val backState = rememberNavigationEventState(NavigationEventInfo.None)
                                NavigationBackHandler(
                                    state = backState,
                                    isBackEnabled = !dismissEnabled || imeInsets.getBottom(density) > 0,
                                    onBackCompleted = { if (imeInsets.getBottom(density) > 0) keyboard?.hide() },
                                )
                                AospDialogPanel(title, insideMargin, requestClose, parentBackdrop, dimAmount, content)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AospDialogPanel(
    title: String?,
    insideMargin: DpSize,
    onClose: () -> Unit,
    backdrop: Backdrop?,
    dimAmount: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(density) { windowSize.height.toDp() }
    val isLargeScreen = with(density) { windowSize.width.toDp() >= 840.dp && windowHeight >= 480.dp }
    val outsideMargin = DialogDefaults.outsideMargin
    val cornerRadius = if (isLargeScreen) DialogDefaults.CornerRadius
        else (getRoundedCorner() - outsideMargin.width).coerceIn(32.dp, 48.dp)
    val topInset = maxOf(
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
        WindowInsets.captionBar.asPaddingValues().calculateTopPadding(),
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding(),
    )
    val dismissLabel = stringResource(R.string.action_close)
    val glass = useModuleGlass()
    val transition = LocalNavTransitionScope.current
    val below = remember(transition) {
        object : NavTransitionScope by transition {
            override val relativeDepth: Float get() = transition.relativeDepth + 1f
        }
    }
    val scrim = remember(below, dimAmount) {
        computedStateOf {
            androidx.compose.ui.graphics.Color.Black.copy(
                alpha = dimAmount * WindowAospTransition.scrimFraction(below).coerceIn(0f, 1f),
            )
        }
    }
    val panelCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Read the live entry transform during sampling instead of duplicating AOSP's scale curves.
    val surfaceScale = remember {
        object : State<Float> {
            override val value: Float get() {
                val coordinates = panelCoordinates.value?.takeIf { it.isAttached } ?: return 1f
                return (coordinates.localToWindow(Offset(1f, 0f)) - coordinates.localToWindow(Offset.Zero))
                    .getDistance().coerceAtLeast(0.001f)
            }
        }
    }
    val surfaceModifier = if (glass) {
        dialogSurfaceModifier(cornerRadius, scrim, surfaceScale, glassDialogSurface(backdrop))
    } else Modifier
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(onClose) { detectTapGestures { onClose() } }
                .semantics { onClick(label = dismissLabel) { onClose(); true } },
        )
        Box(
            Modifier.fillMaxSize().imePadding().navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.captionBar.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = outsideMargin.width)
                .padding(top = topInset, bottom = outsideMargin.height),
            contentAlignment = if (isLargeScreen) Alignment.Center else Alignment.BottomCenter,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = DialogDefaults.MaxWidth).fillMaxWidth()
                    .heightIn(max = if (isLargeScreen) windowHeight * (2f / 3f) else windowHeight - topInset)
                    .onGloballyPositioned { panelCoordinates.value = it }
                    .pointerInput(Unit) { detectTapGestures {} }
                    .then(surfaceModifier),
                cornerRadius = cornerRadius,
                insideMargin = PaddingValues(horizontal = insideMargin.width, vertical = insideMargin.height),
                colors = CardDefaults.defaultColors(
                    color = if (glass) androidx.compose.ui.graphics.Color.Transparent else DialogDefaults.backgroundColor(),
                ),
            ) {
                title?.let {
                    Text(
                        text = it,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        fontSize = MiuixTheme.textStyles.title4.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = DialogDefaults.titleColor(),
                        textAlign = TextAlign.Center,
                    )
                }
                content()
            }
        }
    }
}
