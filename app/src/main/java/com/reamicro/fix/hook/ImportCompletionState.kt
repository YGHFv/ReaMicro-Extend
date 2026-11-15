package com.reamicro.fix.hook

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One import keeps its original visible task until the native worker has a terminal result. */
internal object ImportCompletionState {
    private val activeCaches = ConcurrentHashMap.newKeySet<File>()
    fun retain(file: File) { activeCaches += file.absoluteFile }
    fun release(file: File) { activeCaches -= file.absoluteFile }
    fun activeFiles(): List<File> = activeCaches.toList()

    fun acceptsTransition(previous: String?, next: String, completedImport: Boolean = false): Boolean = when (previous) {
        "Success" -> next == "Success"
        "Cancelled" -> next == "Cancelled" || (completedImport && next == "Success")
        else -> true
    }

    /** Network/copy occupies 0..80; actual native extraction/catalog/commit occupies 80..99. */
    fun progress(nativeProgress: Int): Int = 80 + nativeProgress.coerceIn(0, 100) * 19 / 100

    fun keepPublishedContent(nativeSuccess: Boolean, existingRowWasUpdated: Boolean): Boolean =
        nativeSuccess || existingRowWasUpdated

    fun forwardProgress(
        progress: Int,
        checkCurrent: () -> Unit,
        notify: () -> Unit,
        afterCommitError: (Throwable) -> Unit,
    ) {
        if (progress < 100) {
            checkCurrent()
            notify()
        } else {
            // Both hosts emit 100 only after the native row and catalog have completed.
            try { notify() } catch (failure: Throwable) { afterCommitError(failure) }
        }
    }
}
