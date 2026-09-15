package com.reamicro.fix.cloud.local

import com.reamicro.fix.cloud.local.CloudTaskLocalRunner.BlessingCheck
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner.MerchantAction
import com.reamicro.fix.cloud.local.CloudTaskLocalRunner.MerchantPhase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地自动任务的状态判定。
 *
 * 两个 bug 都是**静默**的——任务照跑、记录照写，只是该做的事没做，用户只能从游戏里反推：
 * 1. 运签沿用逻辑把"已祈禳"写进战报却根本没祈禳，还让别的任务祈禳好的签一直挡着配置的签种；
 * 2. 行商用「已通知趟次」同时挡掉了自动结算，先通知过、之后才打开自动完成的趟次永不结算。
 * 这里把这两处的判定锁死。
 */
class LocalTaskDecisionTest {

    // ---- 运签文案 ----

    private fun check(
        prayed: Boolean = false,
        activeType: String = "",
        replacedType: String = "",
        failure: String? = null,
    ) = BlessingCheck(
        failure = failure,
        detail = if (activeType.isBlank()) JSONObject() else JSONObject().put("运签", activeType),
        prayed = prayed,
        activeType = activeType,
        replacedType = replacedType,
    )

    @Test
    fun `不祈禳配置下不写任何运签说明`() {
        assertEquals("", CloudTaskLocalRunner.blessingNote(check(prayed = false, activeType = "WEALTH"), ""))
    }

    @Test
    fun `真的祈禳了才说已祈禳`() {
        val note = CloudTaskLocalRunner.blessingNote(check(prayed = true, activeType = "LUCK"), "LUCK")
        assertEquals(" · 已祈禳求运签", note)
    }

    @Test
    fun `顶掉别的签要说明是哪一支`() {
        // 每日轶闻配求运、账号上却挂着自动行商祈禳的求财签：这一样要如实报出来。
        val note = CloudTaskLocalRunner.blessingNote(
            check(prayed = true, activeType = "LUCK", replacedType = "WEALTH"),
            "LUCK",
        )
        assertEquals(" · 已祈禳求运签（顶掉原有求财签）", note)
    }

    @Test
    fun `沿用已有的签不能说成已祈禳`() {
        // 用户报的正是这句谎话：配置求运、实际生效求财，战报却写「已祈禳求运签」。
        val note = CloudTaskLocalRunner.blessingNote(check(prayed = false, activeType = "WEALTH"), "LUCK")
        assertEquals(" · 沿用已有求财签", note)
        assertFalse(note.contains("已祈禳"))
    }

    @Test
    fun `祈禳失败要说原因`() {
        val note = CloudTaskLocalRunner.blessingNote(check(failure = "祈禳求运签失败：铜钱不足"), "LUCK")
        assertEquals("（运签：祈禳求运签失败：铜钱不足）", note)
    }

    // ---- 行商：通知 / 结算 / 开新行商互相独立 ----

    private fun action(
        hasTrip: Boolean = true,
        phase: MerchantPhase = MerchantPhase.SETTLED,
        tripId: Long = 100L,
        lastNotifiedTripId: Long = 0L,
        settledTripId: Long = 0L,
        restartedAfterTripId: Long = 0L,
        autoComplete: Boolean = true,
        startConfigComplete: Boolean = true,
    ): MerchantAction = CloudTaskLocalRunner.merchantAction(
        hasTrip = hasTrip,
        phase = phase,
        tripId = tripId,
        lastNotifiedTripId = lastNotifiedTripId,
        settledTripId = settledTripId,
        restartedAfterTripId = restartedAfterTripId,
        autoComplete = autoComplete,
        startConfigComplete = startConfigComplete,
    )

    @Test
    fun `已通知过但没结算过的趟次仍要自动结算`() {
        // 这就是"自动行商不领奖"：先通知过（自动完成当时是关的），之后打开自动完成，
        // 旧逻辑拿 merchantLastNotifiedTripId 把结算一起挡掉了。
        val result = action(lastNotifiedTripId = 100L, settledTripId = 0L)
        assertTrue("已通知不该挡住自动结算", result.settle)
        assertFalse("不该重复通知", result.notify)
    }

    @Test
    fun `已结算过的趟次不重复结算`() {
        val result = action(lastNotifiedTripId = 100L, settledTripId = 100L)
        assertFalse(result.settle)
    }

    @Test
    fun `关掉自动完成就不结算也不开新行商`() {
        val arrived = action(autoComplete = false)
        assertFalse(arrived.settle)
        assertFalse(arrived.start)
        val noTrip = action(hasTrip = false, autoComplete = false)
        assertFalse(noTrip.start)
    }

    @Test
    fun `没有在途行商时每次都尝试补开一趟`() {
        // 开失败了下次还得试，否则这条链会彻底断掉。
        val result = action(hasTrip = false, phase = MerchantPhase.SETTLED, tripId = 0L)
        assertTrue(result.start)
        assertFalse(result.notify)
    }

    @Test
    fun `已结算的行商只为它开一次新行商`() {
        val first = action(phase = MerchantPhase.SETTLED, tripId = 100L, lastNotifiedTripId = 100L, settledTripId = 100L, restartedAfterTripId = 0L)
        assertTrue("这趟还没为它开成过，要再试", first.start)
        val retried = action(phase = MerchantPhase.SETTLED, tripId = 100L, lastNotifiedTripId = 100L, settledTripId = 100L, restartedAfterTripId = 100L)
        assertFalse("已经为它开成功过，不该反复开", retried.start)
    }

    @Test
    fun `在途行商只等它抵达，不多开一趟`() {
        val result = action(phase = MerchantPhase.IN_TRANSIT)
        assertFalse(result.start)
        assertFalse(result.settle)
        assertFalse(result.notify)
    }

    @Test
    fun `行商参数不全时不硬开新行商`() {
        val result = action(hasTrip = false, phase = MerchantPhase.SETTLED, tripId = 0L, startConfigComplete = false)
        assertFalse(result.start)
    }

    @Test
    fun `SETTLED 但未领奖时必须先领取不能直接续开`() {
        val result = action(phase = MerchantPhase.SETTLED)
        assertTrue(result.settle)
        assertFalse(result.start)
    }

    @Test
    fun `预计抵达但服务端未结算时只复查`() {
        val result = action(phase = MerchantPhase.ARRIVED)
        assertFalse(result.settle)
        assertFalse(result.start)
        assertFalse(result.notify)
    }
}
