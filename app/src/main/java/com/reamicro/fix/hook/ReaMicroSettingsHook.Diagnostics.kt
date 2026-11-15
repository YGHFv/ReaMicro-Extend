package com.reamicro.fix.hook

import com.reamicro.fix.core.HookInstallReport
import com.reamicro.fix.hook.settings.ActionRow
import com.reamicro.fix.hook.settings.FUNCTION1_CLASS
import com.reamicro.fix.hook.settings.FUNCTION2_CLASS
import com.reamicro.fix.hook.settings.FUNCTION3_CLASS

internal data class ModuleDiagnosticRow(
    val key: String,
    val title: String,
    val detail: String,
)

internal fun moduleDiagnosticRows(entries: List<HookInstallReport.Entry>): List<ModuleDiagnosticRow> = buildList {
    val installed = entries.count { it.ok }
    val failures = entries.filterNot { it.ok }
    add(ModuleDiagnosticRow(
        "module_diagnostics_summary",
        "检查结果",
        if (entries.isEmpty()) "暂无检查结果" else
            "已安装 $installed / ${entries.size} 项" +
                if (failures.isEmpty()) "" else "，失败 ${failures.size} 项",
    ))
    failures.forEach { entry ->
        add(ModuleDiagnosticRow(
            "module_diagnostics_failure_${entry.id}",
            entry.id,
            entry.error ?: "安装失败，未提供错误详情",
        ))
    }
    entries.groupBy { it.feature }.forEach { (feature, steps) ->
        add(ModuleDiagnosticRow(
            "module_diagnostics_feature_$feature",
            feature,
            "已安装 ${steps.count { it.ok }} / ${steps.size} 项",
        ))
    }
}

internal fun ReaMicroSettingsHook.renderModuleDiagnosticsContent(innerPaddings: Any, composer: Any) {
    aboutVersionValue()
    val rows = moduleDiagnosticRows(HookInstallReport.snapshot())
    val listContent = functionProxy("ModuleDiagnosticsList", FUNCTION1_CLASS) { args ->
        val scope = args?.getOrNull(0) ?: return@functionProxy targetUnit()
        addLazyItem(scope, 0x524D4452, itemKey = "module_diagnostics_refresh") { itemComposer ->
            renderHostActionCard(listOf(ActionRow(
                key = "module_diagnostics_refresh_action",
                title = "刷新结果",
                onClick = { bumpAboutVersion() },
            )), itemComposer)
        }
        rows.forEach { row ->
            addLazyItem(scope, row.key.hashCode(), itemKey = row.key) { itemComposer ->
                renderModuleDiagnosticCard(row, itemComposer)
            }
        }
        targetUnit()
    }
    renderHostLazyColumn(innerPaddings, listContent, composer)
}

private fun ReaMicroSettingsHook.renderModuleDiagnosticCard(row: ModuleDiagnosticRow, composer: Any) {
    val content = functionProxy("ModuleDiagnosticsCard${row.key}", FUNCTION3_CLASS) { args ->
        val innerComposer = args?.getOrNull(1) ?: return@functionProxy targetUnit()
        val headline = composableLambda(row.key.hashCode(), FUNCTION2_CLASS) { values ->
            val textComposer = values?.getOrNull(0) ?: return@composableLambda targetUnit()
            renderHostText(row.title, textComposer)
            targetUnit()
        }
        val supporting = composableLambda(row.key.hashCode() xor 0x524D4449, FUNCTION2_CLASS) { values ->
            val textComposer = values?.getOrNull(0) ?: return@composableLambda targetUnit()
            renderHostSupportingText(row.detail, textComposer)
            targetUnit()
        }
        // Native ListItem measures wrapped text; a fixed row height clips compact windows and large fonts.
        nativeUi.listItem(
            headline = headline,
            modifier = nativeUi.fillWidth(modifierInstance()),
            composer = innerComposer,
            colors = transparentListItemColors(innerComposer),
            supporting = supporting,
            defaultMask = listItemDefaultMask(hasSupporting = true, hasTrailing = false),
        )
        targetUnit()
    }
    nativeUi.column(settingsCardModifier(composer), arrangementTop(), alignmentStart(), content, composer)
}
