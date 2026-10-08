package com.reamicro.fix.epub.editor

import java.util.concurrent.CancellationException

internal class CommittedFileFeedback(
    val message: String,
    val changed: Boolean = true,
    private val logFailure: (Exception) -> Unit = {},
) {
    private val warnings = linkedSetOf<String>()
    val warning: String
        get() = if (warnings.isEmpty()) "" else
            "$message；${warnings.joinToString("；")}。请刷新后检查，不要重复操作。"
    val displayMessage: String get() = warning.ifBlank { message }

    fun addWarning(detail: String) {
        if (detail.isNotBlank()) warnings.add(detail)
    }

    fun followUp(warning: String, action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addWarning(warning)

            runCatching { logFailure(e) }
        }
    }
}
