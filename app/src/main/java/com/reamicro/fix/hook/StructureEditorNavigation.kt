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
    fun closeFile(path: String) { cancel(); closingFile = path }
    fun takeClosingFile(): String? = closingFile.also { closingFile = null }
    fun home() { destination = StructureEditorDestination.Home }
    fun file(path: String) { destination = StructureEditorDestination.File(path) }
    fun cancel() { destination = StructureEditorDestination.Context; closingFile = null }
}
