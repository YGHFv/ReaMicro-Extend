package com.reamicro.fix.ui

import android.content.SharedPreferences
import android.annotation.SuppressLint
import org.json.JSONArray
import java.util.concurrent.Executors

internal enum class ModuleTaskSort { Manual, EnabledFirst }

internal data class ModuleTaskOrder(
    val mode: ModuleTaskSort = ModuleTaskSort.Manual,
    val manualKeys: List<String> = emptyList(),
)

internal data class ModuleTaskOrderEntry(val key: String, val enabled: Boolean)

internal fun moduleTaskOrderKey(accountId: String, taskType: String): String =
    "${accountId.length}:$accountId${taskType.length}:$taskType"

/** Stable partitioning keeps the manual order within both enabled and disabled groups. */
internal fun orderedModuleTaskKeys(
    order: ModuleTaskOrder,
    entries: List<ModuleTaskOrderEntry>,
): List<String> {
    val available = entries.associateBy { it.key }
    val manual = (order.manualKeys + entries.map { it.key }).distinct().filter { it in available }
    return if (order.mode == ModuleTaskSort.EnabledFirst) {
        manual.filter { available.getValue(it).enabled } +
            manual.filterNot { available.getValue(it).enabled }
    } else manual
}

/** Starting a drag retains exactly the order currently on screen, including enabled-first mode. */
internal fun ModuleTaskOrder.startManual(visibleKeys: List<String>): ModuleTaskOrder =
    copy(
        mode = ModuleTaskSort.Manual,
        manualKeys = visibleKeys.distinct() + manualKeys.filterNot { it in visibleKeys },
    )

internal fun ModuleTaskOrder.move(key: String, target: String): ModuleTaskOrder {
    val from = manualKeys.indexOf(key)
    val to = manualKeys.indexOf(target)
    if (from < 0 || to < 0 || from == to) return this
    val moved = manualKeys.toMutableList()
    moved.add(to, moved.removeAt(from))
    return copy(mode = ModuleTaskSort.Manual, manualKeys = moved)
}

internal class ModuleTaskOrderPreferences(private val preferences: SharedPreferences) {
    fun read(): ModuleTaskOrder {
        val mode = runCatching { ModuleTaskSort.valueOf(preferences.getString(KEY_MODE, null).orEmpty()) }
            .getOrDefault(ModuleTaskSort.Manual)
        val keys = runCatching {
            val array = JSONArray(preferences.getString(KEY_ORDER, "[]"))
            (0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? String)?.takeIf(String::isNotBlank)
            }.distinct()
        }.getOrDefault(emptyList())
        return ModuleTaskOrder(mode, keys)
    }

    @SuppressLint("UseKtx") // The commit Boolean is required to report persistence failure.
    fun write(order: ModuleTaskOrder) {
        check(preferences.edit()
            .putString(KEY_MODE, order.mode.name)
            .putString(KEY_ORDER, JSONArray(order.manualKeys).toString())
            .commit()) { "任务顺序保存失败" }
    }

    /** Ordered off-main commits survive Activity recreation and cannot overtake one another. */
    fun writeAsync(order: ModuleTaskOrder, onFailure: (Throwable) -> Unit) {
        writer.execute { runCatching { write(order) }.exceptionOrNull()?.let(onFailure) }
    }

    private companion object {
        val writer = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ModuleTaskOrder").apply { isDaemon = true }
        }
        const val KEY_MODE = "taskListSortMode"
        const val KEY_ORDER = "taskListManualOrder"
    }
}
