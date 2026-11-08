package com.reamicro.fix.hook

import android.app.Activity
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reamicro.fix.core.HostTypographyMetrics
import com.reamicro.fix.core.InjectedModuleContext

/** A host-themed full-page editor; the draft never enters the host S3 view model or saved state. */
internal class WebDavSyncConfigurationPage(
    private val activity: Activity,
    initial: Credentials,
    private val onSave: (Credentials) -> Unit,
    private val onClosed: () -> Unit,
) {
    data class Credentials(val address: String, val username: String, val password: String)

    private var draft by mutableStateOf(initial)
    private var busy by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)
    private var configurationEpoch by mutableIntStateOf(0)
    private var closed = false
    private var compose: ComposeView? = null
    private var recomposer: Recomposer? = null
    private var callbackRegistered = false
    private var back: (() -> Unit)? = null
    private var canceled by mutableStateOf(false)
    private var releaseSecure: (() -> Unit)? = null
    private val owner = ModuleComposeOwner(activity) { closeEmbedded() }
    private val callback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { configurationEpoch++ }
        override fun onLowMemory() = Unit
    }

    fun verifying() {
        if (closed || canceled) return
        busy = true
        problem = null
    }

    fun error(message: String) {
        if (closed || canceled) return
        busy = false
        problem = message
    }

    fun createEmbeddedView(onBack: () -> Unit): ComposeView {
        check(!closed && !canceled && !activity.isFinishing && !activity.isDestroyed) { "同步配置页已关闭" }
        compose?.let { return it }
        back = onBack
        try {
            owner.start()
            val view = ComposeView(InjectedModuleContext.create(activity))
            compose = view
            view.apply {
                // Credentials remain local, including when an autofill service is active.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                // The host window's owner belongs to another Compose/ClassLoader tree.
                val pageRecomposer = createLifecycleAwareWindowRecomposer(lifecycle = owner.lifecycle)
                this@WebDavSyncConfigurationPage.recomposer = pageRecomposer
                setParentCompositionContext(pageRecomposer)
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent { this@WebDavSyncConfigurationPage.ConfigurationContent() }
            }
            activity.registerComponentCallbacks(callback)
            callbackRegistered = true
            releaseSecure = HostSecurePageLease.acquire(activity.window)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { closeEmbedded() }
            })
            return view
        } catch (error: Throwable) {
            runCatching { closeEmbedded() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    fun cancelPending() {
        if (canceled) return
        canceled = true
        onClosed()
    }

    fun dismiss() {
        cancelPending()
        val pop = back
        if (pop == null) closeEmbedded() else pop()
    }

    /** Native NavHost retains this view during its exit animation, then releases it. */
    fun closeEmbedded() {
        if (closed) return
        closed = true
        val view = compose
        try {
            cancelPending()
        } finally {
            compose = null
            back = null
            draft = Credentials("", "", "")
            try {
                view?.disposeComposition()
            } finally {
                try {
                    recomposer?.cancel()
                    recomposer = null
                } finally {
                    try {
                        owner.close()
                    } finally {
                        try {
                            if (callbackRegistered) {
                                callbackRegistered = false
                                activity.unregisterComponentCallbacks(callback)
                            }
                        } finally {
                            releaseSecure?.invoke()
                            releaseSecure = null
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigurationContent() {
        val config = LocalConfiguration.current
        val native = remember(configurationEpoch, config) {
            StructureHost232Colors.snapshot(activity, StructureHome130Style.isDark(activity))
        }
        val scale = EmbeddedHostUi.scale(activity).takeIf { it.isFinite() && it > 0f } ?: 1f
        val typography = remember(configurationEpoch, config, scale) { WebDavSyncPageStyle.typography(activity, scale) }
        val pageColor = Color(native.contentArgb)
        val foreground = native.role("OnBackground")
        val border = Color(native.borderVariantArgb)
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val canSave = !closed && !canceled && WebDavConfigurationState.canSubmit(draft.address, busy)
        val submit = {
            if (canSave) {
                focus.clearFocus()
                keyboard?.hide()
                onSave(draft)
            }
        }
        MaterialTheme(colorScheme = native.controlsScheme(), typography = typography) {
            Column(Modifier.fillMaxSize().background(pageColor).windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )) {
                // Native Other S3 editor: a plain configuration title and a separate bottom action bar.
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).offset(y = (-2).dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dismiss() }, modifier = Modifier.size(44.dp)) {
                        Icon(StructureHome130Icons.Back, "返回", Modifier.size(28.dp), tint = foreground)
                    }
                    Text("配置WebDAV", Modifier.weight(1f).padding(start = 4.dp).padding(end = 16.dp).semantics { heading() },
                        style = typography.titleMedium, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = (20f * scale).dp),
                        verticalArrangement = Arrangement.spacedBy((18f * scale).dp)) {
                        Text(
                            "书架发生新增、修改或删除时，会将书籍、封面与阅读数据备份到 WebDAV 私有存储；" +
                                "各设备通过清单文件同步最新书架状态。WebDAV 配置仅保存在本机，各设备需单独设置。",
                            style = typography.bodyMedium, color = Color(native.captionArgb),
                        )
                        val shape = RoundedCornerShape((20f * scale).dp)
                        Surface(Modifier.fillMaxWidth().shadow((3f * scale).dp, shape, clip = false,
                            ambientColor = Color.Black.copy(alpha = .025f), spotColor = Color.Black.copy(alpha = .1f)),
                            shape = shape, color = Color(native.brightArgb), contentColor = foreground,
                            border = BorderStroke((.8f * scale).dp, border.copy(alpha = .68f))) {
                            Column(Modifier.padding(vertical = (8f * scale).dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = (24f * scale).dp, vertical = (12f * scale).dp),
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(WebDavSyncPageStyle.Link, null, Modifier.size(24.dp), tint = foreground)
                                    Spacer(Modifier.width((12f * scale).dp))
                                    Text("WebDAV", style = typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier.semantics { heading() })
                                }
                                HorizontalDivider(Modifier.padding(horizontal = (24f * scale).dp),
                                    color = border.copy(alpha = .58f))
                                Column(Modifier.padding(horizontal = (24f * scale).dp, vertical = (16f * scale).dp),
                                    verticalArrangement = Arrangement.spacedBy((12f * scale).dp)) {
                                    ConfigurationField("地址", draft.address, "https://example.com/dav/同步目录",
                                        KeyboardType.Uri, ImeAction.Next, scale, native,
                                        onNext = { focus.moveFocus(FocusDirection.Next) }) {
                                        draft = draft.copy(address = it); problem = null
                                    }
                                    ConfigurationField("用户名", draft.username, "匿名服务可留空",
                                        KeyboardType.Text, ImeAction.Next, scale, native,
                                        onNext = { focus.moveFocus(FocusDirection.Next) }) {
                                        draft = draft.copy(username = it); problem = null
                                    }
                                    ConfigurationField("密码", draft.password, "匿名服务可留空",
                                        KeyboardType.Password, ImeAction.Done, scale, native,
                                        onNext = submit) {
                                        draft = draft.copy(password = it); problem = null
                                    }
                                }
                            }
                        }
                        problem?.let { message ->
                            Text(message, color = native.role("Error"), style = typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Spacer(Modifier.height((8f * scale).dp))
                    }
                    // Native w10(5) bottom bar: 16/12 UDP padding and a full-width 52 UDP filled button.
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = (16f * scale).dp, vertical = (12f * scale).dp)) {
                        Button(onClick = submit, enabled = canSave,
                            modifier = Modifier.fillMaxWidth().heightIn(min = (52f * scale).dp).semantics {
                                contentDescription = if (busy) "正在验证 WebDAV 连接" else "验证并保存 WebDAV 配置"
                            }, shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(containerColor = foreground,
                                contentColor = Color(native.brightArgb),
                                disabledContainerColor = native.role("OnSurface").copy(alpha = .10f),
                                disabledContentColor = native.role("OnSurfaceVariant").copy(alpha = .38f))) {
                            Text(if (busy) "正在验证…" else "保存", style = typography.labelLarge)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConfigurationField(
        label: String, value: String, hint: String, keyboardType: KeyboardType,
        imeAction: ImeAction, scale: Float, native: StructureHost232Colors.Snapshot,
        onNext: () -> Unit, onChange: (String) -> Unit,
    ) {
        val foreground = native.role("OnBackground")
        val caption = Color(native.captionArgb)
        val inputFamily = remember(activity.assets) { WebDavSyncPageStyle.inputFamily(activity) }
        val style = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = inputFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy((4f * scale).dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = EmbeddedHostUi.materialFontFamily(activity, inputFamily),
            ), color = foreground)
            BasicTextField(value, onChange, enabled = !busy && !canceled && !closed, singleLine = true, textStyle = style.copy(color = foreground),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
                visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
                cursorBrush = SolidColor(native.role("Primary")),
                modifier = Modifier.fillMaxWidth().heightIn(min = (48f * scale).dp).border(
                    (1.2f * scale).dp, Color(native.borderVariantArgb).copy(alpha = .76f),
                    RoundedCornerShape((12f * scale).dp),
                ).semantics { contentDescription = "WebDAV $label" },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = (48f * scale).dp)
                        .padding(horizontal = (14f * scale).dp),
                        contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(hint, style = style, color = caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                })
        }
    }
}

/** Semantic theme roles use the existing HostSymbols/HostTypographyMetrics bridge. */
internal object WebDavSyncPageStyle {
    @Volatile private var styles: Map<String, HostTypographyMetrics.Style> = emptyMap()
    private const val INPUT_FONT_PATH =
        "composeResources/reamicro.composeapp.generated.resources/font/nanum_myeongjo.ttf"

    /** Native S3's BasicTextField names this family explicitly; MaterialText global overrides do not apply. */
    fun inputFamily(activity: Activity): FontFamily = runCatching {
        activity.assets.open(INPUT_FONT_PATH).use { }
        FontFamily(Font(path = INPUT_FONT_PATH, assetManager = activity.assets, weight = FontWeight.Normal))
    }.getOrElse { FontFamily.Default }

    fun capture(loader: ClassLoader, composer: Any) {
        runCatching {
            val metrics = HostTypographyMetrics(loader, composer)
            styles = listOf("TitleMedium", "TitleSmall", "BodyLarge", "BodyMedium", "LabelLarge", "LabelMedium")
                .associateWith(metrics::style)
        }
    }

    fun typography(activity: Activity, scale: Float): Typography {
        val base = BookImageDialogStyle.typography(activity, scale)
        fun HostTypographyMetrics.UnitValue.local(): TextUnit = if (em) value.em else value.sp
        fun style(role: String, fallback: TextStyle): TextStyle {
            val native = styles[role] ?: return fallback
            return fallback.copy(fontSize = native.fontSize?.local() ?: fallback.fontSize,
                lineHeight = native.lineHeight?.local() ?: fallback.lineHeight,
                letterSpacing = native.letterSpacing?.local() ?: fallback.letterSpacing,
                fontWeight = native.fontWeight?.let(::FontWeight) ?: fallback.fontWeight)
        }
        return base.copy(titleMedium = style("TitleMedium", base.titleSmall.copy(fontSize = (17f * scale).sp, fontWeight = FontWeight.Bold)),
            titleSmall = style("TitleSmall", base.titleSmall), bodyLarge = style("BodyLarge", base.bodyLarge),
            bodyMedium = style("BodyMedium", base.bodyMedium),
            labelLarge = style("LabelLarge", base.labelLarge), labelMedium = style("LabelMedium", base.labelMedium))
    }

    // The host's Other S3 heading uses Material Outlined.Link; path verified from the live APK via MT.
    val Link = ImageVector.Builder("Outlined.Link", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(
            "M17,7 h-4 v2 h4 c1.65,0 3,1.35 3,3 s-1.35,3 -3,3 h-4 v2 h4 c2.76,0 5,-2.24 5,-5 s-2.24,-5 -5,-5 Z " +
                "M11,15 H7 c-1.65,0 -3,-1.35 -3,-3 s1.35,-3 3,-3 h4 V7 H7 c-2.76,0 -5,2.24 -5,5 s2.24,5 5,5 h4 v-2 Z " +
                "M8,11 h8 v2 H8 Z",
        ).toNodes(), fill = SolidColor(Color.Black))
    }.build()
}
