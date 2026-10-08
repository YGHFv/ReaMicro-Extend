package com.reamicro.fix.cloud.local

import com.reamicro.fix.association.network.HttpClient
import com.reamicro.fix.notification.cloudTaskItemsSummary
import com.reamicro.fix.notification.cloudTaskQualityPriority
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

object CloudTaskLocalRunner {

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
            val blessEffect = blessing.detail.optString("效果").trim()

            if (nextTrip.endTimeMs > 0L) {
                val endText = formatMerchantEpoch(nextTrip.endTimeMs)
                message += "，结束时间 $endText"
                detail.put("新行商结束时间", endText)
            }
            if (blessEffect.isNotBlank()) message += "，运签效果：$blessEffect"
            if (blessing.detail.length() > 0) {
                detail.put("新行商运签", blessing.detail.optString("运签"))
                if (blessEffect.isNotBlank()) detail.put("新行商运签效果", blessEffect)
            }
            return Outcome("success", message, state, detail = detail)
        }
        state.put("nextRunAtOverride", now + TRAVELING_MERCHANT_POLL_MS)
        return Outcome("success", message, state, notify = notify, detail = detail)
    }

    private fun addActiveBlessing(detail: JSONObject, active: JSONObject) {
        if (active.length() <= 0) return
        detail.put("运签", active.optString("运签"))
        detail.put("运签效果", active.optString("效果"))
    }

    private fun merchantDetail(trip: MerchantTrip): JSONObject = JSONObject()
        .put(
            "事件",
            trip.eventTitle.ifBlank {

                if (trip.status.equals("TRAVELING", ignoreCase = true)) "待结算（事件在抵达后才生成）" else "无"
            },
        )
        .apply { if (trip.eventContent.isNotBlank()) put("事件详情", trip.eventContent) }
        .put("行程", "${formatMerchantEpoch(trip.startTimeMs)} → ${formatMerchantEpoch(trip.endTimeMs)}")
        .put("城池", trip.cityName.ifBlank { trip.cityCode })
        .apply { if (trip.transportLabel.isNotBlank()) put("车马", trip.transportLabel) }
        .apply {

            if (trip.blessingName.isNotBlank()) {
                val effect = blessingEffectText(trip.blessingEffectType, trip.blessingEffectValue)
                put("运签", listOf(trip.blessingName, effect).filter { it.isNotBlank() }.joinToString(" · "))
            }
        }
        .put("本金", "${trip.principal} 铜")
        .put("结算", "${trip.settlementAmount} 铜")
        .put("状态", trip.status.ifBlank { "—" })

    private fun rememberedMerchantConfig(task: JSONObject, trip: MerchantTrip): MerchantConfig = MerchantConfig(
        cityCode = trip.cityCode.ifBlank { task.optString(LocalTaskStore.KEY_MERCHANT_LAST_CITY) },
        transportId = if (trip.hasTrip) trip.transportId else task.optLong(LocalTaskStore.KEY_MERCHANT_LAST_TRANSPORT, 0L),
        principal = trip.principal.takeIf { it > 0L } ?: task.optLong(LocalTaskStore.KEY_MERCHANT_LAST_PRINCIPAL, 0L),
    )

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
            val rewards = dailyLoreRewardItems(data)
            val detail = JSONObject()
                .put("轶闻", data.optString("title"))
                .put("奖励", if (claimed) "已领取" else "待领取（${formatMerchantEpoch(nextCheck)} 再次检查）")
                .put(KEY_REWARD_ITEMS, rewards)
                .put(DAILY_LORE_DETAIL_KEY, dailyLoreSnapshot(data, claimed))
            val summary = cloudTaskItemsSummary(rewards.toString())
            if (summary.isNotBlank()) detail.put("获得", summary)
            addActiveBlessing(detail, blessing.detail)

            val rewardNote = if (summary.isBlank()) "" else "（$summary）"
            return Outcome(result, message + rewardNote, state, detail = detail)
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
            .put(KEY_REWARD_ITEMS, JSONArray(items))
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

    internal fun runAutoRead(
        task: JSONObject, request: JSONObject, credential: JSONObject,
        checkpoint: (JSONObject) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
    ): Outcome {
        val now = clock()
        val durationMinutes = request.optInt("durationMinutes", 30).coerceIn(1, 720)
        val dailyLimit = request.optInt("dailyLimitMinutes", 720).coerceIn(1, 1_440)
        val today = AutoReadTimeLock.recordDate(now)
        val localDay = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val usedToday = AutoReadTimeLock.usedMinutes(task, now)
        if (usedToday >= dailyLimit) return Outcome("success", "今日已达到 ${dailyLimit} 分钟上限", JSONObject(task.toString()))
        AutoReadTimeLock.deferredOutcome(durationMinutes, task, now, dailyLimit)?.let { return it }
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
            val beforeSubmit = clock()
            if (AutoReadTimeLock.recordDate(beforeSubmit) != today ||
                java.time.Instant.ofEpochMilli(beforeSubmit).atZone(ZoneId.systemDefault()).toLocalDate() != localDay) {
                state.put("nextRunAtOverride", beforeSubmit + 60_000L)
                return Outcome("paused", "已跨日期，自动阅读将按新一天的真实时间重新检查", state, notify = false)
            }

            AutoReadTimeLock.deferredOutcome(durationMinutes, state, beforeSubmit, dailyLimit)?.let { return it }
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

    private fun blessingDetail(blessing: JSONObject, source: String): JSONObject {
        val type = blessing.optString("blessingType")
        return JSONObject()
            .put("运签", "$source · ${blessingLabel(type)} ${blessing.optString("name")}".trim())
            .put("效果", blessing.optString("description"))
    }

    internal data class BlessingCheck(
        val failure: String?,
        val detail: JSONObject,

        val prayed: Boolean = false,

        val activeType: String = "",

        val replacedType: String = "",
    )

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

        "" -> "不祈禳"
        BLESSING_LUCK -> "求运签"
        BLESSING_SAFETY -> "求安签"
        BLESSING_WEALTH -> "求财签"
        else -> "运签"
    }

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

        var catalog = mergePawnPropCatalog(
            pawnPropCatalog(task),
            listOf(PawnPropChoice(propId, data.optString("specialPropName").trim(), data.optString("specialPropQuality"))),
        )
        if (remaining <= 0) {
            return Outcome("success", "今日可典当次数已用完（$usedToday/$maxPerDay）", pawnCatalogState(catalog), notify = false)
        }
        if (propId in forbiddenPawnPropIds(request)) {

            val hint = PROHIBITED_PAWN_PROP_HINTS[propId]
            val why = if (hint.isNullOrBlank()) "在禁当清单里" else "是$hint"
            return Outcome("success", "今日期物「$propName」$why，跳过典当", pawnCatalogState(catalog), notify = false)
        }
        val forbidden = forbiddenPawnPropIds(request)

        if ("name:$propName" in forbidden) {
            val hint = PROHIBITED_PAWN_PROP_HINTS["name:$propName"]
            return Outcome("success", "今日期物「$propName」是$hint，跳过典当", pawnCatalogState(catalog), notify = false)
        }

        if (data.optString("specialPropQuality").trim().equals("RED", ignoreCase = true)) {
            return Outcome("success", "今日期物「$propName」是红色品质，不自动典当", pawnCatalogState(catalog), notify = false)
        }
        val materials = postReaMicro(
            baseUrl,
            token,
            JSONObject(),
            request.optString("materialsEndpoint").ifBlank { "rest/community/get-user-materials" },
        )
        operationError(materials)?.let { return Outcome("failed", "读取背包失败：$it", pawnCatalogState(catalog)) }
        val list = materials.optJSONObject("data")?.optJSONArray("materials")
            ?: materials.optJSONArray("materials")
            ?: JSONArray()

        catalog = mergePawnPropCatalog(catalog, bagPawnPropChoices(list))
        var target: JSONObject? = null
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            if (item.opt("propId")?.toString()?.trim() == propId) {
                target = item
                break
            }
        }
        if (target == null) {
            return Outcome("success", "背包里没有期物「$propName」，跳过典当", pawnCatalogState(catalog), notify = false)
        }
        val quantity = target.optInt("quantity", 0).coerceAtLeast(0)
        if (quantity <= 0) {
            return Outcome("success", "期物「$propName」持有数量为 0，跳过典当", pawnCatalogState(catalog), notify = false)
        }
        val userPropId = target.opt("userPropId")?.toString()?.trim().orEmpty()
            .takeIf { it.isNotBlank() }
            ?: return Outcome("failed", "期物「$propName」缺少 userPropId，无法典当", pawnCatalogState(catalog))
        var successCount = 0
        var coin = 0L
        var failure: String? = null
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val state = JSONObject().put("lastPawnDate", today).put("pawnUsedToday", usedToday).put("pawnLastCoin", 0L)
            .put(KEY_PAWN_PROP_CATALOG, catalog)
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

            cityCode = trip.optString("cityCode"),
            transportId = trip.optLong("transportId", 0L),

            cityName = lookupName(data.optJSONArray("cities"), "code", trip.optString("cityCode"), "name"),
            transportLabel = transportLabel(trip.optLong("transportId", 0L), data.optJSONArray("transports")),
            blessingName = trip.optString("blessingName"),
            blessingEffectType = trip.optString("blessingEffectType"),
            blessingEffectValue = trip.optString("blessingEffectValue"),
        )
    }

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

    internal data class MerchantAction(

        val notify: Boolean,

        val settle: Boolean,

        val start: Boolean,
    )

    internal fun merchantPhase(trip: MerchantTrip, now: Long): MerchantPhase = when {
        !trip.hasTrip -> MerchantPhase.SETTLED
        trip.status.equals("SETTLED", ignoreCase = true) -> MerchantPhase.SETTLED
        trip.endTimeMs <= 0L -> MerchantPhase.IN_TRANSIT
        now < trip.endTimeMs -> MerchantPhase.IN_TRANSIT
        else -> MerchantPhase.ARRIVED
    }

    internal fun merchantProfitText(eventTitle: String, settlementAmount: Long, principal: Long): String {
        val delta = settlementAmount - principal
        val profit = if (delta >= 0L) "收益 +$delta" else "亏损 $delta"
        val title = eventTitle.ifBlank { "行商" }
        return "$title · $profit"
    }

    private fun formatMerchantEpoch(epochMs: Long): String =
        if (epochMs <= 0L) "未知" else MERCHANT_TIME_FORMAT.format(java.util.Date(epochMs))

    internal enum class MerchantPhase { IN_TRANSIT, ARRIVED, SETTLED }

    private fun lookupName(array: JSONArray?, matchKey: String, value: String, nameKey: String): String {
        if (array == null || value.isBlank()) return value
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString(matchKey) == value) return item.optString(nameKey).ifBlank { value }
        }
        return value
    }

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

    internal fun blessingEffectText(effectType: String, effectValue: String): String = when (effectType.trim().uppercase()) {

        "MERCHANT_DISASTER_REDUCTION" -> "行商灾害概率降低 $effectValue%"
        "MERCHANT_PROFIT_BONUS" -> "商事盈利收益率提升 $effectValue%"
        "MERCHANT_LOSS_REDUCTION" -> "商事亏损降低 $effectValue%"
        "MERCHANT_DURATION_REDUCTION" -> "行商耗时缩短 $effectValue%"
        "MERCHANT_ENCOUNTER_BONUS" -> "行商奇遇概率提升 $effectValue%"

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

        val detail: JSONObject = JSONObject(),
    )

    internal const val REAMICRO_BASE_URL = "https://api.reamicro.zhendong.ltd/"

    internal const val BLESSING_LUCK = "LUCK"
    internal const val BLESSING_SAFETY = "SAFETY"
    internal const val BLESSING_WEALTH = "WEALTH"

    internal val PROHIBITED_PAWN_PROP_HINTS = mapOf(
        "11" to "传承消耗物品（清酒）",
        "12" to "祈禳消耗物品（剡藤）",
        "13" to "传承消耗物品（檀香）",
        "14" to "祈禳消耗物品（青瓷）",
        "15" to "祈禳消耗物品（徽墨）",
        "16" to "备选消耗物品（端砚）",
        "17" to "夺宝消耗物品（琬琰）",
        "18" to "传承消耗物品（欹器）",
        "name:青圭" to "招募消耗物品（青圭）",
    )

    private val PROHIBITED_PAWN_PROP_QUALITIES = mapOf(

        "17" to "RED",
        "18" to "RED",
        "name:青圭" to "RED",
    )

    internal const val KEY_PAWN_PROP_CATALOG = "pawnPropCatalog"

    internal data class PawnPropChoice(
        val propId: String,
        val name: String,
        val quality: String,
        val hint: String = "",
    ) {

        val label: String get() = if (hint.isBlank()) name else "$name（$hint）"
    }

    internal fun pawnPropCatalog(state: JSONObject): JSONObject =
        state.optJSONObject(KEY_PAWN_PROP_CATALOG) ?: JSONObject()

    internal fun mergePawnPropCatalog(catalog: JSONObject, seen: List<PawnPropChoice>): JSONObject {
        val merged = JSONObject(catalog.toString())
        for (choice in seen) {
            val propId = choice.propId.trim()
            val name = choice.name.trim()

            if (propId.isEmpty() || propId == "0" || name.isEmpty()) continue
            val previous = merged.optJSONObject(propId)
            merged.put(
                propId,
                JSONObject()
                    .put("name", name)
                    .put("quality", choice.quality.trim().ifBlank { previous?.optString("quality").orEmpty() }),
            )
        }
        return merged
    }

    internal fun bagPawnPropChoices(materials: JSONArray): List<PawnPropChoice> =
        (0 until materials.length()).mapNotNull { index ->
            val item = materials.optJSONObject(index) ?: return@mapNotNull null
            PawnPropChoice(
                propId = item.opt("propId")?.toString().orEmpty().trim(),
                name = item.optString("name").ifBlank { item.optString("propName") }.trim(),
                quality = item.optString("quality").ifBlank { item.optString("propQuality") }.trim(),
            )
        }

    internal fun pawnPropChoices(state: JSONObject): List<PawnPropChoice> {
        val catalog = pawnPropCatalog(state)
        val nameKeyedDefaults = PROHIBITED_PAWN_PROP_HINTS.keys
            .filter { it.startsWith("name:") }
            .map { it.removePrefix("name:") }
            .toSet()
        val entries = LinkedHashMap<String, PawnPropChoice>()
        for ((key, hint) in PROHIBITED_PAWN_PROP_HINTS) {

            entries[key] = PawnPropChoice(
                propId = key,
                name = hint.substringAfter('（', hint).substringBefore('）'),
                quality = PROHIBITED_PAWN_PROP_QUALITIES[key].orEmpty(),
                hint = hint.substringBefore('（'),
            )
        }
        catalog.keys().forEach { propId ->
            val item = catalog.optJSONObject(propId) ?: return@forEach
            val name = item.optString("name").trim()
            if (name.isEmpty() || name in nameKeyedDefaults) return@forEach
            entries[propId] = PawnPropChoice(
                propId = propId,
                name = name,
                quality = item.optString("quality").trim(),
                hint = PROHIBITED_PAWN_PROP_HINTS[propId]?.substringBefore('（').orEmpty(),
            )
        }
        return entries.values.sortedWith(
            compareByDescending<PawnPropChoice> { cloudTaskQualityPriority(it.quality) }
                .thenBy { it.name }
                .thenBy { it.propId.toLongOrNull() ?: Long.MAX_VALUE },
        )
    }

    private fun pawnCatalogState(catalog: JSONObject): JSONObject =
        JSONObject().put(KEY_PAWN_PROP_CATALOG, catalog)

    internal data class PawnCatalogFetch(val catalog: JSONObject, val error: String?)

    internal fun fetchPawnPropCatalog(
        token: String,
        baseUrl: String = REAMICRO_BASE_URL,
        request: JSONObject = JSONObject(),
    ): PawnCatalogFetch {
        if (token.isBlank()) throw IllegalStateException("阅微登录凭据无效")
        val errors = mutableListOf<String>()
        var catalog = JSONObject()
        runCatching {
            val info = postReaMicro(
                baseUrl,
                token,
                JSONObject(),
                request.optString("pawnCountEndpoint").ifBlank { "rest/community/get-pawn-count" },
            )
            operationError(info)?.let { throw IllegalStateException(it) }
            val data = info.optJSONObject("data") ?: info
            catalog = mergePawnPropCatalog(
                catalog,
                listOf(
                    PawnPropChoice(
                        propId = data.opt("specialPropId")?.toString().orEmpty().trim(),

                        name = data.optString("specialPropName").trim(),
                        quality = data.optString("specialPropQuality").trim(),
                    ),
                ),
            )
        }.onFailure { errors += "当日期物：${it.message ?: "读取失败"}" }
        runCatching {
            val materials = postReaMicro(
                baseUrl,
                token,
                JSONObject(),
                request.optString("materialsEndpoint").ifBlank { "rest/community/get-user-materials" },
            )
            operationError(materials)?.let { throw IllegalStateException(it) }
            val list = materials.optJSONObject("data")?.optJSONArray("materials")
                ?: materials.optJSONArray("materials")
                ?: JSONArray()
            catalog = mergePawnPropCatalog(catalog, bagPawnPropChoices(list))
        }.onFailure { errors += "背包：${it.message ?: "读取失败"}" }
        return PawnCatalogFetch(catalog, errors.takeIf { it.isNotEmpty() }?.joinToString("；"))
    }

    internal data class MerchantCityOption(
        val code: String,
        val label: String,

        val requiredTransportType: String,

        val routeType: String,

        val baseDurationMinutes: Long,
    )

    internal fun hostExpectedTransportType(routeType: String): String =
        if (routeType.trim() == "LAND") "HORSE" else "SHIP"

    internal data class MerchantTransportOption(
        val id: String,
        val label: String,
        val owned: Boolean,
        val transportType: String,
        val carryingCapacity: Long,
        val speedPercent: Long,
    )

    internal data class MerchantOptionsFetch(
        val cities: List<MerchantCityOption>,
        val transports: List<MerchantTransportOption>,
        val activeCityCode: String,
        val activeTransportId: Long,

        val noTransportCapacity: Long,
        val error: String?,
    )

    internal fun fetchMerchantOptions(
        token: String,
        baseUrl: String = REAMICRO_BASE_URL,
    ): MerchantOptionsFetch {
        if (token.isBlank()) throw IllegalStateException("阅微登录凭据无效")
        val info = postReaMicro(baseUrl, token, JSONObject(), "rest/community/get-traveling-merchant")
        operationError(info)?.let { throw IllegalStateException(it) }
        val data = info.optJSONObject("data") ?: info
        val cities = data.optJSONArray("cities") ?: JSONArray()
        val transports = data.optJSONArray("transports") ?: JSONArray()
        val trip = data.optJSONObject("activeTrip")
        return MerchantOptionsFetch(
            cities = (0 until cities.length()).mapNotNull { index ->
                cities.optJSONObject(index)?.let { city ->
                    val code = city.optString("code").trim()

                    MerchantCityOption(
                        code = code,
                        label = city.optString("name").trim().ifBlank { code },
                        requiredTransportType = city.optString("requiredTransportType").trim(),
                        routeType = city.optString("routeType").trim(),
                        baseDurationMinutes = city.optLong("baseDurationMinutes", 0L),
                    )
                }
            },
            transports = (0 until transports.length()).mapNotNull { index ->
                transports.optJSONObject(index)?.let { transport ->
                    val id = transport.opt("id")?.toString().orEmpty().trim()
                    MerchantTransportOption(
                        id = id,
                        label = transport.optString("name").trim().ifBlank { id },
                        owned = transport.optBoolean("owned", false),
                        transportType = transport.optString("transportType").trim(),
                        carryingCapacity = transport.optLong("carryingCapacity", 0L),
                        speedPercent = transport.optLong("speedPercent", 0L),
                    )
                }
            },
            activeCityCode = trip?.optString("cityCode").orEmpty().trim(),
            activeTransportId = trip?.optLong("transportId", 0L) ?: 0L,
            noTransportCapacity = data.optLong("noTransportCapacity", 0L),
            error = null,
        )
    }

    private fun forbiddenPawnPropIds(request: JSONObject): Set<String> =
        request.optJSONArray("forbiddenPawnPropIds")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotEmpty() }
            }.toSet()
        } ?: PROHIBITED_PAWN_PROP_HINTS.keys

    internal fun parseForbiddenPawnPropIds(text: String): Set<String> =
        text.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    private const val TRAVELING_MERCHANT_POLL_MS = 4L * 3_600_000L
    private const val TRAVELING_MERCHANT_ARRIVE_GRACE_MS = 60_000L
    internal const val TASK_RETRY_INTERVAL_MS = 5 * 60_000L

    private const val CLAIM_RETRY_INTERVAL_MS = 3_600_000L

    internal const val KEY_MERCHANT_NOTIFIED_TRIP = "merchantLastNotifiedTripId"

    internal const val KEY_REWARD_ITEMS = "奖励物品"

    private val MERCHANT_TIME_FORMAT = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
}
