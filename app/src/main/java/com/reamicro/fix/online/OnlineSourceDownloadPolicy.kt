package com.reamicro.fix.online

import android.content.Context
import com.reamicro.fix.settings.ModuleSettings
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

internal data class OnlineSourceDownloadPolicy(
    val requestsPerSecond: Int? = null,
    val dailyChapterLimit: Int? = null,
    val preferOnDemandLoading: Boolean = false,
    val paragraphCommentsEnabled: Boolean = false,
)

internal class OnlineSourceDailyLimitException(message: String) : IllegalStateException(message)

internal object OnlineSourceDownloadPolicyStore {
    private const val KEY_RATE_PREFIX = "online_download_rate_"
    private const val KEY_DAILY_LIMIT_PREFIX = "online_download_daily_limit_"
    private const val KEY_DAILY_DATE_PREFIX = "online_download_daily_date_"
    private const val KEY_DAILY_USED_PREFIX = "online_download_daily_used_"
    private const val KEY_ON_DEMAND_PREFIX = "online_download_on_demand_"
    private const val KEY_PARAGRAPH_COMMENTS_PREFIX = "online_paragraph_comments_"
    private const val MAX_REQUESTS_PER_SECOND = 10_000
    private const val MAX_DAILY_CHAPTER_LIMIT = 10_000_000
    private val quotaLocks = ConcurrentHashMap<String, Any>()

    fun parse(requestsPerSecond: String, dailyChapterLimit: String): OnlineSourceDownloadPolicy =
        OnlineSourceDownloadPolicy(
            requestsPerSecond = parseOptionalPositive(
                raw = requestsPerSecond,
                label = "每秒请求次数",
                maximum = MAX_REQUESTS_PER_SECOND,
            ),
            dailyChapterLimit = parseOptionalPositive(
                raw = dailyChapterLimit,
                label = "每日章节限额",
                maximum = MAX_DAILY_CHAPTER_LIMIT,
            ),
        )

    fun attach(context: Context?, source: OnlineSourceEntry): OnlineSourceEntry {
        val policy = load(context, source.id)
        return source.copy(
            configuredRequestsPerSecond = policy.requestsPerSecond,
            dailyChapterLimit = policy.dailyChapterLimit,
            preferOnDemandLoading = policy.preferOnDemandLoading,
            paragraphCommentsEnabled = policy.paragraphCommentsEnabled,
        )
    }

    fun load(context: Context?, sourceId: String): OnlineSourceDownloadPolicy {
        val preferences = prefs(context) ?: return OnlineSourceDownloadPolicy()
        return OnlineSourceDownloadPolicy(
            requestsPerSecond = preferences.getInt(KEY_RATE_PREFIX + sourceId, 0).takeIf { it > 0 },
            dailyChapterLimit = preferences.getInt(KEY_DAILY_LIMIT_PREFIX + sourceId, 0).takeIf { it > 0 },
            preferOnDemandLoading = preferences.getBoolean(KEY_ON_DEMAND_PREFIX + sourceId, false),
            paragraphCommentsEnabled = preferences.getBoolean(KEY_PARAGRAPH_COMMENTS_PREFIX + sourceId, false),
        )
    }

    fun save(
        context: Context?,
        sourceId: String,
        requestsPerSecond: String,
        dailyChapterLimit: String,
        preferOnDemandLoading: Boolean = false,
        paragraphCommentsEnabled: Boolean = false,
    ): OnlineSourceDownloadPolicy {
        val appContext = context?.applicationContext ?: error("缺少 Context")
        val policy = parse(requestsPerSecond, dailyChapterLimit).copy(
            preferOnDemandLoading = preferOnDemandLoading,
            paragraphCommentsEnabled = paragraphCommentsEnabled,
        )
        prefs(appContext)?.edit()?.apply {
            policy.requestsPerSecond?.let { putInt(KEY_RATE_PREFIX + sourceId, it) }
                ?: remove(KEY_RATE_PREFIX + sourceId)
            policy.dailyChapterLimit?.let { putInt(KEY_DAILY_LIMIT_PREFIX + sourceId, it) }
                ?: remove(KEY_DAILY_LIMIT_PREFIX + sourceId)
            putBoolean(KEY_ON_DEMAND_PREFIX + sourceId, policy.preferOnDemandLoading)
            putBoolean(KEY_PARAGRAPH_COMMENTS_PREFIX + sourceId, policy.paragraphCommentsEnabled)
        }?.apply()
        return policy
    }

