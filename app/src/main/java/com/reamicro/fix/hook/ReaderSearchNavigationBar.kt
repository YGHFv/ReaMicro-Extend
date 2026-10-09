package com.reamicro.fix.hook

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.core.InjectedModuleContext
import java.lang.ref.WeakReference

internal class ReaderSearchNavigationBar(val activity: Activity, private val reader: ReaderHook) {
    private val owner = ModuleComposeOwner(activity) { dispose() }
    private var index by mutableIntStateOf(0)
    private var count by mutableIntStateOf(0)
    private var confirmed by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var disposed = false
    private var recomposer: Recomposer? = null
    private val view = ComposeView(InjectedModuleContext.create(activity))

    private val placement = ReaderSearchBarPlacement()
    private var parentDecor: ViewGroup? = null
    private val reposition = Runnable { updatePlacement() }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePlacement() }
    private val iconSize = 22.dp
    private var themeSnapshot by mutableStateOf(reader.readerSearchTheme ?:
        StructureHost232Colors.snapshot(activity, StructureHome130Style.isDark(activity)))

    fun updateTheme(snapshot: StructureHost232Colors.Snapshot) {
        if (disposed || themeSnapshot == snapshot) return
        view.post {

            if (!disposed && reader.readerSearchTheme == snapshot) themeSnapshot = snapshot
        }
    }

    private fun safeInsets(): Insets {
        val decor = parentDecor ?: return Insets.NONE
        val windowInsets = ViewCompat.getRootWindowInsets(decor) ?: return Insets.NONE
        val safe = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
        )
        // 手势区允许贴底；三键导航、横屏刘海和键盘仍需避让。
        val bottom = windowInsets.getInsets(WindowInsetsCompat.Type.tappableElement() or
            WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()).bottom
        return Insets.of(safe.left, safe.top, safe.right, bottom)
    }

    private fun bounds(): ReaderSearchBarPlacement.Bounds? {
        val decor = parentDecor ?: return null
        if (decor.width <= 0 || decor.height <= 0 || view.width <= 0 || view.height <= 0) return null
        val insets = safeInsets()
        return ReaderSearchBarPlacement.bounds(decor.width, decor.height, view.width, view.height,
            insets.left, insets.top, insets.right, insets.bottom, 16f * activity.resources.displayMetrics.density)
    }

    private fun place(point: ReaderSearchBarPlacement.Point) {

        view.translationX = point.x - view.left
        view.translationY = point.y - view.top
    }

    private fun updatePlacement() {
        if (disposed) return
        val decor = parentDecor ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val insets = safeInsets()
        val margin = (16 * activity.resources.displayMetrics.density).toInt()
        val horizontalMargin = margin.coerceAtMost(((decor.width - insets.left - insets.right) / 2).coerceAtLeast(0))

        val left = insets.left + horizontalMargin
        val right = insets.right + horizontalMargin
        if (params.leftMargin != left || params.rightMargin != right) {
            params.leftMargin = left
            params.rightMargin = right
            view.layoutParams = params
        }
        bounds()?.let { place(placement.position(it)) }
    }

    private fun dragBy(dx: Float, dy: Float) {
        if (disposed) return
        bounds()?.let { place(placement.moveBy(dx, dy, it)) }
    }

    fun attach() {
        check(!disposed) { "Search navigation bar is already disposed" }
        check(!activity.isFinishing && !activity.isDestroyed) { "Reader activity is closing" }
        if (view.parent != null) return
        val decor = activity.window.decorView as ViewGroup
        try {
            owner.start()
            view.setViewTreeLifecycleOwner(owner)
            view.setViewTreeSavedStateRegistryOwner(owner)

            recomposer = view.createLifecycleAwareWindowRecomposer(lifecycle = owner.lifecycle)
            view.setParentCompositionContext(recomposer)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) { dispose(removeFromParent = false) }
            })
            view.setContent { this@ReaderSearchNavigationBar.BarContent() }
            parentDecor = decor
            decor.addOnLayoutChangeListener(layoutListener)
            view.addOnLayoutChangeListener(layoutListener)

            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                view.removeCallbacks(reposition)
                view.post(reposition)
                insets
            }
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
            })
            ViewCompat.requestApplyInsets(view)
            view.post(reposition)
            reader.searchNavigationBarRef = WeakReference(view)
            reader.searchNavigationBarActivityRef = WeakReference(activity)
        } catch (error: Exception) {
            dispose()
            throw error
        }
    }
    fun render(current: Int, total: Int, hasConfirmed: Boolean = false, failure: Boolean = false) {
        confirmed = hasConfirmed
        failed = failure
        if (!disposed) { index = current; count = total }
    }
    fun refresh() {
        if (!disposed) { reader.readerSearchTheme?.let(::updateTheme); revision += 1 }
    }
    fun visible(show: Boolean) {
        if (!disposed) {
            view.visibility = if (show) View.VISIBLE else View.GONE
            if (show) updatePlacement()
        }
    }
    fun dispose() = dispose(removeFromParent = true)
    private fun dispose(removeFromParent: Boolean) {
        if (disposed) return
        disposed = true
        view.removeCallbacks(reposition)
        view.removeOnLayoutChangeListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
        parentDecor?.removeOnLayoutChangeListener(layoutListener)
        parentDecor = null

        try {
            view.disposeComposition()
        } finally {
            recomposer?.cancel()
            recomposer = null
            try {
                if (removeFromParent) (view.parent as? ViewGroup)?.removeView(view)
            } finally {
                owner.close()
                if (reader.searchNavigationView === this) reader.searchNavigationView = null
                if (reader.searchNavigationBarRef?.get() === view) {
                    reader.searchNavigationBarRef = null
                    reader.searchNavigationBarActivityRef = null
                    reader.maybeUnregisterSearchOverlayThemeCallbacks()
                }
            }
        }
    }
    @Composable private fun BarContent() {
        revision
        val p = StructureHome130Style.Palette(themeSnapshot)

        val iconColors = IconButtonDefaults.iconButtonColors(contentColor = p.text,
            disabledContentColor = p.text.copy(alpha = .38f))
        val fonts = remember { StructureHome130Style.fonts(activity) }
        MaterialTheme(colorScheme = p.controlsScheme(), typography = StructureHome130Style.typography(fonts)) {
            Surface(modifier = Modifier.pointerInput(Unit) {

                detectDragGesturesAfterLongPress(
                    onDragStart = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) },
                    onDragEnd = { updatePlacement() },
                    onDragCancel = { updatePlacement() },
                ) { change, amount ->
                    change.consume()
                    dragBy(amount.x, amount.y)
                }
            }, shape = RoundedCornerShape(28.dp), color = p.page.copy(alpha = .96f),
                border = BorderStroke(.5.dp, p.borderVariant), tonalElevation = 0.dp) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(modifier = Modifier.size(48.dp), colors = iconColors, onClick = { reader.openBottomSearchPage() }) {
                        Icon(StructureHome130Icons.SearchResults, "搜索结果列表", Modifier.size(iconSize), tint = p.text)
                    }
                    IconButton(modifier = Modifier.size(48.dp), colors = iconColors, enabled = index > 0, onClick = { reader.jumpRelativeSearchResult(-1) }) {
                        Icon(StructureHome130Icons.Back, "上一处", Modifier.size(iconSize), tint = if (index > 0) p.text else p.text.copy(alpha = .38f))
                    }
                    TextButton(modifier = Modifier.weight(1f, fill = false), colors = ButtonDefaults.textButtonColors(contentColor = p.primary), onClick = { reader.returnToSearchOrigin() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(searchNavigationLabel(confirmed, failed) + "${index + 1}/$count", style = MaterialTheme.typography.bodyMedium, color = p.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("返回原位", style = MaterialTheme.typography.bodySmall, color = p.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(modifier = Modifier.size(48.dp), colors = iconColors, enabled = index < count - 1, onClick = { reader.jumpRelativeSearchResult(1) }) {
                        Icon(StructureHome130Icons.Back, "下一处", Modifier.size(iconSize).rotate(180f), tint = if (index < count - 1) p.text else p.text.copy(alpha = .38f))
                    }
                    IconButton(modifier = Modifier.size(48.dp), colors = iconColors, onClick = { reader.clearStaleSearchNavigation() }) {
                        Icon(StructureHome130Icons.SearchNavigationClose, "结束搜索导航", Modifier.size(iconSize), tint = p.text)
                    }
                }
            }
        }
    }
}
