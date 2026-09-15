package com.reamicro.fix.cloud.local

import com.reamicro.fix.association.network.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 阅微自动任务的执行逻辑库，由本地任务 [LocalTaskRunner] 调用。
 *
 * 早前这里还负责「设备模式」：向服务器领 device 租约、在模块进程代跑云端任务，再把结果
 * 回报服务器（`runDue` + `dispatchCompletion`）。云端任务已改回服务器执行，该路径整体移除；
 * 本对象现在只保留与传输方式无关的任务实现——轶闻、祈愿、自动阅读、行商、期物典当。
 */
object CloudTaskLocalRunner {
    /**
     * 执行一条阅微自动任务。任务运行状态通过返回的 state 表达，由调用方决定如何落盘。
     */
    internal fun runTask(taskType: String, task: JSONObject, request: JSONObject, credential: JSONObject,
        checkpoint: (JSONObject) -> Unit = {}): Outcome =
        when (taskType) {
            "yeshe_checkin" -> runCheckin(task, request, credential, checkpoint)
            "yeshe_draw_card" -> runDrawCard(task, request, credential, checkpoint)
            "cloud_auto_read" -> runAutoRead(task, request, credential, checkpoint)
            "traveling_merchant" -> runMerchantNotify(task, request, credential, checkpoint)
            "pawn" -> runPawn(task, request, credential, checkpoint)
            else -> Outcome("failed", "模块暂不支持此任务类型")
        }

