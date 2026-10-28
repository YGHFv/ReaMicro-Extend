package com.reamicro.fix.core

/** B4 删除了 getter/component/copy；按构造器和 toString 双重核实的字段顺序复制。 */
object HostHomeSearchState {
    const val CLASS = "app.zhendong.reamicro.ui.home.HomeUiState"

    val b4Properties = listOf(
        "model", "user", "searching", "showLibrary", "groups", "bookshelfGroups",
        "hasBooks", "localSearchResults", "cloudSearchResults", "passwordRequest",
        "oldBooksToMigrate", "migrationStatus", "migrationResult", "isMigrating",
        "bookshelfMultiSelect", "autoScanDirUri", "autoScanDirLabel",
    )

    fun isB4(state: Any): Boolean =
        HostSymbols.abiProfileId == "2.3.3-b4" && HostSymbols.logicalClass(state.javaClass.name) == CLASS

    fun cloudResults(state: Any): Map<*, *>? =
        state.javaClass.hostField("cloudSearchResults").get(state) as? Map<*, *>

    fun copyCloudResults(state: Any, results: Map<Any?, Any?>): Any {
        check(isB4(state)) { "Unverified home search state" }
        return HostExactRecordCopy(state.javaClass, b4Properties)
            .copy(state, mapOf("cloudSearchResults" to results))
    }
}
