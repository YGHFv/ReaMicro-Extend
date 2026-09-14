package com.reamicro.fix.cloud.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 道观运签与期物典当的固定契约。
 *
 * 这两组值都是**游戏侧的判定依据**，写错会静默失效（祈禳被判非法、或者把该留着的消耗品典当掉）：
 * - 签种 wire 值取自宿主 `ui/shrine/components/TempleWay`，只有 LUCK/SAFETY/WEALTH 三个；
 * - 禁当期物清单与参考脚本 `PAWN_PROHIBITED` 一致，都是祈禳/传承/夺宝要用的消耗品。
 */
class CloudTaskBlessingAndPawnTest {

    @Test
    fun `签种取值与宿主一致`() {
        assertEquals("LUCK", CloudTaskLocalRunner.BLESSING_LUCK)
        assertEquals("SAFETY", CloudTaskLocalRunner.BLESSING_SAFETY)
        assertEquals("WEALTH", CloudTaskLocalRunner.BLESSING_WEALTH)
    }

    @Test
    fun `三种签的中文名`() {
        assertEquals("求运签", CloudTaskLocalRunner.blessingLabel("LUCK"))
        assertEquals("求安签", CloudTaskLocalRunner.blessingLabel("safety"))
        assertEquals("求财签", CloudTaskLocalRunner.blessingLabel("WEALTH"))
    }

    @Test
    fun `空签种是「不祈禳」而不是未知`() {
        // 用户明确要求能选择不祈禳：空串代表"不发祈禳请求"，界面上要显示成人话。
        assertEquals("不祈禳", CloudTaskLocalRunner.blessingLabel(""))
        assertEquals("不祈禳", CloudTaskLocalRunner.blessingLabel("  "))
    }

    @Test
    fun `未知签种退回通用称呼而不是显示原文`() {
        // 服务端将来加了新签种也不能把裸 wire 值塞进通知里给用户看。
        assertEquals("运签", CloudTaskLocalRunner.blessingLabel("SOMETHING_NEW"))
    }

    @Test
    fun `禁当期物清单覆盖脚本里的 11 到 18`() {
        val prohibited = CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS
        assertEquals((11..18).map(Int::toString).toSet(), prohibited.keys)
        assertTrue(prohibited.values.all { it.isNotBlank() })
        // 12/14/15 是祈禳消耗品：典当掉就没法祈禳了，这条最容易踩。
        assertTrue(prohibited.getValue("12").contains("祈禳"))
        assertTrue(prohibited.getValue("14").contains("祈禳"))
        assertTrue(prohibited.getValue("15").contains("祈禳"))
    }
}
