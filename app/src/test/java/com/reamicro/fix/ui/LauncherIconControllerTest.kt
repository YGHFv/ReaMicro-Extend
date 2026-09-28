package com.reamicro.fix.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑测试：用假的组件表和偏好存储驱动 [LauncherIconController]，
 * 不依赖 Android。覆盖切换、隐藏、失败回滚、pending 恢复、多入口、读/写失败等分支。
 */
class LauncherIconControllerTest {

    private val aliasKeys = listOf("default", "mono", "gold", "night")

    /** 假组件表：可注入读/写异常，写入按传入顺序生效以验证“先启用后禁用”。 */
    private class FakeComponents(default: String) : LauncherIconComponents {
        val state = linkedMapOf<String, LauncherComponentState>()
        val applied = mutableListOf<List<LauncherIconChange>>()
        var readError: (() -> Unit)? = null
        var applyError: (() -> Unit)? = null

        init {
            listOf("default", "mono", "gold", "night").forEach {
                state[it] = if (it == default) LauncherComponentState.DEFAULT else LauncherComponentState.DEFAULT
            }
        }

        override fun read(key: String): LauncherComponentState {
            readError?.invoke()
            return state.getValue(key)
        }

        override fun apply(changes: List<LauncherIconChange>) {
            applyError?.invoke()
            applied += changes
            changes.forEach { change ->
                state[change.key] = if (change.enabled) LauncherComponentState.ENABLED else LauncherComponentState.DISABLED
            }
        }

        fun enabledKeys(): Set<String> = state.filterValues { it == LauncherComponentState.ENABLED }.keys
    }

    /** 假偏好：内存记录，可注入写失败并模拟 commit(false) 仍改内存的行为。 */
    private class FakePreferences(var record: LauncherIconRecord) : LauncherIconPreferences {
        val writes = mutableListOf<LauncherIconRecord>()
        var failWrites = 0
        var mutateOnFailure = false

        override fun read(): LauncherIconRecord = record

        override fun write(record: LauncherIconRecord): Boolean {
            writes += record
            if (failWrites > 0) {
                failWrites--
                if (mutateOnFailure) this.record = record
                return false
            }
            this.record = record
            return true
        }
    }

    private fun aliases() = aliasKeys.map { LauncherIconAlias(it, defaultEnabled = it == "default") }

    private fun controller(components: FakeComponents, preferences: FakePreferences) =
        LauncherIconController(aliases(), components, preferences)

    private fun freshInstall() = FakePreferences(LauncherIconRecord(LauncherIconChoice("default", hidden = false), configured = false))

    @Test
    fun `selecting a style enables only that alias and confirms preferences`() {
        val components = FakeComponents("default")
        val preferences = freshInstall()
        val result = controller(components, preferences).selectStyle("gold")

        assertTrue(result.success)
        assertNull(result.view.issue)
        assertEquals(LauncherIconCondition.VISIBLE, result.view.condition)
        assertEquals("gold", result.view.choice.key)
        assertEquals(setOf("gold"), components.enabledKeys())
        assertEquals(LauncherIconChoice("gold", hidden = false), preferences.record.confirmed)
        assertNull(preferences.record.pending)
    }

    @Test
    fun `target is enabled before the previous entry is disabled`() {
        val components = FakeComponents("default")
        // 全新安装：默认别名按 manifest 默认视为启用。
        val preferences = freshInstall()
        controller(components, preferences).selectStyle("mono")

        val batch = components.applied.single()
        val enableIndex = batch.indexOfFirst { it.key == "mono" && it.enabled }
        val disableIndex = batch.indexOfFirst { it.key == "default" && !it.enabled }
        assertTrue("target enable must precede old disable", enableIndex in 0 until disableIndex)
    }

    @Test
    fun `hiding disables every alias and shows hidden condition`() {
        val components = FakeComponents("default")
        val preferences = freshInstall()
        val controller = controller(components, preferences)
        controller.selectStyle("gold")

        val result = controller.setHidden(true)

        assertTrue(result.success)
        assertEquals(LauncherIconCondition.HIDDEN, result.view.condition)
        assertTrue(components.enabledKeys().isEmpty())
        // 隐藏时保留恢复目标配色。
        assertEquals("gold", preferences.record.confirmed.key)
        assertTrue(preferences.record.confirmed.hidden)
    }

