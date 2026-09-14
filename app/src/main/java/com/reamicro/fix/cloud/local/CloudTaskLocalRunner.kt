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
 * 本对象现在只保留与传输方式无关的任务实现——签到、抽卡、自动阅读、行商通知。
 */
object CloudTaskLocalRunner {
    /**
     * 执行一条阅微自动任务。任务运行状态通过返回的 state 表达，由调用方决定如何落盘。
     */
    internal fun runTask(taskType: String, task: JSONObject, request: JSONObject, credential: JSONObject): Outcome =
        when (taskType) {
            "yeshe_checkin" -> runCheckin(task, request, credential)
            "yeshe_draw_card" -> runDrawCard(task, request, credential)
            "cloud_auto_read" -> runAutoRead(task, request, credential)
            "traveling_merchant" -> runMerchantNotify(task, request, credential)
            "pawn" -> runPawn(task, request, credential)
            else -> Outcome("failed", "模块暂不支持此任务类型")
        }

    /**
     * 行商通知：每次执行拉取行商状态。
     * - 无活跃行商：静默等待，下次 4 小时后再查。
     * - 行商在途（now < endTime）：把下次检查排到 endTime，暂停 4 小时轮询。
     * - 行商已抵达（now >= endTime 且未结算、未通知过）：发出「收益/亏损」通知；
     *   若开启自动完成，则结算并（在配置齐全时）开启新行商。
     */
    internal fun runMerchantNotify(task: JSONObject, request: JSONObject, credential: JSONObject): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { REAMICRO_BASE_URL }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        // 行商可配置求安/求财：跑之前先确认有道观运签，没有就补一支（已有签不替换）。
        val blessingType = request.optString("blessingType")
        val blessing = ensureTaoistBlessing(baseUrl, token, request, blessingType)
        // 任务可能设成"不祈禳"或没配签种，但游戏里账户上本来就有一支签在生效（比如「增益签」），
        // 用户要看到的是"这趟行商吃了什么加成"，所以无论祈不祈禳都把当前签读出来（只读）。
        val activeBlessing = currentBlessingDetail(baseUrl, token, request, blessing)
        val blessingSuffix = if (blessing.failure != null) "（运签：${blessing.failure}）" else blessingNote(blessingType)
        val now = System.currentTimeMillis()
        val lastNotified = task.optLong("merchantLastNotifiedTripId", 0L)
        val body = postReaMicro(baseUrl, token, request.optJSONObject("body") ?: JSONObject(), request.optString("endpoint").ifBlank { "rest/community/get-traveling-merchant" })
        businessError(body)?.let { return Outcome("failed", "获取行商状态失败：$it") }
        val trip = parseMerchantTrip(body)
        val pollAgain = now + TRAVELING_MERCHANT_POLL_MS
        // 这趟行商用的城池/本金/车马就是「上次行商配置」的来源：阅微只在内存里保存用户选择，
        // 重启即丢，所以由我们记下来，供自动开新行商时留空沿用。
        val remembered = rememberedMerchantConfig(task, trip)
        if (!trip.hasTrip) {
            // 没有在途行商：开着"自动完成"且参数齐全时补开一趟，否则这条链会在
            // （比如手动结算过一次之后）彻底断掉，表现就是"自动行商不工作"。
            val autoComplete = request.optBoolean("merchantAutoComplete", false)
            if (autoComplete) {
                val config = resolveStartConfig(request, remembered)
                if (config.isComplete && maybeStartMerchant(baseUrl, token, request, config)) {
                    return Outcome(
                        "success",
                        "当前没有进行中的行商，已按配置开启新行商$blessingSuffix",
                        merchantState(pollAgain, lastNotified, config),
                        notify = true,
                    )
                }
            }
            return Outcome("success", "当前没有进行中的行商", merchantState(pollAgain, lastNotified, remembered), notify = false)
        }
        when (merchantPhase(trip, now)) {
            MerchantPhase.SETTLED -> {
                // 游戏在 endTime 到达时就会把状态置成 SETTLED —— 也就是说**这里才是常见路径**。
                // 此前这个分支只说一句"行商已结算"就按 4 小时重排，于是通知里既没有事件也没有
                // 收益/亏损、节奏还退化成固定 4 小时轮询。现在按趟去重后如实播报结算结果。
                if (trip.tripId <= 0L || trip.tripId == lastNotified) {
                    return Outcome("success", "行商已结算", merchantState(pollAgain, lastNotified, remembered), notify = false)
                }
                var message = merchantProfitText(trip.eventTitle, trip.settlementAmount, trip.principal) + blessingSuffix
                val detail = merchantDetail(trip)
                detail.put("结果", merchantProfitText(trip.eventTitle, trip.settlementAmount, trip.principal))
                if (activeBlessing.length() > 0) detail.put("运签", activeBlessing.optString("运签"))
                if (activeBlessing.length() > 0) detail.put("运签效果", activeBlessing.optString("效果"))
                val autoComplete = request.optBoolean("merchantAutoComplete", false)
                if (autoComplete) {
                    val config = resolveStartConfig(request, remembered)
                    if (config.isComplete) {
                        message += if (maybeStartMerchant(baseUrl, token, request, config)) "，已开启新行商" else "（未能开启新行商）"
                    } else {
                        message += "（未能开启新行商：${merchantStartHint(config)}）"
                    }
                }
                return Outcome("success", message, merchantState(pollAgain, trip.tripId, remembered), notify = true, detail = detail)
            }
            MerchantPhase.NOTIFIED -> {
                return Outcome("success", "行商已结算", merchantState(pollAgain, lastNotified, remembered), notify = false)
            }
            MerchantPhase.IN_TRANSIT -> {
                val resumeAt = trip.endTimeMs + TRAVELING_MERCHANT_ARRIVE_GRACE_MS
                val message = "行商进行中，预计 ${formatMerchantEpoch(trip.endTimeMs)} 完成"
                return Outcome(
                    "success",
                    message,
                    merchantState(resumeAt, lastNotified, remembered),
                    notify = false,
                    detail = merchantDetail(trip),
                )
            }
            MerchantPhase.ARRIVED -> {
                if (trip.tripId == lastNotified) {
                    return Outcome("success", "行商已通知，等待结算", merchantState(pollAgain, lastNotified, remembered), notify = false)
                }
                var message = merchantProfitText(trip.eventTitle, trip.settlementAmount, trip.principal) + blessingSuffix
                val detail = merchantDetail(trip)
                detail.put("结果", merchantProfitText(trip.eventTitle, trip.settlementAmount, trip.principal))
                if (activeBlessing.length() > 0) detail.put("运签", activeBlessing.optString("运签"))
                if (activeBlessing.length() > 0) detail.put("运签效果", activeBlessing.optString("效果"))
                val autoComplete = request.optBoolean("merchantAutoComplete", false)
                if (autoComplete && trip.tripId > 0L) {
                    val settle = postReaMicro(baseUrl, token, JSONObject().put("tripId", trip.tripId), request.optString("settleEndpoint").ifBlank { "rest/community/settle-traveling-merchant" })
                    val settleError = businessError(settle)
                    if (settleError != null) {
                        message += "（自动完成失败：$settleError）"
                    } else {
                        // 结算响应里带着最终结算额：用它重算收益/亏损，比抵达瞬间的预估值准。
                        val settledTrip = parseMerchantTrip(settle)
                        if (settledTrip.hasTrip && settledTrip.settlementAmount > 0L) {
                            message = merchantProfitText(
                                settledTrip.eventTitle.ifBlank { trip.eventTitle },
                                settledTrip.settlementAmount,
                                settledTrip.principal.takeIf { it > 0L } ?: trip.principal,
                            )
                        }
                        message += "，已自动完成行商"
                        detail.put("自动完成", "已结算" + if (settleError == null) "" else "（$settleError）")
                        val start = startResult(baseUrl, token, request, remembered)
                        if (start != null) message += "并开启新行商" else message += "（未能开启新行商：${merchantStartHint(resolveStartConfig(request, remembered))}）"
                    }
                }
                return Outcome("success", message, merchantState(pollAgain, trip.tripId, remembered), notify = true, detail = detail)
            }
        }
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
        transportId = trip.transportId.takeIf { it > 0L } ?: task.optLong(LocalTaskStore.KEY_MERCHANT_LAST_TRANSPORT, 0L),
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
        config.transportId <= 0L -> "缺少车马，请先跑一趟行商或手动填写 transportId"
        config.principal <= 0L -> "缺少本金，请先跑一趟行商或手动填写本金"
        else -> "参数不完整"
    }

    /** 配置齐全时开启新行商，返回是否成功发起；不齐全返回 null。 */
    private fun startResult(baseUrl: String, token: String, request: JSONObject, remembered: MerchantConfig): Boolean? {
        val config = resolveStartConfig(request, remembered)
        if (!config.isComplete) return null
        return maybeStartMerchant(baseUrl, token, request, config)
    }

    /** 用给定参数开启新行商。 */
    private fun maybeStartMerchant(baseUrl: String, token: String, request: JSONObject, config: MerchantConfig): Boolean {
        val payload = JSONObject()
            .put("cityCode", config.cityCode)
            .put("principal", config.principal)
            .put("transportId", config.transportId)
        val started = runCatching {
            postReaMicro(baseUrl, token, payload, request.optString("startEndpoint").ifBlank { "rest/community/start-traveling-merchant" })
        }.getOrNull() ?: return false
        return businessError(started) == null
    }

    /** 行商启动参数；[isComplete] 表示三项都可用。 */
    internal data class MerchantConfig(val cityCode: String, val transportId: Long, val principal: Long) {
        val isComplete: Boolean get() = cityCode.isNotBlank() && transportId > 0L && principal > 0L
    }

    private fun merchantState(nextRunAt: Long, lastNotifiedTripId: Long, config: MerchantConfig): JSONObject = JSONObject()
        .put("nextRunAtOverride", nextRunAt)
        .put("merchantLastNotifiedTripId", lastNotifiedTripId)
        // 落盘「上次行商配置」，供下次自动开新行商留空时沿用。
        .put(LocalTaskStore.KEY_MERCHANT_LAST_CITY, config.cityCode)
        .put(LocalTaskStore.KEY_MERCHANT_LAST_TRANSPORT, config.transportId)
        .put(LocalTaskStore.KEY_MERCHANT_LAST_PRINCIPAL, config.principal)

    private fun runCheckin(task: JSONObject, request: JSONObject, credential: JSONObject): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        // 每日轶闻固定求运（LUCK）：先确认道观运签，没有就补一支。
        val blessing = ensureTaoistBlessing(baseUrl, token, request, BLESSING_LUCK)
        val blessingSuffix = if (blessing.failure != null) "（运签：${blessing.failure}）" else blessingNote(BLESSING_LUCK)
        val body = postReaMicro(baseUrl, token, request.optJSONObject("body") ?: JSONObject(), request.optString("endpoint").ifBlank { "rest/community/get-daily-lore" })
        val error = businessError(body)
        if (error != null) return Outcome("failed", "获取每日轶闻失败：$error")
        val data = body.optJSONObject("data") ?: JSONObject()
        val loreId = data.optLong("id", data.optLong("loreId", body.optLong("id", 0L)))
        var claimed = data.optBoolean("claimed", false)
        var claimDueAt = task.optLong("claimDueAt", 0L).takeIf { it > 0L }
            ?: data.optLong("endTime", 0L).let { if (it > 0L && it < 100_000_000_000L) it * 1_000L else it }
        if (!data.optBoolean("isFinish", false)) {
            if (loreId <= 0L) return Outcome("failed", "阅微每日轶闻响应缺少 userLoreId")
            val complete = postReaMicro(baseUrl, token, JSONObject().put("userLoreId", loreId), request.optString("completeEndpoint").ifBlank { "rest/community/complete-daily-lore" })
            val completeError = businessError(complete)
            if (completeError != null) return Outcome("failed", "野社签到提交失败：$completeError")
            claimed = (complete.optJSONObject("data") ?: complete).optBoolean("claimed", false)
        }
        if (!claimed && claimDueAt > 0L && System.currentTimeMillis() >= claimDueAt && loreId > 0L) {
            val claim = postReaMicro(baseUrl, token, JSONObject().put("userLoreId", loreId), request.optString("completeEndpoint").ifBlank { "rest/community/complete-daily-lore" })
            val claimError = businessError(claim)
            if (claimError != null) return Outcome("failed", "签到奖励领取失败：$claimError")
            claimed = (claim.optJSONObject("data") ?: claim).optBoolean("claimed", true)
        }
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        if (claimDueAt <= 0L) claimDueAt = System.currentTimeMillis() + 8L * 3_600_000L
        val state = JSONObject()
            .put("lastCheckinDate", today)
            .put("lastCheckinAt", System.currentTimeMillis())
            .put("claimDueAt", claimDueAt)
            .put("nextRunAtOverride", if (claimed) 0L else claimDueAt)
            .put("claimCompletedDate", if (claimed) today else "")
            .put("claimJustCompleted", claimed)
        val checkinMessage = if (claimed) "签到完成，奖励已领取" else "签到完成，等待奖励解锁"
        val detail = JSONObject()
            .put("轶闻", request.optJSONObject("body")?.optString("title").orEmpty())
            .put("奖励", if (claimed) "已领取" else "待解锁（${formatMerchantEpoch(claimDueAt)}）")
        blessing.detail.takeIf { it.length() > 0 }?.let {
            detail.put("运签", it.optString("运签"))
            detail.put("运签效果", it.optString("效果"))
        }
        return Outcome("success", checkinMessage + blessingSuffix, state, detail = detail)
    }

    private fun runDrawCard(task: JSONObject, request: JSONObject, credential: JSONObject): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val configuredLimit = request.optInt("dailyLimit", 3).coerceIn(0, 20)
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val usedToday = if (task.optString("dailyCounterDate") == today) task.optInt("dailyCounter", 0).coerceAtLeast(0) else 0
        val target = if (configuredLimit == 0) {
            val info = postReaMicro(baseUrl, token, JSONObject(), request.optString("userInfoEndpoint").ifBlank { "rest/user/get-user-info" })
            val infoError = businessError(info)
            if (infoError != null) return Outcome("failed", "读取彩筹余额失败：$infoError")
            (info.optJSONObject("data")?.optInt("gem", 0) ?: 0).coerceAtLeast(0)
        } else (configuredLimit - usedToday).coerceAtLeast(0)
        if (target <= 0) return Outcome("success", "今日没有可用彩筹")
        val endpoint = request.optString("endpoint").ifBlank { "rest/community/wish" }
        val items = mutableListOf<JSONObject>()
        var consumed = 0
        while (consumed < target) {
            val count = minOf(9, target - consumed)
            val wishBody = (request.optJSONObject("body") ?: JSONObject()).put("count", count)
            val wish = postReaMicro(baseUrl, token, wishBody, endpoint)
            val error = businessError(wish)
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
        }
        val summary = items.groupBy { "${it.optString("name")}\u0000${it.optString("quality")}" }
            .entries.joinToString("、") { (_, values) -> "${values.first().optString("name")} x${values.sumOf { it.optInt("count", 1) }}" }
        val detail = JSONObject()
            .put("本次祈愿", "$consumed 次")
            .put("获得", if (summary.isBlank()) "无" else summary)
        return Outcome(
            "success",
            if (summary.isBlank()) "抽卡完成" else summary,
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

    private fun runAutoRead(task: JSONObject, request: JSONObject, credential: JSONObject): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val configuredBooks = request.optJSONArray("books") ?: JSONArray()
        val books = if (configuredBooks.length() > 0) {
            configuredBooks
        } else {
            val recent = postReaMicro(
                baseUrl,
                token,
                JSONObject().put("pageNum", 1).put("pageSize", request.optInt("recentLimit", 1).coerceIn(1, 10)),
                request.optString("recentEndpoint").ifBlank { "rest/reader/get-read-record-list" },
            )
            businessError(recent)?.let { return Outcome("failed", "读取最近阅读记录失败：$it") }
            recent.optJSONObject("data")?.optJSONArray("list")
                ?: recent.optJSONArray("list")
                ?: JSONArray()
        }
        val durationMinutes = request.optInt("durationMinutes", 30).coerceIn(1, 720)
        val dailyLimit = request.optInt("dailyLimitMinutes", 720).coerceIn(durationMinutes, 1_440)
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val usedToday = if (task.optString("dailyReadDate") == today) task.optInt("dailyReadMinutes", 0).coerceAtLeast(0) else 0
        val duration = minOf(durationMinutes, (dailyLimit - usedToday).coerceAtLeast(0))
        if (duration <= 0) return Outcome("success", "今日已达到 ${dailyLimit} 分钟上限")
        val rotation = task.optInt("bookRotation", 0).coerceAtLeast(0) % books.length().coerceAtLeast(1)
        var completedBooks = 0
        val names = mutableListOf<String>()
        for (offset in 0 until books.length().coerceAtMost(10)) {
            val book = books.optJSONObject((rotation + offset) % books.length()) ?: continue
            val bookId = book.optLong("bookId", book.optLong("cloudBookId", 0L))
            if (bookId <= 0L) continue
            val payload = JSONObject().put("list", JSONArray().put(
                JSONObject()
                    .put("bookId", bookId)
                    .put("date", today)
                    .put("duration", duration * 60)
                    .put("verify", ""),
            ))
            val result = postReaMicro(baseUrl, token, payload, request.optString("timeEndpoint").ifBlank { "rest/reader/update-read-time-by-date" })
            businessError(result)?.let { return Outcome("failed", "上报阅读时长失败：$it") }
            completedBooks++
            names += book.optString("name").ifBlank { book.optString("bookName").ifBlank { "图书 $bookId" } }
        }
        if (completedBooks == 0) return Outcome("failed", "没有找到可阅读的图书")
        val state = JSONObject()
            .put("dailyReadDate", today)
            .put("dailyReadMinutes", usedToday + duration)
            .put("bookRotation", rotation + completedBooks)
        val detail = JSONObject()
            .put("图书", names.joinToString("、"))
            .put("时长", "$duration 分钟")
            .put("今日累计", "${usedToday + duration} 分钟")
        return Outcome("success", "${names.joinToString("、")} · $duration 分钟", state, detail = detail)
    }

    /**
     * 任务执行前检查道观运签：**没有签才祈禳一支**，已有签不替换（替换会白白消耗祈禳道具）。
     *
     * 返回失败原因（调用方把它附到任务消息里，用户才知道"这次为什么没签加成"）；
     * 成功、未配置签种、或已有签时返回 null。
     */
    private fun ensureTaoistBlessing(
        baseUrl: String,
        token: String,
        request: JSONObject,
        blessingType: String,
    ): BlessingCheck {
        val type = blessingType.trim().uppercase()
        if (type.isBlank()) return BlessingCheck(null, JSONObject())
        val current = postReaMicro(
            baseUrl,
            token,
            JSONObject(),
            request.optString("blessingEndpoint").ifBlank { "rest/community/get-taoist-blessing" },
        )
        businessError(current)?.let { return BlessingCheck("查询运签失败：$it", JSONObject()) }
        val data = current.optJSONObject("data") ?: current
        // blessing 为 null 表示没有签；JSONObject.NULL 与缺失两种形态都要识别成"无签"。
        val blessing = data.opt("blessing")
        if (blessing is JSONObject) return BlessingCheck(null, blessingDetail(blessing, "沿用已有"))
        val pray = postReaMicro(
            baseUrl,
            token,
            JSONObject().put("blessingType", type),
            request.optString("prayEndpoint").ifBlank { "rest/community/pray-taoist-blessing" },
        )
        businessError(pray)?.let { return BlessingCheck("祈禳${blessingLabel(type)}失败：$it", JSONObject()) }
        val prayed = (pray.optJSONObject("data") ?: pray).optJSONObject("blessing")
        return BlessingCheck(null, if (prayed != null) blessingDetail(prayed, "本次祈禳") else JSONObject())
    }

    /**
     * 当前账户上生效的运签详情。
     *
     * 与"这次有没有祈禳"无关：祈禳只是补签，签本身是账户状态。任务设成不祈禳时也不会去发祈禳请求，
     * 但这里仍要把它读出来——否则详情里看不到行商吃到的加成（用户报的就是这个）。
     */
    private fun currentBlessingDetail(
        baseUrl: String,
        token: String,
        request: JSONObject,
        prayed: BlessingCheck,
    ): JSONObject {
        if (prayed.detail.length() > 0) return prayed.detail
        val current = runCatching {
            postReaMicro(
                baseUrl,
                token,
                JSONObject(),
                request.optString("blessingEndpoint").ifBlank { "rest/community/get-taoist-blessing" },
            )
        }.getOrNull() ?: return JSONObject()
        if (businessError(current) != null) return JSONObject()
        val data = current.optJSONObject("data") ?: current
        val blessing = data.opt("blessing") as? JSONObject ?: return JSONObject()
        return blessingDetail(blessing, "当前生效")
    }

    /** 运签详情：签种、签文名、效果描述（游戏原文，例如"下一次每日轶闻：绿色及以上概率提升 2 个百分点"）。 */
    private fun blessingDetail(blessing: JSONObject, source: String): JSONObject {
        val type = blessing.optString("blessingType")
        return JSONObject()
            .put("运签", "$source · ${blessingLabel(type)} ${blessing.optString("name")}".trim())
            .put("效果", blessing.optString("description"))
    }

    /** 运签检查结果：failure 非空表示这次没拿到签；detail 用于任务记录详情。 */
    private data class BlessingCheck(val failure: String?, val detail: JSONObject)

    /** 祈禳成功时给任务消息加一句，方便回查这次任务用的什么签。 */
    private fun blessingNote(blessingType: String): String =
        blessingType.trim().takeIf { it.isNotBlank() }?.let { " · 已祈禳${blessingLabel(it)}" }.orEmpty()

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
    private fun runPawn(task: JSONObject, request: JSONObject, credential: JSONObject): Outcome {
        val baseUrl = credential.optString("baseUrl").ifBlank { return Outcome("failed", "阅微服务器地址为空") }
        val token = credential.optString("token").ifBlank { return Outcome("paused", "阅微登录凭据无效") }
        val info = postReaMicro(
            baseUrl,
            token,
            JSONObject(),
            request.optString("pawnCountEndpoint").ifBlank { "rest/community/get-pawn-count" },
        )
        businessError(info)?.let { return Outcome("failed", "获取期物典当信息失败：$it") }
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
        businessError(materials)?.let { return Outcome("failed", "读取背包失败：$it") }
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
            val error = businessError(pawn)
            if (error != null) {
                failure = error
                return@repeat
            }
            coin += (pawn.optJSONObject("data") ?: pawn).optLong("coin", 0L)
            successCount++
        }
        val today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()
        val state = JSONObject()
            .put("lastPawnDate", today)
            .put("pawnUsedToday", usedToday + successCount)
            .put("pawnLastCoin", coin)
        if (successCount == 0) {
            return Outcome("failed", "典当失败：${failure ?: "未知原因"}", state)
        }
        val tail = if (failure != null) "（第 ${successCount + 1} 次中断：$failure）" else ""
        val detail = JSONObject()
            .put("期物", propName)
            .put("典当数量", "$successCount 件")
            .put("获得铜钱", "$coin 文")
            .put("今日次数", "${usedToday + successCount}/$maxPerDay")
        return Outcome("success", "典当「$propName」$successCount 件，获得铜钱 $coin 文$tail", state, detail = detail)
    }

    private fun postReaMicro(baseUrl: String, token: String, body: JSONObject, endpoint: String): JSONObject {
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

    /** 从行商响应解析活跃行商。字段名与宿主 TravelingMerchantTrip 对齐；endTime 为秒级 epoch。 */
    internal fun parseMerchantTrip(body: JSONObject): MerchantTrip {
        val data = body.optJSONObject("data") ?: body
        val trip = data.optJSONObject("activeTrip") ?: return MerchantTrip(hasTrip = false)
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

    internal enum class MerchantPhase { IN_TRANSIT, ARRIVED, SETTLED, NOTIFIED }

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
    private val MERCHANT_TIME_FORMAT = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
}
