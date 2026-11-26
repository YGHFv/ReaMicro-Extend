package com.reamicro.fix.cloud.local

import org.json.JSONObject

/**
 * A checkpoint for the optional prayer and the following departure, not a user preference.
 * Neither host request has an idempotency key. Once a request may have reached the server,
 * recovery must read its result before sending another request which could consume resources.
 */
internal class MerchantDeparture(
    private val state: JSONObject,
    previousTripId: Long,
    config: CloudTaskLocalRunner.MerchantConfig,
    blessingType: String,
    private val checkpoint: (JSONObject) -> Unit,
) {
    private val context = JSONObject()
        .put("previousTripId", previousTripId)
        .put("cityCode", config.cityCode)
        .put("transportId", config.transportId)
        .put("principal", config.principal)
        .put("blessingType", blessingType)

    private var record = state.optJSONObject(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE)
        ?.let { JSONObject(it.toString()) } ?: JSONObject()

    init {
        if (record.length() > 0) {
            require(record.optInt("schema") == 1 && record.optJSONObject("context") != null) {
                "行商出发记录无法恢复，请先确认宿主行程与运签"
            }
        }
        // A known refusal has not spent materials. Changed departure options may try again.
        // An unanswered request remains unresolved even if the user edits those options.
        if (record.length() == 0 || (!sameContext() && !unresolved)) {
            record = if (record.optString("prayer") == PRAYER_READY) {
                // A confirmed/reused blessing may already have been consumed elsewhere.
                // Editing the trip must not reopen prayer for the same departure.
                record.put("context", context)
            } else JSONObject().put("schema", 1).put("context", context)
        }
    }

    val configuredType: String get() = record.getJSONObject("context").optString("blessingType")
    val contextChanged: Boolean get() = !sameContext()
    // 兼容旧版已记录的材料不足状态，继续出发时不再次消耗材料。
    val prayerSkipped: Boolean get() = record.optString("prayer") in setOf(PRAYER_SKIPPED, MATERIALS_UNAVAILABLE)
    val prayerSkipReason: String get() = record.optString("prayerSkipReason").ifBlank {
        if (record.optString("prayer") == MATERIALS_UNAVAILABLE) "祈福材料不足" else "祈禳失败"
    }
    val mustConfirmPrayer: Boolean get() = record.optString("prayer") in setOf(PRAYER_PENDING, PRAYER_READY)
    val startUnconfirmed: Boolean get() = record.optString("start") in setOf(START_PENDING, START_ACCEPTED)
    private val unresolved: Boolean get() = record.optString("prayer") == PRAYER_PENDING || startUnconfirmed

    fun prayerPending() = update("prayer", PRAYER_PENDING)
    fun prayerReady() {
        // Read-only confirmation allows changed trip options to proceed while keeping
        // the prayer guard. Do not clear it between confirmation and the next run.
        record.put("context", context)
        update("prayer", PRAYER_READY)
    }
    fun skipPrayer(reason: String) {
        record.put("prayerSkipReason", reason)
        update("prayer", PRAYER_SKIPPED)
    }
    fun startPending() = update("start", START_PENDING)
    fun startAccepted() = update("start", START_ACCEPTED)
    fun startRejected() = update("start", "")

    fun complete() = clear(state, checkpoint)

    private fun sameContext(): Boolean {
        val saved = record.optJSONObject("context") ?: return false
        return saved.optLong("previousTripId") == context.optLong("previousTripId") &&
            saved.optLong("transportId") == context.optLong("transportId") &&
            saved.optLong("principal") == context.optLong("principal") &&
            saved.optString("cityCode") == context.optString("cityCode") &&
            saved.optString("blessingType") == context.optString("blessingType")
    }

    private fun update(key: String, value: String) {
        if (record.optString(key) == value &&
            state.optJSONObject(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE)?.toString() == record.toString()
        ) return
        record.put(key, value)
        state.put(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE, JSONObject(record.toString()))
        checkpoint(state)
    }

    companion object {
        private const val PRAYER_PENDING = "pending"
        private const val PRAYER_READY = "ready"
        private const val PRAYER_SKIPPED = "skipped"
        private const val MATERIALS_UNAVAILABLE = "materials_unavailable"
        private const val START_PENDING = "pending"
        private const val START_ACCEPTED = "accepted"

        fun observeTrip(state: JSONObject, tripId: Long, checkpoint: (JSONObject) -> Unit) {
            if (tripId <= 0L) return
            val record = state.optJSONObject(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE) ?: return
            val previous = record.optJSONObject("context")?.optLong("previousTripId") ?: return
            if (tripId != previous) clear(state, checkpoint)
        }

        private fun clear(state: JSONObject, checkpoint: (JSONObject) -> Unit) {
            if (state.optJSONObject(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE)?.length() == 0) return
            // Runtime patching copies present keys; removing a key would retain an old checkpoint.
            state.put(CloudTaskLocalRunner.KEY_MERCHANT_DEPARTURE, JSONObject())
            checkpoint(state)
        }

        fun explicitRejection(body: JSONObject): String? {
            val code = body.opt("code")
            if (code != null && code != JSONObject.NULL) {
                if (code !is Number) return null
                val numericCode = code.toString().toLongOrNull() ?: return null
                if (numericCode != 0L && numericCode != 200L) {
                    return message(body).ifBlank { "业务码 $code" }
                }
            }
            val data = body.optJSONObject("data") ?: if (body.has("data")) return null else body
            return if (data.opt("success") == false) message(data).ifBlank { "服务端未接受操作" } else null
        }

        private fun message(body: JSONObject): String =
            (body.opt("message") as? String).orEmpty().trim()
    }
}
