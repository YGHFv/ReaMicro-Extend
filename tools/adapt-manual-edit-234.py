#!/usr/bin/env python3
"""定点升级本地手动编辑源；保留既有 2.3.3 路径、文件编码及换行，不写其他功能源。"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'external-sources/src/main'


def patch(path, changes):
    raw = path.read_bytes()
    newline = '\r\n' if b'\r\n' in raw else '\n'
    for old, new, count in changes:
        before = old.replace('\n', newline).encode('utf-8')
        after = new.replace('\n', newline).encode('utf-8')
        if after in raw:
            continue
        if raw.count(before) != count:
            raise ValueError(f'Unrecognized local source: {path.name}: {old[:100]} ({raw.count(before)})')
        raw = raw.replace(before, after, count)
    path.write_bytes(raw)


patch(BASE / 'kotlin/com/reamicro/fix/hook/ManualAssociationUiHook.kt', [
    ('import com.reamicro.fix.core.HostSymbols\n',
     'import com.reamicro.fix.core.HostSymbols\nimport com.reamicro.fix.core.HostManualEditAbi\n', 1),
    ('    private val manualAssociationService = ManualAssociationService()',
     '    private val releaseAbi = HostManualEditAbi.forProfile(HostSymbols.profileId)\n'
     '    private var manualDialogRef: WeakReference<Dialog>? = null\n'
     '    private val manualAssociationService = ManualAssociationService()', 1),
    ('HostSymbols.profileId == "2.3.3"', 'releaseAbi != null', 6),
    ('    fun releaseForHotReload() {\n        hideFloatingButton()',
     '    fun releaseForHotReload() {\n        dismissManualDialog()\n'
     '        releaseScreenBookId = null\n        releaseTopBarVm.remove()\n'
     '        releaseButtonColors = null\n        globalTypefaceCache.clear()\n'
     '        failedGlobalTypefaceLogKeys.clear()\n        hideFloatingButton()', 1),
    ('                val vm = param.thisObject ?: return\n                hideFloatingButton()',
     '                val vm = param.thisObject ?: return\n                dismissManualDialog()\n                hideFloatingButton()', 1),
    ('                if (param.thisObject !== viewModelRef?.get()) return\n                hideFloatingButton()',
     '                if (param.thisObject !== viewModelRef?.get()) return\n'
     '                dismissManualDialog()\n                releaseScreenBookId = null\n'
     '                releaseButtonColors = null\n                pendingCoverFixes.remove(System.identityHashCode(param.thisObject))\n'
     '                hideFloatingButton()', 1),
    ('                    releaseScreenBookId = null\n',
     '                    releaseScreenBookId = null\n                    dismissManualDialog()\n', 1),
    ('        val relate = repository.getDeclaredMethod("k", classLoader.hostClass(BOOK_CLASS), third,',
     '        val relate = repository.getDeclaredMethod(requireNotNull(releaseAbi).relateMethod, classLoader.hostClass(BOOK_CLASS), third,', 1),
    ('        XposedBridge.logAlways("$LOG_PREFIX local manual-edit host 2.3.3 installed")',
     '        XposedBridge.logAlways("$LOG_PREFIX local manual-edit host ${HostSymbols.profileId} installed")', 1),
    ('        val screen = classLoader.hostClass("ReaMicroManual233.PublishScreen").getDeclaredMethod(\n'
     '            "a", classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS), Long::class.javaPrimitiveType,',
     '        val abi = requireNotNull(releaseAbi)\n'
     '        val screen = classLoader.loadClass(abi.publishScreen).getDeclaredMethod(\n'
     '            abi.screenMethod, classLoader.hostClass(NAV_GRAPH_SCOPE_CLASS), Long::class.javaPrimitiveType,', 1),
    ('        val closure = classLoader.hostClass("ReaMicroManual233.PublishTopBar")',
     '        val closure = classLoader.loadClass(abi.topBarContent)', 1),
    ('closure.getDeclaredField("a").getByte(param.thisObject) != 14.toByte()',
     'closure.getDeclaredField("a").getByte(param.thisObject) != abi.topBarBranch', 1),
    ('    private fun showManualDialog(viewModel: Any) {', '''    private fun dismissManualDialog() {
        val dialog = manualDialogRef?.get()
        manualDialogRef = null
        if (dialog == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) dialog.dismiss()
        else dialog.window?.decorView?.post { dialog.dismiss() }
    }

    private fun isCurrentManualTarget(viewModel: Any): Boolean =
        settingsProvider().canUseManualEdit && (releaseAbi == null ||
            (viewModelRef?.get() === viewModel && releaseScreenBookId != null &&
                releaseScreenBookId == (fieldValue(viewModel, "bookId") as? Number)?.toLong()))

    private fun showManualDialog(viewModel: Any) {''', 1),
    ('        if (!settingsProvider().canUseManualEdit) return\n        activity.runOnUiThread {\n            // If editing',
     '        if (!isCurrentManualTarget(viewModel)) return\n        activity.runOnUiThread {\n'
     '            if (!isCurrentManualTarget(viewModel)) return@runOnUiThread\n            dismissManualDialog()\n'
     '            // If editing', 1),
    ('            dialog.apply {\n                setOnShowListener {',
     '            manualDialogRef = WeakReference(dialog)\n'
     '            dialog.setOnDismissListener { if (manualDialogRef?.get() === dialog) manualDialogRef = null }\n'
     '            dialog.apply {\n                setOnShowListener {', 1),
    ('                    previewButton.setOnClickListener {\n                        val editedDraft',
     '                    previewButton.setOnClickListener {\n'
     '                        // 页面切换或源卸载后，旧表单不能向其他书籍写入预览。\n'
     '                        if (!isCurrentManualTarget(viewModel)) { dismiss(); return@setOnClickListener }\n'
     '                        val editedDraft', 1),
])
patch(BASE / 'kotlin/com/reamicro/fix/hook/ReaMicroThirdPartyBookFactory.kt', [
    ('if (HostSymbols.profileId == "2.3.3")',
     'if (com.reamicro.fix.core.HostManualEditAbi.forProfile(HostSymbols.profileId) != null)', 1),
])
patch(BASE / 'java/com/reamicro/fix/external/source/ManualEditFeatureProvider.java', [
    ('return "2.3.3".equals(com.reamicro.fix.core.HostSymbols.INSTANCE.getProfileId());',
     'return com.reamicro.fix.core.HostManualEditAbi.INSTANCE.forProfile(\n'
     '            com.reamicro.fix.core.HostSymbols.INSTANCE.getProfileId()) != null;', 1),
    ('Arrays.asList("manual_edit", "manual_edit_host_233")',
     'Arrays.asList("manual_edit", "manual_edit_host_233", "manual_edit_host_234_b1")', 1),
])
print('Manual source adapted for verified 2.3.3 / 2.3.4-b1 contracts')
