package com.reamicro.fix.hook

import android.view.KeyEvent
import android.view.View
import com.reamicro.fix.core.HostSymbols
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.hostMethod
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

internal fun ReaderHook.hookReaderBackKey() {
    if (!HostSymbols.usesRc3Abi) return
    // RC3 根焦点节点把 BACK 按下解释为 FocusDirection.Exit，松开却不消费。
    // 正文及普通底栏放行按下事件，让 Activity 接收完整按键并执行原有返回链。
    val rootKeyNode = classLoader.loadClass("tf")
    val ownerField = rootKeyNode.getDeclaredField("p").apply { isAccessible = true }
    val handler = rootKeyNode.getDeclaredMethod("F", KeyEvent::class.java)
    val stateField = classLoader.hostClass("app.zhendong.reamicro.arch.MVIViewModel").hostField("uiState")
    val stateClass = classLoader.hostClass("app.zhendong.reamicro.ui.reader.ReaderUiState")
    val statusField = stateClass.hostField("status")
    val sheetField = stateClass.hostField("sheetStatus")
    val hiddenClass = classLoader.loadClass(if (HostSymbols.profileId == "2.3.3") "lrb" else "mrb")
    val selectionClass = classLoader.hostClass("org.epub.ui.EpubSelectionController")
    val selectionMethod = selectionClass.hostMethod("getSelection")
    XposedBridge.hookMethod(handler, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val event = param.args?.getOrNull(0) as? KeyEvent ?: return
            if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_DOWN) return
            val activity = activityProvider() ?: return
            if (!isActivityResumedProvider() || activity.isFinishing || activity.isDestroyed) return
            val view = ownerField.get(param.thisObject) as? View ?: return
            if (!view.isShown || view.rootView !== activity.window.decorView) return
            val vm = currentViewModelRef?.get() ?: return
            val state = callNoArg(stateField.get(vm), "getValue") ?: return
            if (!stateClass.isInstance(state)) return
            val selection = currentSelectionControllerRef?.get() ?: return
            if (!selectionClass.isInstance(selection)) return
            val release = shouldReleaseReaderBackKey(
                profile = HostSymbols.profileId,
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                cancelled = event.isCanceled,
                readerStatus = readerUiStatusName(statusField.get(state)),
                sheetHidden = hiddenClass.isInstance(sheetField.get(state)),
                selectionKnownEmpty = selectionMethod.invoke(selection) == null,
                editorOrDialogOpen = selectionEditPreparing.get() || selectionEditSaving.get() ||
                    searchPageDialogRef?.get()?.isShowing == true,
            )
            if (release) param.result = false
        }
    })
    XposedBridge.log("ReaMicro RC3 reader BACK focus pass-through installed")
}
