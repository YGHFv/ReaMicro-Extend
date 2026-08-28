package com.reamicro.fix.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TravelingMerchantEndTimeHook(
    private val classLoader: ClassLoader,
) {
    private val activeTripEndTimeSeconds = ThreadLocal<Long>()

    fun install() {
        val sheetClass = Class.forName(TRAVELING_MERCHANT_SHEET_CLASS, false, classLoader)
        val tripClass = Class.forName(TRAVELING_MERCHANT_TRIP_CLASS, false, classLoader)
        val endTimeGetter = tripClass.declaredMethods.first {
            it.name == END_TIME_GETTER && it.parameterTypes.isEmpty()
        }.apply { isAccessible = true }
        val businessCardsMethod = sheetClass.declaredMethods.first(::isTripBusinessCardsMethod).apply {
            isAccessible = true
        }
        val loadoutRowMethod = sheetClass.declaredMethods.first(::isLoadoutRowMethod).apply {
            isAccessible = true
        }

        hookTripEndTimeContext(businessCardsMethod, endTimeGetter)
        hookLoadoutEndTimeText(loadoutRowMethod)
        XposedBridge.log("$LOG_PREFIX traveling merchant end time hook installed")
    }

    private fun hookTripEndTimeContext(method: Method, endTimeGetter: Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                activeTripEndTimeSeconds.remove()
                val trip = param.args?.getOrNull(0) ?: return
                val endTimeSeconds = runCatching {
                    (endTimeGetter.invoke(trip) as? Number)?.toLong()
                }.onFailure {
                    XposedBridge.log("$LOG_PREFIX failed to read merchant end time: ${it.stackTraceToString()}")
                }.getOrNull() ?: return
                if (endTimeSeconds > 0L) {
                    activeTripEndTimeSeconds.set(endTimeSeconds)
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                activeTripEndTimeSeconds.remove()
            }
        })
    }

    private fun hookLoadoutEndTimeText(method: Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val endTimeSeconds = activeTripEndTimeSeconds.get() ?: return
                val args = param.args ?: return
                if (args.getOrNull(DURATION_TEXT_ARGUMENT_INDEX) !is String) return

                // 只替换“计程”的显示参数，不改写行商数据对象及其任何进度字段。
                args[DURATION_TEXT_ARGUMENT_INDEX] = formatTravelingMerchantEndTime(endTimeSeconds)
            }
        })
    }

    private fun isTripBusinessCardsMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == TRIP_BUSINESS_CARDS_METHOD &&
            types.size == TRIP_BUSINESS_CARDS_PARAMETER_COUNT &&
            types.getOrNull(0)?.name == TRAVELING_MERCHANT_TRIP_CLASS &&
            types.getOrNull(2) == String::class.java &&
            types.getOrNull(3) == Float::class.javaPrimitiveType
    }

    private fun isLoadoutRowMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == LOADOUT_ROW_METHOD &&
            types.size == LOADOUT_ROW_PARAMETER_COUNT &&
            types.getOrNull(0) == String::class.java &&
            types.getOrNull(1) == String::class.java &&
            types.getOrNull(DURATION_TEXT_ARGUMENT_INDEX) == String::class.java
    }

    private companion object {
        const val LOG_PREFIX = "ReaMicro LSP"
        const val TRAVELING_MERCHANT_SHEET_CLASS =
            "app.zhendong.reamicro.ui.shrine.components.TravelingMerchantSheetKt"
        const val TRAVELING_MERCHANT_TRIP_CLASS =
            "app.zhendong.reamicro.data.res.community.TravelingMerchantTrip"
        const val END_TIME_GETTER = "getEndTime"
        const val TRIP_BUSINESS_CARDS_METHOD = "MerchantTripBusinessCards"
        const val LOADOUT_ROW_METHOD = "MerchantLoadoutRow"
        const val TRIP_BUSINESS_CARDS_PARAMETER_COUNT = 6
        const val LOADOUT_ROW_PARAMETER_COUNT = 11
        const val DURATION_TEXT_ARGUMENT_INDEX = 4
    }
}

internal fun formatTravelingMerchantEndTime(
    epochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = END_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds).atZone(zoneId))

private val END_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
