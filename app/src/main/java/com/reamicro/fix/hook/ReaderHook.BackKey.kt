package com.reamicro.fix.hook

import android.view.KeyEvent
import android.view.View
import com.reamicro.fix.core.HostReaderGuardBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime

internal fun ReaderHook.hookReaderBackKey() {
    val symbols = requireNotNull(HostReaderGuardBindings.backKey) { "Reader back-key contract unavailable" }
    val ownerField = symbols.field("owner")
    val handler = symbols.method("key")
    val stateClass = symbols.type("state")
    val statusField = symbols.field("status")
    val sheetField = symbols.field("sheet")
    val hiddenClass = symbols.type("hidden")
    val selectionClass = symbols.type("selection")
    val selectionMethod = symbols.method("selection")
    HookRuntime.hookMethod(handler, object : HookCallback() {
        override fun before(param: Call) {
            val event = param.args?.getOrNull(0) as? KeyEvent ?: return
            if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_DOWN) return
            val activity = activityProvider() ?: return
            if (!isActivityResumedProvider() || activity.isFinishing || activity.isDestroyed) return
            val view = ownerField.get(param.thisObject) as? View ?: return
            if (!view.isShown || view.rootView !== activity.window.decorView) return
            val vm = currentViewModelRef?.get() ?: return
            val state = symbols.method("currentState").invoke(vm) ?: return
            if (!stateClass.isInstance(state)) return
            val selection = currentSelectionControllerRef?.get() ?: return
            if (!selectionClass.isInstance(selection)) return
            val release = shouldReleaseReaderBackKey(
                contractAvailable = true,
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
    HookRuntime.log("ReaMicro reader BACK focus pass-through installed from current APK")
}
