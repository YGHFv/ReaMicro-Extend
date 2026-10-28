package com.reamicro.fix.hook

import com.reamicro.fix.core.hostNameMatches
import com.reamicro.fix.core.hostClass
import com.reamicro.fix.core.hostField
import com.reamicro.fix.core.typeMatches


import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TravelingMerchantEndTimeHook(
    private val classLoader: ClassLoader,
) {
    private val activeTripEndTimeSeconds = ThreadLocal<Long>()

    fun install() {
        // b2 起宿主被 R8 改名，逻辑名直查 Class.forName 会 CNFE，必须走 hostClass 解析。
        val sheetClass = classLoader.hostClass(TRAVELING_MERCHANT_SHEET_CLASS)
        val tripClass = classLoader.hostClass(TRAVELING_MERCHANT_TRIP_CLASS)
        // b2/b3 的 TravelingMerchantTrip 21 个 getter 已被 R8 删除（只剩 <clinit>/<init>/equals/hashCode/toString），
        // 所以这里必须先试 getter，再退回按 profile 映射读取 endTime 字段。
        val endTimeGetter = tripClass.declaredMethods.firstOrNull {
            it.hostNameMatches(END_TIME_GETTER) && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true }
        val endTimeField: Field? = if (endTimeGetter == null) {
            tripClass.hostField(END_TIME_FIELD).apply { isAccessible = true }
        } else {
            null
        }
        val businessCardsMethod = sheetClass.declaredMethods.first(::isTripBusinessCardsMethod).apply {
            isAccessible = true
        }
        val loadoutRowMethod = sheetClass.declaredMethods.first(::isLoadoutRowMethod).apply {
            isAccessible = true
        }

        hookTripEndTimeContext(businessCardsMethod, endTimeGetter, endTimeField)
        hookLoadoutEndTimeText(loadoutRowMethod)
        XposedBridge.log("$LOG_PREFIX traveling merchant end time hook installed")
    }

    private fun hookTripEndTimeContext(method: Method, endTimeGetter: Method?, endTimeField: Field?) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                activeTripEndTimeSeconds.remove()
                val trip = param.args?.getOrNull(0) ?: return
                val endTimeSeconds = runCatching {
                    val raw = endTimeGetter?.invoke(trip) ?: endTimeField?.get(trip)
                    (raw as? Number)?.toLong()
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

                args[DURATION_TEXT_ARGUMENT_INDEX] = formatTravelingMerchantEndTime(endTimeSeconds)
            }
        })
    }

    private fun isTripBusinessCardsMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.hostNameMatches(TRIP_BUSINESS_CARDS_METHOD) &&
            types.size == TRIP_BUSINESS_CARDS_PARAMETER_COUNT &&
            // 宿主被改名后类型名是混淆名，必须解析逻辑类再比较，不能拿运行时名字比逻辑名字。
            typeMatches(types.getOrNull(0), TRAVELING_MERCHANT_TRIP_CLASS) &&
            types.getOrNull(2) == String::class.java &&
            types.getOrNull(3) == Float::class.javaPrimitiveType
    }

    private fun isLoadoutRowMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.hostNameMatches(LOADOUT_ROW_METHOD) &&
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
        const val END_TIME_FIELD = "endTime"
        const val TRIP_BUSINESS_CARDS_METHOD = "MerchantTripBusinessCards"
        const val LOADOUT_ROW_METHOD = "MerchantLoadoutRow"
        const val TRIP_BUSINESS_CARDS_PARAMETER_COUNT = 6
        // b2/b3 的 MerchantLoadoutRow 比 b1 少一个 boolean：
        // b1 (String,String,Z,Z,String,Z,String,Function2,Composer,I,I) 11 参、时长文本在 4；
        // b2/b3 (String,String,Z,String,Z,String,Function2,Composer,I,I) 10 参、时长文本在 3。
        const val LOADOUT_ROW_PARAMETER_COUNT = 10
        const val DURATION_TEXT_ARGUMENT_INDEX = 3
    }
}

internal fun formatTravelingMerchantEndTime(
    epochSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = END_TIME_FORMATTER.format(Instant.ofEpochSecond(epochSeconds).atZone(zoneId))

private val END_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