    /** 行商的 SETTLED 表示奖励待领取，领取成功后才能开启下一趟。 */
    internal fun runMerchantNotify(task: JSONObject, request: JSONObject, credential: JSONObject, checkpoint: (JSONObject) -> Unit = {}): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { REAMICRO_BASE_URL }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val now = System.currentTimeMillis()
        val state = JSONObject(task.toString()).put("nextRunAtOverride", now + TASK_RETRY_INTERVAL_MS)
        val body = postReaMicro(
            baseUrl, token, request.optJSONObject("body") ?: JSONObject(),
            request.optString("endpoint").ifBlank { "rest/community/get-traveling-merchant" },
        )
        operationError(body)?.let { return Outcome("failed", "获取行商状态失败：$it", state) }
        var trip = parseMerchantTrip(body)
        val remembered = rememberedMerchantConfig(task, trip)
        rememberMerchantConfig(state, remembered)
        state.put(LocalTaskStore.KEY_MERCHANT_END_TIME, trip.endTimeMs)
        val config = resolveStartConfig(request, remembered)
        val autoComplete = request.optBoolean("merchantAutoComplete", false)
        val phase = merchantPhase(trip, now)
        if (trip.hasTrip && trip.tripId <= 0L) return Outcome("failed", "行商响应缺少 tripId", state)
        if (trip.hasTrip && phase == MerchantPhase.IN_TRANSIT) {
            state.put("nextRunAtOverride", merchantNextRunAt(trip, now))
            return Outcome(
                "success", "行商进行中，预计 ${formatMerchantEpoch(trip.endTimeMs)} 完成", state,
                notify = false, detail = merchantDetail(trip),
            )
        }
        if (trip.hasTrip && phase == MerchantPhase.ARRIVED) {
            state.put("nextRunAtOverride", now + TRAVELING_MERCHANT_ARRIVE_GRACE_MS)
            return Outcome("success", "行商已到预计结束时间，等待服务端生成结算结果", state, notify = false)
        }
        val action = merchantAction(
            trip.hasTrip, phase, trip.tripId,
            state.optLong(KEY_MERCHANT_NOTIFIED_TRIP), state.optLong(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP),
            state.optLong(LocalTaskStore.KEY_MERCHANT_RESTART_AFTER), autoComplete, config.isComplete,
        )
        var message = if (trip.hasTrip) {
            merchantProfitText(trip.eventTitle, trip.settlementAmount, trip.principal) + "，奖励待领取"
        } else "当前没有进行中的行商"
        var detail = if (trip.hasTrip) merchantDetail(trip) else JSONObject()
        var notify = action.notify
        if (action.notify) state.put(KEY_MERCHANT_NOTIFIED_TRIP, trip.tripId)
        if (action.settle) {
            val settled = runCatching {
                postReaMicro(
                    baseUrl, token, JSONObject().put("tripId", trip.tripId),
                    request.optString("settleEndpoint").ifBlank { "rest/community/settle-traveling-merchant" },
                )
            }.getOrElse { return Outcome("failed", "$message（领取行商奖励失败：${it.message}）", state, detail = detail) }
            operationError(settled)?.let {
                return Outcome("failed", "$message（领取行商奖励失败：$it）", state, detail = detail)
            }
            state.put(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP, trip.tripId)
            state.put(LocalTaskStore.KEY_MERCHANT_END_TIME, 0L)
            checkpoint(state)
            val settledTrip = parseMerchantTrip(settled)
            if (settledTrip.hasTrip) {
                trip = settledTrip.copy(
                    cityName = trip.cityName.ifBlank { settledTrip.cityName },
                    transportLabel = trip.transportLabel.ifBlank { settledTrip.transportLabel },
                )
                detail = merchantDetail(trip)
            }
            detail.put("领取奖励", "已领取")
            message = merchantProfitText(trip.eventTitle, trip.settlementAmount, trip.principal) + "，已领取行商奖励"
            notify = true
        } else if (trip.hasTrip && state.optLong(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP) == trip.tripId) {
            message = "行商奖励已领取"
            state.put(LocalTaskStore.KEY_MERCHANT_END_TIME, 0L)
        }
        val canStart = autoComplete && (!trip.hasTrip ||
            (state.optLong(LocalTaskStore.KEY_MERCHANT_SETTLED_TRIP) == trip.tripId &&
                state.optLong(LocalTaskStore.KEY_MERCHANT_RESTART_AFTER) != trip.tripId))
        if (canStart) {
            if (!config.isComplete) {
                return Outcome("failed", "$message（未能开启新行商：${merchantStartHint(config)}）", state, detail = detail)
            }
            val blessingType = request.optString("blessingType").trim().uppercase()
            val blessing = ensureTaoistBlessing(baseUrl, token, request, blessingType)
            if (blessing.failure != null) {
                return Outcome("failed", "$message${blessingNote(blessing, blessingType)}", state, detail = detail)
            }
            val started = runCatching {
                postReaMicro(
                    baseUrl, token,
                    JSONObject().put("cityCode", config.cityCode).put("principal", config.principal).put("transportId", config.transportId),
                    request.optString("startEndpoint").ifBlank { "rest/community/start-traveling-merchant" },
                )
            }.getOrElse { return Outcome("failed", "$message（未能开启新行商：${it.message}）", state, detail = detail) }
            operationError(started)?.let {
                return Outcome("failed", "$message（未能开启新行商：$it）", state, detail = detail)
            }
            var nextTrip = parseMerchantTrip(started)
            if (!nextTrip.hasTrip) {
                val refreshed = runCatching {
                    postReaMicro(baseUrl, token, JSONObject(), request.optString("endpoint").ifBlank { "rest/community/get-traveling-merchant" })
                }.getOrNull()
                if (refreshed != null && businessError(refreshed) == null) nextTrip = parseMerchantTrip(refreshed)
            }
            if ((!nextTrip.hasTrip || nextTrip.tripId == trip.tripId) &&
                !(started.optJSONObject("data") ?: started).optBoolean("success", false)
            ) {
                return Outcome("failed", "$message（未能确认新行商已开启）", state, detail = detail)
            }
            if (trip.hasTrip) state.put(LocalTaskStore.KEY_MERCHANT_RESTART_AFTER, trip.tripId)
            rememberMerchantConfig(state, config)
            state.put(LocalTaskStore.KEY_MERCHANT_END_TIME, nextTrip.endTimeMs)
            state.put("nextRunAtOverride", merchantNextRunAt(nextTrip, now))
            checkpoint(state)
            message += "，已开启新行商${blessingNote(blessing, blessingType)}"
            detail.put("新行商", "预计 ${formatMerchantEpoch(nextTrip.endTimeMs)} 完成")
            if (blessing.detail.length() > 0) detail.put("新行商运签", blessing.detail.optString("运签"))
            return Outcome("success", message, state, detail = detail)
        }
        state.put("nextRunAtOverride", now + TRAVELING_MERCHANT_POLL_MS)
        return Outcome("success", message, state, notify = notify, detail = detail)
    }

    /** 把当前生效的运签写进详情（读不到就什么都不写，不编造）。 */
    private fun addActiveBlessing(detail: JSONObject, active: JSONObject) {
        if (active.length() <= 0) return
        detail.put("运签", active.optString("运签"))
        detail.put("运签效果", active.optString("效果"))
    }

    /** 行商详情：谁、去哪、本金多少、结算多少、事件是什么。 */
    private fun merchantDetail(trip: MerchantTrip): JSONObject = JSONObject()
        .put(
            "事件",
            trip.eventTitle.ifBlank {
                // 行商途中的事件在结算时才生成，这里别用"行商"这种兜底词冒充事件内容。
                if (trip.status.equals("TRAVELING", ignoreCase = true)) "待结算（事件在抵达后才生成）" else "无"
            },
        )
        .apply { if (trip.eventContent.isNotBlank()) put("事件详情", trip.eventContent) }
        .put("行程", "${formatMerchantEpoch(trip.startTimeMs)} → ${formatMerchantEpoch(trip.endTimeMs)}")
        .put("城池", trip.cityName.ifBlank { trip.cityCode })
        .apply { if (trip.transportLabel.isNotBlank()) put("车马", trip.transportLabel) }
        .apply {
            // 行商这趟自带运签信息（blessingName / effect），比单独查一次更准。
            if (trip.blessingName.isNotBlank()) {
                val effect = blessingEffectText(trip.blessingEffectType, trip.blessingEffectValue)
                put("运签", listOf(trip.blessingName, effect).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
        .put("本金", "${trip.principal} 铜")
        .put("结算", "${trip.settlementAmount} 铜")
        .put("状态", trip.status.ifBlank { "—" })

    /**
     * 解析本次要用于开新行商的参数：**用户填了就用用户的，留空则沿用上次行商配置**。
     * 上次配置优先取当前这趟行商实际使用的参数（最准确），没有则用历史记录。
     */
    private fun rememberedMerchantConfig(task: JSONObject, trip: MerchantTrip): MerchantConfig = MerchantConfig(
        cityCode = trip.cityCode.ifBlank { task.optString(LocalTaskStore.KEY_MERCHANT_LAST_CITY) },
        transportId = if (trip.hasTrip) trip.transportId else task.optLong(LocalTaskStore.KEY_MERCHANT_LAST_TRANSPORT, 0L),
        principal = trip.principal.takeIf { it > 0L } ?: task.optLong(LocalTaskStore.KEY_MERCHANT_LAST_PRINCIPAL, 0L),
    )

    /** 用户填了的字段优先；留空的字段沿用它记住的上次行商配置。 */
    internal fun resolveStartConfig(request: JSONObject, remembered: MerchantConfig): MerchantConfig = MerchantConfig(
        cityCode = request.optString("merchantCityCode").trim().ifBlank { remembered.cityCode },
        transportId = request.optLong("merchantTransportId", 0L).takeIf { it > 0L } ?: remembered.transportId,
        principal = request.optLong("merchantPrincipal", 0L).takeIf { it > 0L } ?: remembered.principal,
    )

    private fun merchantStartHint(config: MerchantConfig): String = when {
        config.cityCode.isBlank() -> "缺少城池，请先跑一趟行商或手动填写 cityCode"
        config.transportId < 0L -> "车马配置无效"
        config.principal <= 0L -> "缺少本金，请先跑一趟行商或手动填写本金"
        else -> "参数不完整"
    }

    private fun merchantNextRunAt(trip: MerchantTrip, now: Long): Long =
        trip.endTimeMs.takeIf { it > now }?.plus(TRAVELING_MERCHANT_ARRIVE_GRACE_MS) ?: (now + TASK_RETRY_INTERVAL_MS)

    private fun rememberMerchantConfig(state: JSONObject, config: MerchantConfig) {
        state.put(LocalTaskStore.KEY_MERCHANT_LAST_CITY, config.cityCode)
        state.put(LocalTaskStore.KEY_MERCHANT_LAST_TRANSPORT, config.transportId)
        state.put(LocalTaskStore.KEY_MERCHANT_LAST_PRINCIPAL, config.principal)
    }

    internal data class MerchantConfig(val cityCode: String, val transportId: Long, val principal: Long) {
        val isComplete: Boolean get() = cityCode.isNotBlank() && transportId >= 0L && principal > 0L
    }

    /** 查询生成每日轶闻；isFinish 表示可领取，领取只调用一次 complete-daily-lore。 */
    private fun runCheckin(task: JSONObject, request: JSONObject, credential: JSONObject, checkpoint: (JSONObject) -> Unit): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val now = System.currentTimeMillis()
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val state = JSONObject(task.toString())
            .put("nextRunAtOverride", now + TASK_RETRY_INTERVAL_MS)
            .put("claimJustCompleted", false)
        val blessingType = request.optString("blessingType").trim().uppercase()
        val blessing = if (task.optString("lastCheckinDate") != today) {
            ensureTaoistBlessing(baseUrl, token, request, blessingType)
        } else BlessingCheck(null, JSONObject())
        if (blessing.failure != null) return Outcome("failed", blessing.failure, state)
        val body = runCatching {
            postReaMicro(baseUrl, token, request.optJSONObject("body") ?: JSONObject(),
                request.optString("endpoint").ifBlank { "rest/community/get-daily-lore" })
        }.getOrElse { return Outcome("failed", "获取每日轶闻失败：${it.message}", state) }
        operationError(body)?.let { return Outcome("failed", "获取每日轶闻失败：$it", state) }
        val data = body.optJSONObject("data") ?: body
        val loreId = data.optLong("id", data.optLong("loreId", 0L))
        if (loreId <= 0L) return Outcome("failed", "阅微每日轶闻响应缺少 userLoreId", state)
        val sameLore = task.optLong("claimLoreId") == loreId && task.optString("lastCheckinDate") == today
        val previouslyClaimed = sameLore && task.optString("claimCompletedDate") == today
        var claimed = data.optBoolean("claimed", false) || previouslyClaimed
        var claimDueAt = epochMillis(data.optLong("endTime", 0L))
            .takeIf { it > 0L } ?: if (sameLore) task.optLong("claimDueAt", 0L) else 0L
        state.put("lastCheckinDate", today)
            .put("lastCheckinAt", if (sameLore) task.optLong("lastCheckinAt", now) else now)
            .put("claimLoreId", loreId)
            .put("claimDueAt", claimDueAt)
            .put("claimCompletedDate", if (claimed) today else "")
        checkpoint(state)
        fun checkinOutcome(result: String, message: String): Outcome {
            val nextCheck = state.optLong("nextRunAtOverride")
            val detail = JSONObject()
                .put("轶闻", data.optString("title"))
                .put("奖励", if (claimed) "已领取" else "待领取（${formatMerchantEpoch(nextCheck)} 再次检查）")
                .put(DAILY_LORE_DETAIL_KEY, dailyLoreSnapshot(data, claimed))
            addActiveBlessing(detail, blessing.detail)
            return Outcome(result, message, state, detail = detail)
        }
        val ready = if (data.has("isFinish")) data.optBoolean("isFinish") else claimDueAt in 1..now
        if (!claimed && ready) {
            val claim = runCatching {
                postReaMicro(baseUrl, token, JSONObject().put("userLoreId", loreId),
                    request.optString("completeEndpoint").ifBlank { "rest/community/complete-daily-lore" })
            }.getOrElse { return checkinOutcome("failed", "签到奖励领取失败：${it.message}") }
            val error = operationError(claim)
            if (error != null) {
                if (listOf("已领取", "已经领取", "已领过").any(error::contains)) {
                    claimed = true
                } else if (listOf("未达到领取", "尚未解锁", "未到领取", "暂不可领取").any(error::contains)) {
                    return checkinOutcome("success", "奖励尚未解锁，5 分钟后再次检查")
                } else return checkinOutcome("failed", "签到奖励领取失败：$error")
            } else {
                val claimData = claim.optJSONObject("data") ?: claim
                claimed = claimData.optBoolean("claimed", true)
                epochMillis(claimData.optLong("endTime", 0L)).takeIf { it > 0L }?.let { claimDueAt = it }
                for (key in listOf("endTime", "completeTime", "exp", "gem", "propId", "propName", "propQuality")) {
                    if (claimData.has(key) && !claimData.isNull(key)) data.put(key, claimData.get(key))
                }
            }
        }
        val nextRunAt = when {
            claimed -> 0L
            claimDueAt > now -> claimDueAt
            ready || claimDueAt > 0L -> now + TASK_RETRY_INTERVAL_MS
            else -> now + CLAIM_RETRY_INTERVAL_MS
        }
        state.put("claimDueAt", claimDueAt)
            .put("nextRunAtOverride", nextRunAt)
            .put("claimCompletedDate", if (claimed) today else "")
            .put("claimJustCompleted", claimed && !previouslyClaimed)
        checkpoint(state)
        val message = when {
            claimed -> "签到完成，奖励已领取"
            claimDueAt > now -> "签到完成，奖励 ${formatMerchantEpoch(claimDueAt)} 解锁"
            else -> "签到完成，奖励待领取，将于 ${formatMerchantEpoch(nextRunAt)} 再次检查"
        }
        return checkinOutcome("success", message + blessingNote(blessing, blessingType))
    }

    /** 秒级 epoch 统一成毫秒：阅微有的接口给秒、有的给毫秒。 */
    private fun epochMillis(raw: Long): Long =
        if (raw in 1 until 100_000_000_000L) raw * 1_000L else raw

    private fun runDrawCard(task: JSONObject, request: JSONObject, credential: JSONObject, checkpoint: (JSONObject) -> Unit): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val configuredLimit = request.optInt("dailyLimit", 3).coerceIn(0, 20)
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val usedToday = if (task.optString("dailyCounterDate") == today) task.optInt("dailyCounter", 0).coerceAtLeast(0) else 0
        val target = if (configuredLimit == 0) {
            val info = postReaMicro(baseUrl, token, JSONObject(), request.optString("userInfoEndpoint").ifBlank { "rest/user/get-user-info" })
            val infoError = operationError(info)
            if (infoError != null) return Outcome("failed", "读取彩筹余额失败：$infoError")
            (info.optJSONObject("data")?.optInt("gem", 0) ?: 0).coerceAtLeast(0)
        } else (configuredLimit - usedToday).coerceAtLeast(0)
        if (target <= 0) return Outcome("success", "今日没有可用彩筹")
        val endpoint = request.optString("endpoint").ifBlank { "rest/community/wish" }
        val items = mutableListOf<JSONObject>()
        var consumed = 0
        while (consumed < target) {
            val count = if (target - consumed >= 9) 9 else 1
            val wishBody = (request.optJSONObject("body") ?: JSONObject()).put("count", count)
            val wish = runCatching { postReaMicro(baseUrl, token, wishBody, endpoint) }
                .getOrElse { return Outcome("failed", "祈愿请求失败：${it.message}", drawState(items, usedToday + consumed)) }
            val error = operationError(wish)
            if (error != null && listOf("彩筹不足", "彩筹不够", "彩签不足", "余额不足").any(error::contains)) break
            if (error != null) return Outcome("failed", "祈愿失败：$error", drawState(items, usedToday + consumed))
            val result = wish.optJSONObject("data")?.optJSONArray("props")
                ?: wish.optJSONArray("props")
                ?: JSONArray()
            for (index in 0 until result.length()) {
                val item = result.optJSONObject(index) ?: continue
                items += JSONObject()
                    .put("name", item.optString("name").ifBlank { item.optString("propName", "物品") })
                    .put("quality", item.optString("quality").ifBlank { item.optString("rarity") })
                    .put("count", item.optInt("count", item.optInt("quantity", 1)).coerceAtLeast(1))
            }
            consumed += count
            checkpoint(drawState(items, usedToday + consumed))
        }
        val summary = items.groupBy { "${it.optString("name")}\u0000${it.optString("quality")}" }
            .entries.joinToString("、") { (_, values) -> "${values.first().optString("name")} x${values.sumOf { it.optInt("count", 1) }}" }
        val detail = JSONObject()
            .put("本次祈愿", "$consumed 次")
            .put("获得", if (summary.isBlank()) "无" else summary)
        return Outcome(
            "success",
            if (consumed == 0) "彩筹已用完" else if (summary.isBlank()) "抽卡完成" else summary,
            drawState(items, usedToday + consumed),
            detail = detail,
        )
    }

    private fun drawState(items: List<JSONObject>, consumed: Int): JSONObject = JSONObject()
        .put("dailyCounterDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())
        .put("dailyCounter", consumed)
        .put("lastDrawItems", JSONArray(items))
        .put("lastDrawResult", items.joinToString("、") { it.optString("name") })
        .put("lastDrawAt", System.currentTimeMillis())

    private fun runAutoRead(task: JSONObject, request: JSONObject, credential: JSONObject, checkpoint: (JSONObject) -> Unit): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val configuredBooks = request.optJSONArray("books") ?: JSONArray()
        val usesRecentBooks = configuredBooks.length() == 0
        val books = if (!usesRecentBooks) {
            configuredBooks
        } else {
            val recent = postReaMicro(
                baseUrl,
                token,
                JSONObject().put("pageNum", 1).put("pageSize", request.optInt("recentLimit", 1).coerceIn(1, 10)),
                request.optString("recentEndpoint").ifBlank { "rest/reader/get-read-record-list" },
            )
            operationError(recent)?.let { return Outcome("failed", "读取最近阅读记录失败：$it") }
            recent.optJSONObject("data")?.optJSONArray("list")
                ?: recent.optJSONArray("list")
                ?: JSONArray()
        }
        val durationMinutes = request.optInt("durationMinutes", 30).coerceIn(1, 720)
        val dailyLimit = request.optInt("dailyLimitMinutes", 720).coerceIn(1, 1_440)
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val usedToday = if (task.optString("dailyReadDate") == today) task.optInt("dailyReadMinutes", 0).coerceAtLeast(0) else 0
        if (usedToday >= dailyLimit) return Outcome("success", "今日已达到 ${dailyLimit} 分钟上限")
        val rotation = task.optInt("bookRotation", 0).coerceAtLeast(0) % books.length().coerceAtLeast(1)
        var completedBooks = 0
        var completedMinutes = 0
        val state = JSONObject().put("dailyReadDate", today).put("dailyReadMinutes", usedToday).put("bookRotation", rotation)
        val names = mutableListOf<String>()
        for (offset in 0 until minOf(books.length(), request.optInt("bookLimit", 10).coerceIn(1, 10))) {
            val duration = minOf(durationMinutes, (dailyLimit - usedToday - completedMinutes).coerceAtLeast(0))
            if (duration <= 0) break
            val book = books.optJSONObject((rotation + offset) % books.length()) ?: continue
            val bookId = book.optLong("bookId", if (usesRecentBooks) 0L else book.optLong("cloudBookId", 0L))
            if (bookId <= 0L) continue
            val payload = JSONObject().put("list", JSONArray().put(
                JSONObject()
                    .put("bookId", bookId)
                    .put("date", today)
                    .put("duration", duration * 60)
                    .put("verify", ""),
            ))
            val result = runCatching { postReaMicro(baseUrl, token, payload, request.optString("timeEndpoint").ifBlank { "rest/reader/update-read-time-by-date" }) }
                .getOrElse { return Outcome("failed", "上报阅读时长失败：${it.message}", state) }
            operationError(result)?.let { return Outcome("failed", "上报阅读时长失败：$it", state) }
            completedBooks++
            completedMinutes += duration
            state.put("dailyReadMinutes", usedToday + completedMinutes).put("bookRotation", rotation + offset + 1)
            checkpoint(state)
            names += book.optString("name").ifBlank { book.optString("bookName").ifBlank { "图书 $bookId" } }
        }
        if (completedBooks == 0) return Outcome("failed", "没有找到可阅读的图书")
        val detail = JSONObject()
            .put("图书", names.joinToString("、"))
            .put("时长", "$completedMinutes 分钟")
            .put("今日累计", "${usedToday + completedMinutes} 分钟")
        return Outcome("success", "${names.joinToString("、")} · $completedMinutes 分钟", state, detail = detail)
    }

    /**
     * 任务执行前把道观运签调整成**任务配置的那一支**。
     *
     * 规则（用户口径）：配置了签种就要那支签。账号上生效的是同一支 → 直接沿用（祈禳要消耗道具，
     * 同签种没必要重来一遍）；没有签、或是别的签种 → 祈禳配置的那支。
     *
     * 早前这里是"已有签就不替换"，于是自动行商祈禳的求财签会把每日轶闻配置的求运签一直挡在门外，
     * 而战报还写着「已祈禳求运签」——用户看到的正是"我明明配了求运，怎么求财去了"。
     * 运签是账号上的单槽位，两个任务本就可能互相顶，这一点由返回值如实报出来，不藏。
     */
    private fun ensureTaoistBlessing(
        baseUrl: String,
        token: String,
        request: JSONObject,
        blessingType: String,
    ): BlessingCheck = runCatching {
        checkTaoistBlessing(baseUrl, token, request, blessingType)
    }.getOrElse { BlessingCheck("查询或祈禳运签失败：${it.message}", JSONObject()) }

    private fun checkTaoistBlessing(
        baseUrl: String,
        token: String,
        request: JSONObject,
        blessingType: String,
    ): BlessingCheck {
        val type = blessingType.trim().uppercase()
        if (type.isBlank()) return BlessingCheck(null, JSONObject())
        if (type !in setOf(BLESSING_LUCK, BLESSING_SAFETY, BLESSING_WEALTH)) {
            return BlessingCheck("不支持的运签配置：$type", JSONObject())
        }
        val current = postReaMicro(
            baseUrl,
            token,
            JSONObject(),
            request.optString("blessingEndpoint").ifBlank { "rest/community/get-taoist-blessing" },
        )
        businessError(current)?.let { return BlessingCheck("查询运签失败：$it", JSONObject()) }
        val data = current.optJSONObject("data") ?: current
        // blessing 为 null（JSONObject.NULL 或缺失）都表示无签；只有拿到具体签种才算"已有"。
        val blessing = data.opt("blessing") as? JSONObject
        val activeType = blessing?.optString("blessingType").orEmpty()
        if (activeType.equals(type, ignoreCase = true)) {
            return BlessingCheck(null, blessingDetail(blessing!!, "沿用已有"), activeType = activeType)
        }
        val pray = postReaMicro(
            baseUrl,
            token,
            JSONObject().put("blessingType", type),
            request.optString("prayEndpoint").ifBlank { "rest/community/pray-taoist-blessing" },
        )
        operationError(pray)?.let { return BlessingCheck("祈禳${blessingLabel(type)}失败：$it", JSONObject()) }
        val prayData = pray.optJSONObject("data") ?: pray
        val prayed = prayData.optJSONObject("blessing") ?: run {
            val refreshed = postReaMicro(baseUrl, token, JSONObject(),
                request.optString("blessingEndpoint").ifBlank { "rest/community/get-taoist-blessing" })
            operationError(refreshed)?.let { return BlessingCheck("确认运签失败：$it", JSONObject()) }
            (refreshed.optJSONObject("data") ?: refreshed).optJSONObject("blessing")
        }
        val actualType = prayed?.optString("blessingType").orEmpty().trim().uppercase()
        if (actualType != type) {
            val reason = if (actualType.isBlank()) "服务端未返回生效运签" else "服务端返回${blessingLabel(actualType)}，与配置不符"
            return BlessingCheck("祈禳${blessingLabel(type)}失败：$reason", JSONObject())
        }
        return BlessingCheck(
            null,
            blessingDetail(prayed!!, "本次祈禳"),
            prayed = true,
            activeType = actualType,
            replacedType = activeType,
        )
    }


    /** 运签详情：签种、签文名、效果描述（游戏原文，例如"下一次每日轶闻：绿色及以上概率提升 2 个百分点"）。 */
    private fun blessingDetail(blessing: JSONObject, source: String): JSONObject {
        val type = blessing.optString("blessingType")
        return JSONObject()
            .put("运签", "$source · ${blessingLabel(type)} ${blessing.optString("name")}".trim())
            .put("效果", blessing.optString("description"))
    }

    /**
     * 运签检查结果。
     *
     * [failure] 非空表示这次没拿到签；[prayed] 区分"这次真的祈禳了"与"沿用了已有的"——
     * 两者都要如实告诉用户，早前统一按"已祈禳"播报，才会出现"配的求运、实际是求财"却写着
     * 「已祈禳求运签」的矛盾记录。
     */
    internal data class BlessingCheck(
        val failure: String?,
        val detail: JSONObject,
        /** 这次是否真的发出并成功了祈禳请求。 */
        val prayed: Boolean = false,
        /** 最终生效的签种（服务端回给我们的那个）。 */
        val activeType: String = "",
        /** 被这次祈禳顶掉的旧签种；没有则为空。 */
        val replacedType: String = "",
    )

    /** 祈禳结果给任务消息加一句：真的祈禳了、沿用了哪一支、还是失败了。 */
    internal fun blessingNote(check: BlessingCheck, configuredType: String): String = when {
        check.failure != null -> "（运签：${check.failure}）"
        configuredType.isBlank() -> ""
        check.prayed -> {
            val label = blessingLabel(check.activeType.ifBlank { configuredType })
            val replaced = check.replacedType.takeIf { it.isNotBlank() }
                ?.let { "（顶掉原有${blessingLabel(it)}）" }.orEmpty()
            " · 已祈禳$label$replaced"
        }
        check.activeType.isNotBlank() -> " · 沿用已有${blessingLabel(check.activeType)}"
        else -> ""
    }

    internal fun blessingLabel(type: String): String = when (type.trim().uppercase()) {
        // 空串是合法选择（用户明确要求"不祈禳"），不是"未知签种"。
        "" -> "不祈禳"
        BLESSING_LUCK -> "求运签"
        BLESSING_SAFETY -> "求安签"
        BLESSING_WEALTH -> "求财签"
        else -> "运签"
    }

    /**
     * 期物典当：按游戏给定的当日期物典当换铜钱。
     *
     * 接口与判定完全对齐参考脚本：`get-pawn-count` 取当日期物与剩余次数；期物命中禁当清单
     * （[PROHIBITED_PAWN_PROP_HINTS]，都是祈禳/传承/夺宝这类要留着的消耗品）就跳过；
     * 再从背包 `get-user-materials` 里找到该期物的 `userPropId`，循环 `pawn` 直到次数或持有量用尽。
     */
    private fun runPawn(task: JSONObject, request: JSONObject, credential: JSONObject, checkpoint: (JSONObject) -> Unit): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val info = postReaMicro(
            baseUrl,
            token,
            JSONObject(),
            request.optString("pawnCountEndpoint").ifBlank { "rest/community/get-pawn-count" },
        )
        operationError(info)?.let { return Outcome("failed", "获取期物典当信息失败：$it") }
        val data = info.optJSONObject("data") ?: info
        val remaining = data.optInt("remaining", 0).coerceAtLeast(0)
        val maxPerDay = data.optInt("maxPerDay", 0).coerceAtLeast(0)
        val usedToday = data.optInt("usedToday", 0).coerceAtLeast(0)
        val propId = data.opt("specialPropId")?.toString().orEmpty().trim()
        val propName = data.optString("specialPropName").ifBlank { "期物" }
        if (remaining <= 0) {
            return Outcome("success", "今日可典当次数已用完（$usedToday/$maxPerDay）", JSONObject(), notify = false)
        }
        PROHIBITED_PAWN_PROP_HINTS[propId]?.let { hint ->
            // 跳过不是故障：今天的期物恰好是要留着的消耗品，明天再看。
            return Outcome("success", "今日期物「$propName」是$hint，跳过典当", JSONObject(), notify = false)
        }
        val materials = postReaMicro(
            baseUrl,
            token,
            JSONObject(),
            request.optString("materialsEndpoint").ifBlank { "rest/community/get-user-materials" },
        )
        operationError(materials)?.let { return Outcome("failed", "读取背包失败：$it") }
        val list = materials.optJSONObject("data")?.optJSONArray("materials")
            ?: materials.optJSONArray("materials")
            ?: JSONArray()
        var target: JSONObject? = null
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            if (item.opt("propId")?.toString()?.trim() == propId) {
                target = item
                break
            }
        }
        if (target == null) {
            return Outcome("success", "背包里没有期物「$propName」，跳过典当", JSONObject(), notify = false)
        }
        val quantity = target.optInt("quantity", 0).coerceAtLeast(0)
        if (quantity <= 0) {
            return Outcome("success", "期物「$propName」持有数量为 0，跳过典当", JSONObject(), notify = false)
        }
        val userPropId = target.opt("userPropId")?.toString()?.trim().orEmpty()
            .takeIf { it.isNotBlank() }
            ?: return Outcome("failed", "期物「$propName」缺少 userPropId，无法典当")
        var successCount = 0
        var coin = 0L
        var failure: String? = null
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val state = JSONObject().put("lastPawnDate", today).put("pawnUsedToday", usedToday).put("pawnLastCoin", 0L)
        repeat(minOf(remaining, quantity)) {
            if (failure != null) return@repeat
            val pawn = runCatching {
                postReaMicro(
                    baseUrl,
                    token,
                    JSONObject().put("userPropId", userPropId.toLongOrNull() ?: userPropId),
                    request.optString("pawnEndpoint").ifBlank { "rest/community/pawn" },
                )
            }.getOrElse {
                failure = it.message ?: "典当请求失败"
                return@repeat
            }
            val error = operationError(pawn)
            if (error != null) {
                failure = error
                return@repeat
            }
            coin += (pawn.optJSONObject("data") ?: pawn).optLong("coin", 0L)
            successCount++
            state.put("pawnUsedToday", usedToday + successCount).put("pawnLastCoin", coin)
            checkpoint(state)
        }
        if (successCount == 0) {
            return Outcome("failed", "典当失败：${failure ?: "未知原因"}", state)
        }
        val tail = if (failure != null) "（第 ${successCount + 1} 次中断：$failure）" else ""
        val detail = JSONObject()
            .put("期物", propName)
            .put("典当数量", "$successCount 件")
            .put("获得铜钱", "$coin 文")
            .put("今日次数", "${usedToday + successCount}/$maxPerDay")
        return Outcome(if (failure == null) "success" else "failed", "典当「$propName」$successCount 件，获得铜钱 $coin 文$tail", state, detail = detail)
    }

    private fun postReaMicro(baseUrl: String, token: String, body: JSONObject, endpoint: String): JSONObject {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("任务已中断，保留进度等待下次执行")
        val raw = HttpClient.postBytes(
            baseUrl.trimEnd('/') + "/" + endpoint.trimStart('/'),
            body.toString().toByteArray(Charsets.UTF_8),
            "application/json",
            mapOf("Authorization" to "Bearer $token", "platform" to "android", "Accept" to "application/json"),
            45_000,
            45_000,
        )
        return JSONObject(raw)
    }

    private fun businessError(body: JSONObject): String? {
        val code = body.optInt("code", 0)
        if (code == 0 || code == 200) return null
        return body.optString("message").ifBlank { body.optString("msg") }.ifBlank { "业务码 $code" }
    }

    private fun operationError(body: JSONObject): String? {
        businessError(body)?.let { return it }
        val data = body.optJSONObject("data") ?: body
        return if (data.has("success") && !data.optBoolean("success", false)) {
            data.optString("message").ifBlank { data.optString("msg") }.ifBlank { "服务端未接受操作" }
        } else null
    }

    /** 从行商响应解析活跃行商。字段名与宿主 TravelingMerchantTrip 对齐；endTime 为秒级 epoch。 */
    internal fun parseMerchantTrip(body: JSONObject): MerchantTrip {
        val data = body.optJSONObject("data") ?: body
        val trip = data.optJSONObject("activeTrip") ?: data.optJSONObject("trip") ?: return MerchantTrip(hasTrip = false)
        val endTimeRaw = trip.optLong("endTime", 0L)
        val endTimeMs = if (endTimeRaw in 1 until 100_000_000_000L) endTimeRaw * 1_000L else endTimeRaw
        val startTimeRaw = trip.optLong("startTime", 0L)
        val startTimeMs = if (startTimeRaw in 1 until 100_000_000_000L) startTimeRaw * 1_000L else startTimeRaw
        return MerchantTrip(
            hasTrip = true,
            tripId = trip.optLong("id", 0L),
            status = trip.optString("status"),
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            settlementAmount = trip.optLong("settlementAmount", 0L),
            principal = trip.optLong("principal", 0L),
            eventTitle = trip.optString("eventTitle"),
            eventContent = trip.optString("eventContent").ifBlank { trip.optString("content") },
            // 这趟行商实际使用的参数，作为「上次行商配置」的来源。
            cityCode = trip.optString("cityCode"),
            transportId = trip.optLong("transportId", 0L),
            // 城池与车马的中文名：响应里带着 cities[] / transports[] 对照表，
            // 直接把 code/id 翻出来，别把 LANGYA、transportId=5 这种内部值摆给用户看。
            cityName = lookupName(data.optJSONArray("cities"), "code", trip.optString("cityCode"), "name"),
            transportLabel = transportLabel(trip.optLong("transportId", 0L), data.optJSONArray("transports")),
            blessingName = trip.optString("blessingName"),
            blessingEffectType = trip.optString("blessingEffectType"),
            blessingEffectValue = trip.optString("blessingEffectValue"),
        )
    }

    /**
     * 这一轮行商该做什么。
     *
     * 三件事互相独立、各按各的趟次记账，必须分开算：
     * - [notify] 只决定"要不要发通知"，由"已播报过的趟次"去重；
     * - [settle] 决定"这趟要不要自动结算"，由"已自动结算过的趟次"去重；
     * - [start] 决定"要不要开新行商"，没在途行商时每次都试（失败下次再试），
     *   有趟已结算的行商时按"为它开成功过没有"去重。
     *
     * 早前三件事共用"已通知趟次"这一个标记，先通知过、之后才打开自动完成的那一趟就再也
     * 进不了结算分支——用户看到的就是"自动行商不领奖、也不开新行商"。
     */
    internal fun merchantAction(
        hasTrip: Boolean,
        phase: MerchantPhase,
        tripId: Long,
        lastNotifiedTripId: Long,
        settledTripId: Long,
        restartedAfterTripId: Long,
        autoComplete: Boolean,
        startConfigComplete: Boolean,
    ): MerchantAction = MerchantAction(
        notify = hasTrip && phase == MerchantPhase.SETTLED &&
            tripId > 0L && tripId != lastNotifiedTripId,
        settle = autoComplete && hasTrip && phase == MerchantPhase.SETTLED &&
            tripId > 0L && tripId != settledTripId,
        start = autoComplete && startConfigComplete && when {
            !hasTrip -> true
            phase == MerchantPhase.SETTLED -> tripId == settledTripId && tripId != restartedAfterTripId
            else -> false
        },
    )

    /** [merchantAction] 的结果。 */
    internal data class MerchantAction(
        /** 这趟还没播报过结果，该发通知。 */
        val notify: Boolean,
        /** 这趟已抵达、且还没自动结算过。 */
        val settle: Boolean,
        /** 该去开一趟新行商。 */
        val start: Boolean,
    )

    internal fun merchantPhase(trip: MerchantTrip, now: Long): MerchantPhase = when {
        !trip.hasTrip -> MerchantPhase.SETTLED
        trip.status.equals("SETTLED", ignoreCase = true) -> MerchantPhase.SETTLED
        trip.endTimeMs <= 0L -> MerchantPhase.IN_TRANSIT
        now < trip.endTimeMs -> MerchantPhase.IN_TRANSIT
        else -> MerchantPhase.ARRIVED
    }

    /** 组装行商完成通知正文：事件标题 + 收益/亏损（结算额 − 本金）。 */
    internal fun merchantProfitText(eventTitle: String, settlementAmount: Long, principal: Long): String {
        val delta = settlementAmount - principal
        val profit = if (delta >= 0L) "收益 +$delta" else "亏损 $delta"
        val title = eventTitle.ifBlank { "行商" }
        return "$title · $profit"
    }

    private fun formatMerchantEpoch(epochMs: Long): String =
        if (epochMs <= 0L) "未知" else MERCHANT_TIME_FORMAT.format(java.util.Date(epochMs))

    internal enum class MerchantPhase { IN_TRANSIT, ARRIVED, SETTLED }

    /** 在数组里按 [matchKey] 找到 [value] 对应的那一条，返回它的 [nameKey] 字段（找不到就退回原值）。 */
    private fun lookupName(array: JSONArray?, matchKey: String, value: String, nameKey: String): String {
        if (array == null || value.isBlank()) return value
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString(matchKey) == value) return item.optString(nameKey).ifBlank { value }
        }
        return value
    }

    /** 车马的中文名与效果，形如"河曲马 · 速度 +5%"；查不到时退回 id。 */
    private fun transportLabel(transportId: Long, transports: JSONArray?): String {
        if (transports == null || transportId <= 0L) return ""
        for (index in 0 until transports.length()) {
            val item = transports.optJSONObject(index) ?: continue
            if (item.optLong("id", 0L) != transportId) continue
            val name = item.optString("name").ifBlank { "车马 $transportId" }
            val speed = item.optLong("speedPercent", 0L)
            val trait = item.optString("traitName")
            return listOfNotNull(
                name,
                speed.takeIf { it != 0L }?.let { "速度 ${if (it > 0) "+" else ""}$it%" },
                trait.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }
        return "车马 $transportId"
    }

    /**
     * 运签效果文案。
     *
     * 效果类型是游戏侧的枚举，直接显示 MERCHANT_PROFIT_BONUS 这种值用户看不懂，按已知类型翻译。
     */
    internal fun blessingEffectText(effectType: String, effectValue: String): String = when (effectType.trim().uppercase()) {
        "MERCHANT_PROFIT_BONUS" -> "商事盈利收益率提升 $effectValue%"
        "LORE_THRESHOLD_BONUS" -> "下一次每日轶闻：绿色及以上概率提升 $effectValue 个百分点"
        "" -> ""
        else -> "$effectType +$effectValue"
    }

    internal data class MerchantTrip(
        val hasTrip: Boolean,
        val tripId: Long = 0L,
        val status: String = "",
        val startTimeMs: Long = 0L,
        val endTimeMs: Long = 0L,
        val settlementAmount: Long = 0L,
        val principal: Long = 0L,
        val eventTitle: String = "",
        val eventContent: String = "",
        val cityCode: String = "",
        val cityName: String = "",
        val transportId: Long = 0L,
        /** 车马名称/效果，形如"河曲马 · 速度 +5%"；取不到时为空串。 */
        val transportLabel: String = "",
        val blessingName: String = "",
        val blessingEffectType: String = "",
        val blessingEffectValue: String = "",
    )

    internal data class Outcome(
        val result: String,
        val message: String,
        val state: JSONObject = JSONObject(),
        val notify: Boolean = true,
        /** 展示给用户的细节（运签/奖励/事件/期物…），落进任务记录供主界面详情页渲染。 */
        val detail: JSONObject = JSONObject(),
    )

    internal const val REAMICRO_BASE_URL = "https://api.reamicro.zhendong.ltd/"

    /**
     * 道观运签的签种。wire 值取自宿主 `ui/shrine/components/TempleWay`（LUCK/SAFETY/WEALTH），
     * 不是我们自造的枚举，改名会让祈禳请求被服务端判为非法。
     */
    internal const val BLESSING_LUCK = "LUCK"
    internal const val BLESSING_SAFETY = "SAFETY"
    internal const val BLESSING_WEALTH = "WEALTH"

    /**
     * 禁当期物：这些消耗品另有用途（祈禳/传承/夺宝需要），典当掉会让对应玩法缺料。
     * 与参考脚本的 PAWN_PROHIBITED 一致。
     */
    internal val PROHIBITED_PAWN_PROP_HINTS = mapOf(
        "11" to "传承消耗物品（清酒）",
        "12" to "祈禳消耗物品（剡藤）",
        "13" to "传承消耗物品（檀香）",
        "14" to "祈禳消耗物品（青瓷）",
        "15" to "祈禳消耗物品（徽墨）",
        "16" to "备选消耗物品（端砚）",
        "17" to "夺宝消耗物品（琬琰）",
        "18" to "传承消耗物品（欹器）",
    )

    private const val TRAVELING_MERCHANT_POLL_MS = 4L * 3_600_000L
    private const val TRAVELING_MERCHANT_ARRIVE_GRACE_MS = 60_000L
    internal const val TASK_RETRY_INTERVAL_MS = 5 * 60_000L

    /** 服务端没给奖励解锁时刻时的重试间隔：按小时回来问一次，直到领到。 */
    private const val CLAIM_RETRY_INTERVAL_MS = 3_600_000L

    /** 已播报过结果的趟次（只管通知去重，不代表已经自动完成）。 */
    internal const val KEY_MERCHANT_NOTIFIED_TRIP = "merchantLastNotifiedTripId"
    private val MERCHANT_TIME_FORMAT = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
}
