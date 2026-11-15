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

    private val scopes = ThreadLocal<java.util.ArrayDeque<MutableMap<String, Entry>>>()

    fun install(feature: String, name: String, block: () -> Any?): Boolean =
        installResult(feature, name, block)

    fun installResult(feature: String, name: String, block: () -> Any?): Boolean {
        val nested = linkedMapOf<String, Entry>()
        val stack = scopes.get() ?: java.util.ArrayDeque<MutableMap<String, Entry>>().also(scopes::set)
        stack.addLast(nested)
        val result = try { runCatching(block) } finally {
            stack.removeLast()
            if (stack.isEmpty()) scopes.remove()
        }
        val nestedFailures = nested.values.filterNot { it.ok }
        val nestedError = nestedFailures
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ") { it.id }
            ?.let { IllegalStateException("nested hook steps failed: $it") }
        val success = result.isSuccess && result.getOrNull() != false && nestedFailures.isEmpty()
        record(feature, name, success, result.exceptionOrNull() ?: nestedError ?:
            if (result.getOrNull() == false) IllegalStateException("installation returned false") else null)
        return success
    }

    fun installAll(feature: String, steps: List<Pair<String, () -> Any?>>): Boolean =
        steps.map { (name, block) -> install(feature, name, block) }.all { it }

    fun record(feature: String, name: String, ok: Boolean, error: Throwable? = null) {
        val entry = Entry(
            feature = feature,
            name = name,
            ok = ok,
            error = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" },
        )
        synchronized(lock) { entries[entry.id] = entry }
        scopes.get()?.forEach { it[entry.id] = entry }
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
