package com.reamicro.fix.core

object HookInstallReport {

    data class Entry(
        val feature: String,
        val name: String,
        val ok: Boolean,
        val error: String?,
    ) {
        val id: String get() = "$feature.$name"
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    fun install(feature: String, name: String, block: () -> Unit): Boolean =
        installResult(feature, name) {
            block()
            Unit
        }

    fun installResult(feature: String, name: String, block: () -> Any?): Boolean {
        val before = snapshot().mapTo(hashSetOf()) { it.id }
        val result = runCatching(block)
        val nestedFailures = snapshot().filter { it.id !in before && !it.ok }
        val nestedError = nestedFailures
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.id }
            ?.let { IllegalStateException("nested hook steps failed: $it") }
        val success = result.isSuccess && result.getOrNull() != false && nestedFailures.isEmpty()
        record(feature, name, success, result.exceptionOrNull() ?: nestedError)
        return success
    }

    fun installAll(feature: String, steps: List<Pair<String, () -> Unit>>) {
        steps.forEach { (name, block) -> install(feature, name, block) }
    }

    fun record(feature: String, name: String, ok: Boolean, error: Throwable? = null) {
        val entry = Entry(
            feature = feature,
            name = name,
            ok = ok,
            error = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" },
        )
        synchronized(lock) { entries[entry.id] = entry }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.values.toList() }

    fun failures(): List<String> = snapshot().filterNot { it.ok }.map { it.id }

    fun summaryLine(): String {
        val all = snapshot()
        val ok = all.count { it.ok }
        val failed = all.filterNot { it.ok }
        return "hook installed $ok/${all.size}" + if (failed.isNotEmpty()) {
            ", failed: ${failed.joinToString(", ") { it.id }}"
        } else ""
    }

    fun failureDetails(): List<String> =
        snapshot().filterNot { it.ok }.map { "${it.id} <- ${it.error ?: "unknown"}" }

    fun featureSummaries(): List<String> =
        snapshot().groupBy { it.feature }.map { (feature, list) ->
            "$feature ${list.count { it.ok }}/${list.size}"
        }

}
