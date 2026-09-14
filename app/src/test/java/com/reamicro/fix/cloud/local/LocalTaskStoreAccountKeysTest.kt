package com.reamicro.fix.cloud.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * prefs 账号键 → 账号 ID 的还原。
 *
 * 回归背景：`LocalTaskStore` 的 `earliestNextRunAt` / `hasEnabledTasks` /
 * `accountsWithEnabledTasks` 曾直接拿 prefs 的**原始 key**（`account_42`）当 accountId 用，
 * 而 `list()` 会再加一次前缀去找 `account_account_42`，于是永远查不到账号：
 * `accountsWithEnabledTasks()` 恒为空集 ⇒ `LocalTaskRunner.runDue()` 一个任务都不跑，
 * 前台心跳、闹钟唤醒、镜像下发三条路径全部空转；`earliestNextRunAt()` 恒为 0 ⇒
 * 本地任务的时刻从不参与闹钟排程。用户侧的表现就是「本地任务全部失效」。
 *
 * 这里锁住这层映射的往返关系，避免再次退化。
 */
class LocalTaskStoreAccountKeysTest {

    private fun storageKey(accountId: String) = "${LocalTaskStore.KEY_ACCOUNT_PREFIX}$accountId"

    @Test
    fun `prefs 原始 key 能还原出账号 ID`() {
        // 这条断言在修复前会得到空列表——正是那个 bug 的最小复现。
        assertEquals(listOf("42"), accountIdsFromStorageKeys(listOf(storageKey("42"))))
    }

    @Test
    fun `与账号键拼法严格互逆`() {
        val accountId = "b7c1e0f4-9d2a-4c33-8f10-1a2b3c4d5e6f"
        assertEquals(accountId, accountIdFromStorageKey(storageKey(accountId)))
    }

    @Test
    fun `非账号键一律忽略`() {
        // SharedPreferences 里混着别的键时不能把它们当成账号，否则会去读一堆无意义的前缀键。
        val keys = listOf("records", "account_", "accounts", "someOtherSetting", storageKey("7"))
        assertEquals(listOf("7"), accountIdsFromStorageKeys(keys))
    }

    @Test
    fun `空前缀键不算账号`() {
        // "account_" 剥完是空串，当成账号会写出 account_ 这种自相矛盾的键。
        assertNull(accountIdFromStorageKey(LocalTaskStore.KEY_ACCOUNT_PREFIX))
        assertNull(accountIdFromStorageKey(""))
        assertNull(accountIdFromStorageKey("account"))
    }

    @Test
    fun `多账号全部还原且保持原有顺序`() {
        val keys = listOf(storageKey("a"), "records", storageKey("b"), storageKey("c"))
        assertEquals(listOf("a", "b", "c"), accountIdsFromStorageKeys(keys))
    }

    @Test
    fun `还原出的账号 ID 不含前缀`() {
        // 双前缀是那个 bug 的直接特征：还原结果里再出现 "account_" 说明又拼了一次。
        val restored = accountIdsFromStorageKeys(listOf(storageKey("42"))).single()
        assertTrue("账号 ID 不应再带前缀，实际：$restored", !restored.startsWith(LocalTaskStore.KEY_ACCOUNT_PREFIX))
    }
}
