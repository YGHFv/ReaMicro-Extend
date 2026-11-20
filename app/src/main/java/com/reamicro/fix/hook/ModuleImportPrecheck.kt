package com.reamicro.fix.hook

internal data class ModuleDirectoryImportPlan(
    val allowed: Boolean,
    val uuid: String,
    val title: String,
    val token: Any? = null,
)

internal object ModuleImportPrecheck {
    @Volatile private var hook: ReaderImportOverwriteHook? = null

    @Synchronized
    fun attach(hook: ReaderImportOverwriteHook) {
        this.hook = hook
    }

    @Synchronized
    fun detach(hook: ReaderImportOverwriteHook) {
        if (this.hook === hook) this.hook = null
    }

    fun precheck(epubFile: java.io.File, sourceUri: String): Boolean =
        hook?.precheckModuleImport(epubFile, sourceUri) ?: true

    fun prepareDirectory(
        directory: java.io.File, sourceUri: String, uuid: String, title: String, continuingUuid: String?,
        repository: Any,
    ): ModuleDirectoryImportPlan = hook?.prepareModuleDirectoryImport(directory, sourceUri, uuid, title, continuingUuid, repository)
        ?: ModuleDirectoryImportPlan(true, uuid, title)

    fun discard(plan: ModuleDirectoryImportPlan) {
        hook?.discardModuleDirectoryImport(plan)
    }
}
