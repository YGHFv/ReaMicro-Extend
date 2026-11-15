package com.reamicro.fix.hook

import com.reamicro.fix.core.HostHomeBindings
import com.reamicro.fix.xposed.HookCallback
import com.reamicro.fix.xposed.HookRuntime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TravelingMerchantEndTimeHook(private val classLoader: ClassLoader) {
    private val contexts = ThreadLocal<java.util.ArrayDeque<Long>>()
    fun install() {
        val host = requireNotNull(HostHomeBindings.merchant) { "Merchant end-time contract unavailable" }
        check(host.type("trip").classLoader === classLoader)
        val handles = arrayListOf<io.github.libxposed.api.XposedInterface.HookHandle>()
        try {
            handles += HookRuntime.hookMethod(host.method("cards"), object : HookCallback() {
                override fun before(param: Call) {
                    val trip = param.args[host.index("trip")]
                    val endTime = if (host.type("trip").isInstance(trip)) host.field("endTime").getLong(trip) else 0L
                    (contexts.get() ?: java.util.ArrayDeque<Long>().also(contexts::set)).addLast(endTime)
                }
                override fun after(param: Call) {
                    contexts.get()?.let { stack ->
                        if (stack.isNotEmpty()) stack.removeLast()
                        if (stack.isEmpty()) contexts.remove()
                    }
                }
            })
            handles += HookRuntime.hookMethod(host.method("row"), object : HookCallback() {
                override fun before(param: Call) {
                    val endTime = contexts.get()?.peekLast()?.takeIf { it > 0L } ?: return
                    val slot = host.index("duration")
                    param.args[slot] = formatTravelingMerchantEndTime(endTime)
                    val changed = host.index("changed")
                    param.args[changed] = (param.args[changed] as Int) and (14 shl (3 * slot)).inv()
                    val defaults = host.index("defaults")
                    param.args[defaults] = (param.args[defaults] as Int) and (1 shl slot).inv()
                }
            })
            HookRuntime.deoptimize(host.method("cards"))
            HookRuntime.deoptimize(host.method("row"))
            com.reamicro.fix.reload.ModuleResources.cleanup { contexts.remove() }
        } catch (error: Throwable) {
            handles.asReversed().forEach { runCatching { it.unhook() }.onFailure(error::addSuppressed) }
            throw error
        }
    }
}
internal fun formatTravelingMerchantEndTime(
    epochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = END_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds).atZone(zoneId))

private val END_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