    @Test
    fun `selecting while hidden updates preference without lighting an alias`() {
        val components = FakeComponents("default")
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("gold", hidden = true)))
        val controller = controller(components, preferences)

        val result = controller.selectStyle("night")

        assertTrue(result.success)
        assertEquals(LauncherIconCondition.HIDDEN, result.view.condition)
        assertTrue(components.enabledKeys().isEmpty())
        assertEquals("night", preferences.record.confirmed.key)
        assertTrue(preferences.record.confirmed.hidden)
    }

    @Test
    fun `unhiding restores the saved style`() {
        val components = FakeComponents("default")
        components.state.keys.forEach { components.state[it] = LauncherComponentState.DISABLED }
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("gold", hidden = true)))

        val result = controller(components, preferences).setHidden(false)

        assertTrue(result.success)
        assertEquals(setOf("gold"), components.enabledKeys())
        assertFalse(preferences.record.confirmed.hidden)
        assertEquals("gold", preferences.record.confirmed.key)
    }

    @Test
    fun `apply failure rolls back to the previous style and reports failure`() {
        val components = FakeComponents("default")
        val preferences = freshInstall()
        val controller = controller(components, preferences)
        controller.selectStyle("gold")
        components.applyError = { throw IllegalStateException("blocked") }

        val result = controller.selectStyle("mono")

        assertFalse(result.success)
        assertEquals(LauncherIconIssue.APPLY_FAILED, result.view.issue)
        // 回滚后仍是上一套 gold，且组件实际状态没有多入口。
        assertEquals("gold", result.view.choice.key)
        assertEquals(setOf("gold"), components.enabledKeys())
        assertEquals("gold", preferences.record.confirmed.key)
        assertNull(preferences.record.pending)
    }

    @Test
    fun `interrupted apply is finished by restore via pending journal`() {
        val components = FakeComponents("default")
        val preferences = freshInstall()
        controller(components, preferences).selectStyle("gold")

        // 模拟：写完 pending 后进程被杀，组件还停在旧状态。
        preferences.record = LauncherIconRecord(
            confirmed = LauncherIconChoice("gold", hidden = false),
            pending = LauncherIconChoice("night", hidden = false),
        )
        components.state.keys.forEach { components.state[it] = LauncherComponentState.DISABLED }
        components.state["gold"] = LauncherComponentState.ENABLED

        val result = controller(components, preferences).restore()

        assertTrue(result.success)
        assertEquals("night", result.view.choice.key)
        assertEquals(setOf("night"), components.enabledKeys())
        assertEquals("night", preferences.record.confirmed.key)
        assertNull(preferences.record.pending)
    }

    @Test
    fun `restore repairs a reset to manifest defaults using saved preference`() {
        // 组件被重置为全 DEFAULT（例如异常清空），但用户偏好是 night。
        val components = FakeComponents("default")
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("night", hidden = false)))

        val result = controller(components, preferences).restore()

        assertTrue(result.success)
        assertEquals(setOf("night"), components.enabledKeys())
        assertEquals("night", result.view.choice.key)
    }

    @Test
    fun `restore keeps a user-chosen default entry without extra writes`() {
        val components = FakeComponents("default")
        // 已配置成默认款且组件正好只有默认入口：应保持，不误判为需要修复。
        components.state["default"] = LauncherComponentState.ENABLED
        listOf("mono", "gold", "night").forEach { components.state[it] = LauncherComponentState.DISABLED }
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("default", hidden = false)))

        val result = controller(components, preferences).restore()

        assertTrue(result.success)
        assertEquals(setOf("default"), components.enabledKeys())
        assertEquals("default", result.view.choice.key)
    }

    @Test
    fun `multiple enabled entries surface as MULTIPLE`() {
        val components = FakeComponents("default")
        components.state["gold"] = LauncherComponentState.ENABLED
        components.state["mono"] = LauncherComponentState.ENABLED
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("gold", hidden = false)))

        val result = controller(components, preferences).inspect()

        assertFalse(result.success)
        assertEquals(LauncherIconCondition.MULTIPLE, result.view.condition)
        assertEquals(LauncherIconIssue.MULTIPLE, result.view.issue)
    }

    @Test
    fun `read failure surfaces as UNKNOWN and keeps preference fallback`() {
        val components = FakeComponents("default")
        components.readError = { throw SecurityException("denied") }
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("gold", hidden = false)))

        val result = controller(components, preferences).inspect()

        assertFalse(result.success)
        assertEquals(LauncherIconCondition.UNKNOWN, result.view.condition)
        assertEquals(LauncherIconIssue.READ_FAILED, result.view.issue)
        assertEquals("gold", result.view.choice.key)
    }

    @Test
    fun `save failure before apply leaves components untouched`() {
        val components = FakeComponents("default")
        val preferences = freshInstall()
        val controller = controller(components, preferences)
        controller.selectStyle("gold")
        val appliedBefore = components.applied.size
        preferences.failWrites = 1
        preferences.mutateOnFailure = true

        val result = controller.selectStyle("mono")

        assertFalse(result.success)
        assertEquals(LauncherIconIssue.SAVE_FAILED, result.view.issue)
        // 关键：pending 写盘失败时绝不动组件，避免出现无日志的已改状态。
        assertEquals(appliedBefore, components.applied.size)
        assertEquals(setOf("gold"), components.enabledKeys())
        assertEquals("gold", preferences.record.confirmed.key)
        assertNull(preferences.record.pending)
    }

    @Test
    fun `hidden style change persists without enabling any alias even after prior issue`() {
        val components = FakeComponents("default")
        val preferences = FakePreferences(LauncherIconRecord(LauncherIconChoice("gold", hidden = true)))
        val controller = controller(components, preferences)
        controller.selectStyle("mono")

        assertTrue(components.enabledKeys().isEmpty())
        assertEquals("mono", preferences.record.confirmed.key)
        assertTrue(preferences.record.confirmed.hidden)
    }
}
