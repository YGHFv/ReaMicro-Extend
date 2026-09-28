package com.reamicro.fix.ui

import com.reamicro.fix.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root 卡片的状态必须同源。
 *
 * 回归背景：实机（无 root 的 HyperOS）上出现过「看门狗运行中」配「未启用看门狗」这种互相矛盾的
 * 展示——原因是界面拿三个布尔值各自推导标题和说明，两处口径不一致就会打架。现在状态只由
 * [RootWakeController.Status.stateResource] 推导一次（返回语言无关的资源 ID），这里把整个
 * 真值表锁在资源 ID 上，不再绑定任何一种语言的具体文案。
 */
class RootWakeStatusTest {

    private fun status(available: Boolean, installed: Boolean, running: Boolean) =
        RootWakeController.Status(
            rootAvailable = available,
            watchdogInstalled = installed,
            watchdogRunning = running,
            message = "",
        )

    @Test
    fun `没有 root 时一律显示未授权`() {
        // 最要紧的一条：su 不可用时"看门狗在不在跑"根本无从谈起，任何组合都不能显示成运行中。
        listOf(
            status(false, false, false),
            status(false, true, false),
            status(false, false, true),
            status(false, true, true),
        ).forEach { s ->
            assertEquals(R.string.root_unauthorized, s.stateResource())
        }
    }

    @Test
    fun `有 root 时按运行与安装状态递进`() {
        assertEquals(R.string.root_authorized_inactive, status(true, false, false).stateResource())
        assertEquals(R.string.root_installed, status(true, true, false).stateResource())
        assertEquals(R.string.root_running, status(true, true, true).stateResource())
    }

    @Test
    fun `脚本被删但循环还活着时仍报运行中`() {
        // 这条组合只在一种情况下出现：停用时 `rm` 成功而 `pkill` 失败（或两者之间被打断）。
        // 此时循环确实还在跑，说"未启用"会把用户引向错误的判断；如实报"运行中"才有用——
        // 界面上的说明会同时提示脚本已不在，用户知道重启后不会自动生效。
        assertEquals(R.string.root_running, status(true, false, true).stateResource())
    }

    @Test
    fun `不可用状态与运行中状态是不同的资源`() {
        // 未授权绝不能和"运行中"取到同一个资源 ID（原缺陷正是两者被混为一谈）。
        assertTrue(status(false, true, true).stateResource() != status(true, true, true).stateResource())
    }

    /**
     * 看门狗脚本按**任务时刻**唤醒，而不是固定周期无条件广播。
     *
     * 用户明确要求过："不要每 15 分钟就唤起一次，根据任务的定时或者结束时间静默自启"。
     * 脚本是纯字符串，正好可以在单测里锁住这个性质，避免以后又被改回定期广播。
     */
    @Test
    fun `看门狗脚本按任务时刻唤醒而不是固定周期`() {
        val script = RootWakeController.watchdogScript("/data/user/0/com.reamicro.fix/files/next-wake-at")
        // 必须读模块写下的下次唤醒时刻。
        assertTrue(script.contains("/data/user/0/com.reamicro.fix/files/next-wake-at"))
        assertTrue("应读取下次唤醒时刻文件", script.contains("cat \"\$WAKE_FILE\""))
        // 必须真的会睡到那个时刻（而不是每次都直接广播）。
        assertTrue("应按时差 sleep", script.contains("delay=\$((next - now))"))
        // 仍然要带这两个标志：root 身份绕过冻结、能拉起 stopped 应用。
        assertTrue(script.contains("--include-stopped-packages"))
        assertTrue(script.contains("--user 0"))
        assertTrue(script.contains("com.reamicro.fix.cloud.api.CloudTaskHeartbeatReceiver"))
    }

    @Test
    fun `读不到时刻时仍会定期兜底唤醒`() {
        val script = RootWakeController.watchdogScript("/tmp/next-wake-at")
        // 全新安装还没排过闹钟时不能失联：next 为 0 时要先睡一个粒度再广播。
        assertTrue(script.contains("MAX_SLEEP"))
        assertTrue(script.contains("next=0"))
        assertTrue(script.contains("''|*[!0-9]*) next=0"))
    }

    @Test
    fun `广播后有冷却避免同一时刻反复广播`() {
        val script = RootWakeController.watchdogScript("/tmp/next-wake-at")
        assertTrue(script.contains("COOLDOWN"))
        assertTrue(script.contains("sleep \"\$COOLDOWN\""))
    }
}
