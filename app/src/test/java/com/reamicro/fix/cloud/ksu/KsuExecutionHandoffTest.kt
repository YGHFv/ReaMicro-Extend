package com.reamicro.fix.cloud.ksu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KsuExecutionHandoffTest {
    @Test
    fun `ambiguous enable timeout never gives execution back to Android`() {
        var ksuOwnsExecution = false
        val handoff = KsuExecutionHandoff({ ksuOwnsExecution = it }, { _, _ ->
            assertTrue(ksuOwnsExecution)
            error("lost response after root accepted configuration")
        }, {})
        assertTrue(runCatching { handoff.enable(JSONObject()) }.isFailure)
        assertTrue(ksuOwnsExecution)
    }

    @Test
    fun `failed mode persistence prevents root execution`() {
        var commands = 0
        val handoff = KsuExecutionHandoff({ error("disk full") }, { _, _ -> commands++; JSONObject() }, {})
        assertTrue(runCatching { handoff.enable(JSONObject()) }.isFailure)
        assertEquals(0, commands)
    }

    @Test
    fun `Android takes ownership only after stopped root snapshot was restored`() {
        var ksuOwnsExecution = true
        val steps = mutableListOf<String>()
        val handoff = KsuExecutionHandoff({ ksuOwnsExecution = it; steps += "android" }, { action, _ ->
            steps += action
            JSONObject().put("enabled", false)
        }, { assertTrue(ksuOwnsExecution); steps += "restore" })
        handoff.disable()
        assertEquals(listOf("disable", "restore", "android"), steps)
        assertFalse(ksuOwnsExecution)
    }

    @Test
    fun `failed snapshot restore leaves Android blocked`() {
        var ksuOwnsExecution = true
        val handoff = KsuExecutionHandoff({ ksuOwnsExecution = it }, { _, _ -> JSONObject().put("enabled", false) }, {
            error("snapshot could not be saved")
        })
        assertTrue(runCatching { handoff.disable() }.isFailure)
        assertTrue(ksuOwnsExecution)
    }
}
