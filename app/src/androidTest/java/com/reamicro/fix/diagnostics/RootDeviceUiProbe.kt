package com.reamicro.fix.diagnostics

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reamicro.fix.R
import com.reamicro.fix.cloud.root.RootTaskBridge
import com.reamicro.fix.ui.ModuleMainActivity
import org.json.JSONObject
import java.io.File

/** Accessibility-driven smoke check; opens panels and performs a read-only Root check only. */
internal fun Instrumentation.probeRootUi(report: JSONObject) {
    val context = targetContext
    startActivitySync(Intent(context, ModuleMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    SystemClock.sleep(1_000)
    uiAutomation.serviceInfo?.let {
        it.flags = it.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        uiAutomation.serviceInfo = it
    }
    fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        return buildList {
            add(root)
            for (i in 0 until root.childCount) addAll(nodes(root.getChild(i)))
        }
    }
    fun text(id: Int) = context.getString(id)
    fun click(label: String): Boolean {
        for (node in nodes(uiAutomation.rootInActiveWindow).filter {
            it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
        }.asReversed()) {
            var candidate: AccessibilityNodeInfo? = node
            repeat(5) {
                val item = candidate
                // The custom liquid bar deliberately disables clicks on the selected tab.
                if (label == text(R.string.tab_settings) && item?.isSelected == true) return true
                if (item != null && item.isClickable && item.isEnabled &&
                    item.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                candidate = item?.parent
            }
        }
        return false
    }
    fun scroll(forward: Boolean): Boolean =
        nodes(uiAutomation.rootInActiveWindow).firstOrNull {
            it.isVisibleToUser && it.isScrollable && it.className?.toString() == "android.widget.ScrollView"
        }?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
    fun findAndClick(label: String): Boolean {
        repeat(8) {
            if (click(label)) { SystemClock.sleep(500); return true }
            if (!scroll(true)) return false
            SystemClock.sleep(350)
        }
        return false
    }
    var settingsReady = false
    repeat(20) {
        if (!settingsReady) {
            settingsReady = click(text(R.string.tab_settings))
            if (!settingsReady) SystemClock.sleep(250)
        }
    }
    report.put("activeWindowPackage", uiAutomation.rootInActiveWindow?.packageName)
    report.put("visibleNodeCount", nodes(uiAutomation.rootInActiveWindow).size)
    check(settingsReady) { "Settings tab not reachable" }
    SystemClock.sleep(600)
    repeat(8) { if (scroll(false)) SystemClock.sleep(200) }
    report.put("expectedRootLabel", text(R.string.root_inspect))
    report.put("visibleTexts", nodes(uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.takeLast(18).joinToString(" | "))
    check(findAndClick(text(R.string.root_inspect))) { "Root check row not reachable" }
    val deadline = SystemClock.elapsedRealtime() + 35_000
    while (SystemClock.elapsedRealtime() < deadline) {
        val texts = nodes(uiAutomation.rootInActiveWindow).mapNotNull { it.text?.toString() }
        if (texts.any { it.contains(text(R.string.execution_root_granted)) }) break
        SystemClock.sleep(300)
    }
    report.put("uiRootGranted", RootTaskBridge.lastInspection?.rootAvailable == true)
    val checked = RootTaskBridge.lastInspection?.checkedAt
    repeat(8) { if (scroll(false)) SystemClock.sleep(150) }
    check(findAndClick(text(R.string.root_module_title))) { "Module sheet not reachable" }
    val moduleNodes = nodes(uiAutomation.rootInActiveWindow)
    report.put("moduleSheetVisible", moduleNodes.any { it.text?.toString() == text(R.string.execution_install_update) })
    fun footerGap(): Int {
        val root = uiAutomation.rootInActiveWindow ?: return -1
        val frame = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val close = nodes(root).firstOrNull { it.text?.toString() in setOf(text(R.string.action_close), text(R.string.action_cancel)) } ?: return -1
        var button: AccessibilityNodeInfo? = close
        while (button != null && !button.isClickable) button = button.parent
        val bounds = android.graphics.Rect()
        (button ?: close).getBoundsInScreen(bounds)
        return frame.bottom - bounds.bottom
    }
    report.put("density", context.resources.displayMetrics.density)
    report.put("moduleFooterBottomGapPx", footerGap())
    check(click(text(R.string.action_close))) { "Module close button not reachable" }
    SystemClock.sleep(400)
    check(findAndClick(text(R.string.root_diagnostics))) { "Diagnostics sheet not reachable" }
    report.put("diagnosticsStructured", nodes(uiAutomation.rootInActiveWindow)
        .any { it.text?.toString() == text(R.string.root_versions) })
    report.put("diagnosticsFooterBottomGapPx", footerGap())
    check(click(text(R.string.action_close)) || click(text(R.string.action_cancel))) { "Diagnostics dismiss button not reachable" }
    SystemClock.sleep(400)
    report.put("lastRootEvidenceRetained", checked != null && checked == RootTaskBridge.lastInspection?.checkedAt)
    report.put("noModeSelector", nodes(uiAutomation.rootInActiveWindow).none {
        it.text?.toString()?.contains("当前唤醒方式") == true || it.text?.toString()?.contains("免 Root") == true
    })
    val directory = File(context.filesDir, "device-diagnostics").apply { mkdirs() }
    uiAutomation.takeScreenshot()?.let { bitmap ->
        File(directory, "ui-smoke.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
