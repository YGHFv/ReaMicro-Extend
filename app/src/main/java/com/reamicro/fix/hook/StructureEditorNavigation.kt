package com.reamicro.fix.hook

internal object StructureEditorContext {
    fun previewBehindEditor(path: String?, kind: String?): String? = path.takeIf { kind == "navigation" }
}

internal sealed interface StructureEditorDestination {
    data object Context : StructureEditorDestination
    data object Home : StructureEditorDestination
    data class File(val path: String) : StructureEditorDestination
}
internal class StructureEditorReturnRoute {
    var destination: StructureEditorDestination = StructureEditorDestination.Context
        private set
    private var closingFile: String? = null
    private var exitingDestination: StructureEditorDestination? = null
    fun closeFile(path: String) { cancel(); closingFile = path }
    fun takeClosingFile(): String? = closingFile.also { closingFile = null }
    fun home() { if (exitingDestination == null) destination = StructureEditorDestination.Home }
    fun file(path: String) { if (exitingDestination == null) destination = StructureEditorDestination.File(path) }
    fun takeExitDestination(): StructureEditorDestination? {
        if (exitingDestination != null) return null
        return destination.also { exitingDestination = it }
    }
    fun cancel() {
        destination = StructureEditorDestination.Context
        closingFile = null
        exitingDestination = null
    }
}
