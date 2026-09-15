package com.reamicro.fix.cloud.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTaskMirrorMergeTest {
    private fun config(blessing: String, revision: Long, nextRunAt: Long = 1000L) = JSONObject()
        .put("enabled", true).put("blessingType", blessing).put("configUpdatedAt", revision)
        .put("nextRunAt", nextRunAt).put("claimDueAt", 9000L).put("claimLoreId", 99L)

    @Test
    fun `宿主旧镜像不能覆盖模块刚保存的不祈禳`() {
        val merged = LocalTaskStore.mergeMirroredTask(config("", 200), config("WEALTH", 100), 500L)
        assertEquals("", merged.getString("blessingType"))
        assertEquals(200L, merged.getLong("configUpdatedAt"))
        assertEquals(1000L, merged.getLong("nextRunAt"))
    }

    @Test
    fun `相同镜像不能冲掉模块排好的领奖时间`() {
        val merged = LocalTaskStore.mergeMirroredTask(config("LUCK", 200, 9000), config("LUCK", 200, 500), 500L)
        assertEquals(9000L, merged.getLong("nextRunAt"))
    }

    @Test
    fun `更新的签种生效但保留领取进度`() {
        val merged = LocalTaskStore.mergeMirroredTask(config("LUCK", 100), config("", 200), 500L)
        assertEquals("", merged.getString("blessingType"))
        assertEquals(500L, merged.getLong("nextRunAt"))
        assertEquals(9000L, merged.getLong("claimDueAt"))
        assertEquals(99L, merged.getLong("claimLoreId"))
    }

    @Test
    fun `没有版本的旧镜像不回滚现有选择`() {
        val merged = LocalTaskStore.mergeMirroredTask(config("", 0), config("LUCK", 0), 500L)
        assertEquals("", merged.getString("blessingType"))
    }

    @Test
    fun `更新的停用配置清除排程但不清除领取进度`() {
        val incoming = config("", 200).put("enabled", false)
        val merged = LocalTaskStore.mergeMirroredTask(config("LUCK", 100), incoming, 500L)
        assertFalse(merged.getBoolean("enabled"))
        assertEquals(0L, merged.getLong("nextRunAt"))
        assertEquals(99L, merged.getLong("claimLoreId"))
    }

    @Test
    fun `两侧缺失可选字段时不崩溃且不重置领奖时间`() {
        val current = JSONObject().put("enabled", true).put("configUpdatedAt", 100L)
            .put("nextRunAt", 9000L).put("claimDueAt", 9000L)
        val incoming = JSONObject().put("enabled", true).put("configUpdatedAt", 200L)
        val merged = LocalTaskStore.mergeMirroredTask(current, incoming, 500L)
        assertEquals(200L, merged.getLong("configUpdatedAt"))
        assertEquals(9000L, merged.getLong("nextRunAt"))
        assertEquals(9000L, merged.getLong("claimDueAt"))
        assertFalse(merged.has("blessingType"))
    }

    @Test
    fun `新镜像缺失旧有的可选字段时安全移除`() {
        val incoming = JSONObject().put("enabled", true).put("configUpdatedAt", 200L)
        val merged = LocalTaskStore.mergeMirroredTask(config("LUCK", 100), incoming, 500L)
        assertFalse(merged.has("blessingType"))
        assertEquals(500L, merged.getLong("nextRunAt"))
        assertEquals(99L, merged.getLong("claimLoreId"))
    }

    @Test
    fun `缺失和显式空值的可选字段都能安全比较`() {
        val current = config("", 100).put("timeOfDay", JSONObject.NULL)
        val incoming = config("", 200).put("merchantCityCode", JSONObject.NULL)
        val merged = LocalTaskStore.mergeMirroredTask(current, incoming, 500L)
        assertFalse(merged.has("timeOfDay"))
        assertEquals(JSONObject.NULL, merged.get("merchantCityCode"))
        assertEquals("", merged.getString("blessingType"))
        assertEquals(500L, merged.getLong("nextRunAt"))
    }

    @Test
    fun `全空配置的新任务默认停用`() {
        val merged = LocalTaskStore.mergeMirroredTask(null, JSONObject(), 500L)
        assertFalse(merged.optBoolean("enabled"))
        assertEquals(0L, merged.getLong("nextRunAt"))
        assertEquals(0L, merged.getLong("configUpdatedAt"))
    }

    @Test
    fun `首次镜像缺少可选配置也能安排启用任务`() {
        val incoming = JSONObject().put("enabled", true).put("configUpdatedAt", 200L)
        val merged = LocalTaskStore.mergeMirroredTask(null, incoming, 500L)
        assertEquals(500L, merged.getLong("nextRunAt"))
        assertFalse(merged.has("blessingType"))
    }

    @Test
    fun `更新后立即复查旧行商且清掉不可信的成功标记`() {
        val legacy = JSONObject().put("nextRunAt", 9000L).put("merchantSettledTripId", 42L)
            .put("merchantRestartedAfterTripId", 42L).put("merchantLastNotifiedTripId", 42L)
        val recovered = recoveredAutomationState("traveling_merchant", legacy, 500L)!!
        assertEquals(500L, recovered.getLong("nextRunAt"))
        assertEquals(0L, recovered.getLong("merchantSettledTripId"))
        assertEquals(0L, recovered.getLong("merchantRestartedAfterTripId"))
        assertEquals(42L, recovered.getLong("merchantLastNotifiedTripId"))
        assertNull(recoveredAutomationState("traveling_merchant", recovered, 600L))
    }

    @Test
    fun `恢复旧轶闻排程但不重置签到日期和已启用签种`() {
        val legacy = config("", 100, 9000).put("lastCheckinDate", "2026-09-15")
        val recovered = recoveredAutomationState("yeshe_checkin", legacy, 500L)!!
        assertEquals(500L, recovered.getLong("nextRunAt"))
        assertEquals("2026-09-15", recovered.getString("lastCheckinDate"))
        assertEquals("", recovered.getString("blessingType"))
        assertNull(recoveredAutomationState("cloud_auto_read", legacy, 500L))
    }
}
