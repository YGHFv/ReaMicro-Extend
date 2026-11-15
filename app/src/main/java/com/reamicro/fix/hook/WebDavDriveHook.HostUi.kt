package com.reamicro.fix.hook

import com.reamicro.fix.reload.runOnModuleUiThread

import com.reamicro.fix.core.HostNativeUi

import com.reamicro.fix.notification.setReaMicroSmallIcon

import android.app.Notification
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.Build
import android.widget.Toast
import com.reamicro.fix.R
import com.reamicro.fix.online.OnlineSourceEntry
import com.reamicro.fix.xposed.HookRuntime
import com.reamicro.fix.hook.webdav.*
import com.reamicro.fix.logging.logWebDav

internal fun WebDavDriveHook.clickableModifier(baseModifier: Any, name: String, onClick: () -> Unit): Any =
    HostNativeUi(classLoader).clickable(baseModifier, functionProxy(name, FUNCTION0_CLASS) { onClick(); targetUnit() })
internal fun WebDavDriveHook.arrangementStart(): Any = requireNotNull(com.reamicro.fix.core.HostUiBindings.current).value("rowStart")
internal fun WebDavDriveHook.alignmentCenterVertically(): Any = HostNativeUi(classLoader).centerVertically()
internal fun WebDavDriveHook.themeOnBackgroundVariant(composer: Any): Long =
    requireNotNull(com.reamicro.fix.core.HostUiBindings.current).call("onBackgroundVariant", colorScheme(composer)) as Long
private var onlineCompletionRefreshVectorCache: Any? = null

internal fun WebDavDriveHook.onlineCompletionUpdateImageVector(): Any? =
    onlineCompletionRefreshVectorCache ?: runCatching {
        HostWebDavVector(this).buildRefresh().also { onlineCompletionRefreshVectorCache = it }
    }.onFailure { HookRuntime.logError("$LOG_PREFIX refresh vector binding failed", it) }.getOrNull()

internal fun WebDavDriveHook.colorScheme(composer: Any): Any = HostNativeUi(classLoader).theme(composer, "getColorScheme")
internal fun WebDavDriveHook.typography(composer: Any): Any = HostNativeUi(classLoader).theme(composer, "getTypography")
internal fun WebDavDriveHook.modifierInstance(): Any = HostNativeUi(classLoader).modifier()
internal fun WebDavDriveHook.showToast(message: String) {
    val activity = activityProvider()
    if (activity != null) {
        activity.runOnModuleUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
        return
    }
    com.reamicro.fix.reload.ModuleResources.handler(Looper.getMainLooper()).post {
        currentContext()?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }
}

internal fun WebDavDriveHook.fillMaxSizeModifier(): Any = HostNativeUi(classLoader).fillSize(modifierInstance())
internal fun WebDavDriveHook.fillMaxWidthModifier(): Any = HostNativeUi(classLoader).fillWidth(modifierInstance())
internal fun WebDavDriveHook.startPaddingModifier(startDp: Int): Any =
    HostNativeUi(classLoader).padding(modifierInstance(), udp(startDp), 0f, 0f, 0f)
internal fun WebDavDriveHook.udp(value: Int): Float = HostNativeUi(classLoader).dp(value)
internal fun WebDavDriveHook.udp(value: Float): Float = HostNativeUi(classLoader).dp(value.toDouble())
internal fun WebDavDriveHook.emptyModifier(): Any = HostNativeUi(classLoader).modifier()
internal fun WebDavDriveHook.onlineCompletionRenderTypeForSource(source: OnlineSourceEntry, title: String): Int {
    val sourceId = source.id.ifBlank { source.name }
    val type = onlineCompletionRenderTypesBySource[sourceId] ?: synchronized(onlineCompletionRenderTypesBySource) {
        onlineCompletionRenderTypesBySource[sourceId] ?: run {
            var candidate = ONLINE_COMPLETION_RENDER_TYPE_BASE +
                ((sourceId.hashCode() and Int.MAX_VALUE) % ONLINE_COMPLETION_RENDER_TYPE_BUCKETS)
            while (true) {
                val existing = onlineCompletionRenderSourceByType[candidate]
                if (existing == null || existing == sourceId) {
                    onlineCompletionRenderSourceByType[candidate] = sourceId
                    onlineCompletionRenderTypesBySource[sourceId] = candidate
                    break
                }
                candidate += 1
                if (candidate >= ONLINE_COMPLETION_RENDER_TYPE_BASE + ONLINE_COMPLETION_RENDER_TYPE_BUCKETS) {
                    candidate = ONLINE_COMPLETION_RENDER_TYPE_BASE
                }
            }
            candidate
        }
    }
    onlineCompletionRenderTitles[type] = title.ifBlank { ONLINE_COMPLETION_TITLE }
    return type
}

internal fun WebDavDriveHook.isOnlineCompletionRenderType(type: Int): Boolean =
    onlineCompletionRenderTitles.containsKey(type)

internal fun cloudPathOf(value: Any?): String = value?.callString("getPath").orEmpty()

internal fun setOnlineCompletionSmallIcon(builder: Notification.Builder) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        runCatching {
            builder.setReaMicroSmallIcon()
        }.onSuccess {
            return
        }.onFailure {
            logWebDav("online completion module notification icon fallback: ${it.message}")
        }
    }
    @Suppress("DEPRECATION")
    builder.setSmallIcon(android.R.drawable.stat_sys_download)
}

internal fun roundedDrawable(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

internal fun WebDavDriveHook.composeColorToArgb(color: Long): Int =
    composeInterop.colorToArgb(color)

internal fun WebDavDriveHook.withAlpha(color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
