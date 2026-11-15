package com.reamicro.fix.core

/** Functional readiness and the historical symbol inventory are separate evidence.
 * Every registered feature is required. Catalog gaps remain visible and never
 * count as a complete catalog, even when no active feature consumes those APIs. */
internal object HostScanCoverage {
    val requiredLogicalKeys: Set<String> = setOf(
        "class:kotlin.coroutines.Continuation",
        "class:kotlin.jvm.functions.Function0",
        "class:kotlin.jvm.functions.Function3",
        "class:androidx.compose.ui.graphics.vector.ImageVector",
        "class:androidx.compose.foundation.layout.WindowInsets",
    ) + listOf("Reader", "Menu", "Catalog", "Font", "Highlight", "MarginEditor", "Theme", "TypeSetting")
        .map { "class:app.zhendong.reamicro.ui.reader.UiStatus\$$it" }

    fun failures(
        requiredGroups: Set<String>,
        availableGroups: Set<String>,
        symbols: Set<String>,
        queryErrors: List<String>,
    ): List<String> = (queryErrors +
        (requiredGroups - availableGroups).sorted().map { "$it: missing feature query result" } +
        (requiredLogicalKeys - symbols).sorted().map { "$it: required runtime symbol unavailable" }).distinct()
}
