package com.reamicro.fix.hook

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.core.HostManualEditBindings
import com.reamicro.fix.core.HostUiBindings
import com.reamicro.fix.reload.ModuleResources
import com.reamicro.fix.settings.XposedModuleSettings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** Adds the hidden tap gesture only to the real host About screen's logo. */
internal class ManualEditUnlockHook(
    private val activity: () -> Activity?,
    private val settings: XposedModuleSettings,
) {
    private data class Frame(val about: Boolean, var imageSeen: Boolean = false)
    private val frames = ThreadLocal<ArrayDeque<Frame>>()
    private var lastTap = 0L
    private var taps = 0
    private var tapTargetUnlocked: Boolean? = null
    private var lastActivity = WeakReference<Activity>(null)
    private var toast: Toast? = null
    @Volatile private var retired = false

    fun install(): Boolean = HookInstallReport.install("ManualEditUnlockHook", "hostAboutLogo") {
        val host = requireNotNull(HostManualEditBindings.unlock)
        val ui = requireNotNull(HostUiBindings.current)
        check(host.type("modifier") == ui.modifierClass && host.type("composer") == ui.composerClass)
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(host.method("content"), object : HookCallback() {
                override fun before(param: Call) {
                    val stack = frames.get() ?: ArrayDeque<Frame>().also(frames::set)
                    stack.addLast(Frame(host.isCallback("content", param.thisObject)))
                }
                override fun after(param: Call) {
                    frames.get()?.let { stack -> stack.pollLast(); if (stack.isEmpty()) frames.remove() }
                }
            })
            handles += HookRuntime.hookMethod(host.method("image"), object : HookCallback() {
                override fun before(param: Call) {
                    val frame = frames.get()?.peekLast() ?: return
                    if (!frame.about || frame.imageSeen || retired) return
                    frame.imageSeen = true
                    val slot = host.number("image.modifier")
                    val original = param.args[slot] ?: ui.value("modifier")
                    val onClick = ui.callback(ui.type("function0")) { tapped() }
                    val modifier = requireNotNull(ui.call("clickable", original, true, null, null, onClick, 6))
                    host.replaceArgument(param.args, "image", modifier)
                }
            })
            listOf("root", "content").map(host::method).forEach(HookRuntime::deoptimize)
            ModuleResources.cleanup {
                retired = true; frames.remove(); taps = 0; lastTap = 0; tapTargetUnlocked = null
                toast?.cancel(); toast = null; lastActivity.clear()
            }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
        true
    }

    private fun tapped() {
        if (retired) return
        val activity = activity()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        val snapshot = settings.snapshot()
        val unlocked = snapshot.associationManualEditUnlocked
        val now = SystemClock.uptimeMillis()
        if (lastActivity.get() !== activity || now - lastTap > 2500L || tapTargetUnlocked != !unlocked) taps = 0
        tapTargetUnlocked = !unlocked
        lastActivity = WeakReference(activity)
        lastTap = now
        taps++
        val message = when {
            taps >= 7 -> {
                taps = 0
                tapTargetUnlocked = null
                if (!settings.setAssociationManualEditUnlocked(!unlocked)) {
                    "保存扩展功能设置失败，请重试"
                } else if (unlocked) {
                    "已关闭手动编辑并隐藏入口"
                } else {
                    "已解锁手动编辑，可在关联补全中开启"
                }
            }
            taps >= 4 -> "再点击 ${7 - taps} 次可${if (unlocked) "关闭" else "解锁"}扩展功能"
            else -> null
        }
        if (message != null) {
            toast?.cancel()
            toast = Toast.makeText(activity, message, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
}