    fun clear(context: Context?, sourceId: String) {
        prefs(context)?.edit()
            ?.remove(KEY_RATE_PREFIX + sourceId)
            ?.remove(KEY_DAILY_LIMIT_PREFIX + sourceId)
            ?.remove(KEY_DAILY_DATE_PREFIX + sourceId)
            ?.remove(KEY_DAILY_USED_PREFIX + sourceId)
            ?.remove(KEY_ON_DEMAND_PREFIX + sourceId)
            ?.remove(KEY_PARAGRAPH_COMMENTS_PREFIX + sourceId)
            ?.apply()
        quotaLocks.remove(sourceId)
    }

    fun effectiveConcurrentRate(source: OnlineSourceEntry): String =
        source.configuredRequestsPerSecond?.let { "$it/1000" } ?: source.concurrentRate

    fun usedToday(context: Context?, sourceId: String): Int {
        val preferences = prefs(context) ?: return 0
        if (preferences.getString(KEY_DAILY_DATE_PREFIX + sourceId, "") != today()) return 0
        return preferences.getInt(KEY_DAILY_USED_PREFIX + sourceId, 0).coerceAtLeast(0)
    }

    fun remainingToday(context: Context?, source: OnlineSourceEntry): Int? =
        source.dailyChapterLimit?.let { limit ->
            (limit - usedToday(context, source.id)).coerceAtLeast(0)
        }

    fun limitReachedMessage(source: OnlineSourceEntry): String {
        val limit = source.dailyChapterLimit ?: return "${source.name} 当前没有可用章节下载额度"
        return "${source.name} 今日已达到章节下载限额 $limit 章，明日自动恢复"
    }

    fun <T> withChapterDownload(context: Context?, source: OnlineSourceEntry, block: () -> T): T {
        val limit = source.dailyChapterLimit ?: return block()
        val appContext = context?.applicationContext ?: error("缺少 Context，无法校验每日章节限额")
        val lock = quotaLocks.computeIfAbsent(source.id) { Any() }
        synchronized(lock) {
            val preferences = prefs(appContext) ?: error("缺少设置存储，无法校验每日章节限额")
            val today = today()
            val storedDate = preferences.getString(KEY_DAILY_DATE_PREFIX + source.id, "")
            val used = if (storedDate == today) {
                preferences.getInt(KEY_DAILY_USED_PREFIX + source.id, 0).coerceAtLeast(0)
            } else {
                0
            }
            if (used >= limit) {
                throw OnlineSourceDailyLimitException(limitReachedMessage(source))
            }
            val result = block()
            preferences.edit()
                .putString(KEY_DAILY_DATE_PREFIX + source.id, today)
                .putInt(KEY_DAILY_USED_PREFIX + source.id, used + 1)
                .apply()
            return result
        }
    }

    private fun parseOptionalPositive(raw: String, label: String, maximum: Int): Int? {
        val text = raw.trim()
        if (text.isBlank() || text == "0") return null
        val value = text.toIntOrNull() ?: error("$label 必须是整数")
        require(value in 1..maximum) { "$label 必须在 1-$maximum 之间" }
        return value
    }

    private fun prefs(context: Context?) =
        context?.applicationContext?.getSharedPreferences(ModuleSettings.PREFS_NAME, Context.MODE_PRIVATE)

    private fun today(): String = LocalDate.now().toString()
}
