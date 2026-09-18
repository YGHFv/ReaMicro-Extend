package com.reamicro.fix.cloud.local

import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    fun `禁当清单文本解析与回显`() {
        assertEquals(setOf("11", "12"), CloudTaskLocalRunner.parseForbiddenPawnPropIds("11|清酒\n12|剡藤\n"))
        assertEquals(setOf("99"), CloudTaskLocalRunner.parseForbiddenPawnPropIds("99"))
        assertEquals(emptySet<String>(), CloudTaskLocalRunner.parseForbiddenPawnPropIds("  \n# 只是注释\n"))
        val formatted = CloudTaskLocalRunner.formatForbiddenPawnPropIds(setOf("12", "99", "11"))
        assertEquals("11|传承消耗物品（清酒）\n12|祈禳消耗物品（剡藤）\n99", formatted)
        // 回显再解析必须回到同一个集合，否则用户只是打开保存一次就会改坏配置。
        assertEquals(setOf("11", "12", "99"), CloudTaskLocalRunner.parseForbiddenPawnPropIds(formatted))
    }

    @Test
    fun `旧配置缺省沿默认清单，显式清空则不禁止`() {
        val default = CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS.keys
        assertEquals(default, localTaskFromJson("pawn", JSONObject().put("taskType", "pawn")).forbiddenPawnPropIds)
        assertTrue(localTaskFromJson("pawn", JSONObject().put("forbiddenPawnPropIds", JSONArray()))
            .forbiddenPawnPropIds.isEmpty())
        assertEquals(
            setOf("11"),
            localTaskFromJson("pawn", JSONObject().put("forbiddenPawnPropIds", JSONArray().put("11"))).forbiddenPawnPropIds,
        )
        assertEquals(0, localTaskRequest(localTaskFromJson("pawn", JSONObject().put("forbiddenPawnPropIds", JSONArray())))
            .getJSONArray("forbiddenPawnPropIds").length())
    }

    @Test
    fun `每日轶闻奖励明细与服务端形状一致`() {
        val items = dailyLoreRewardItems(
            JSONObject().put("exp", "4").put("gem", 3).put("propName", "端砚").put("propQuality", "蓝"),
        )
        assertEquals(3, items.length())
        assertEquals("阅历", items.getJSONObject(0).getString("name"))
        assertEquals(4, items.getJSONObject(0).getInt("count"))
        assertEquals("", items.getJSONObject(0).getString("quality"))
        assertEquals("端砚", items.getJSONObject(2).getString("name"))
        assertEquals("蓝", items.getJSONObject(2).getString("quality"))
        assertEquals(1, items.getJSONObject(2).getInt("count"))
        assertEquals(0, dailyLoreRewardItems(JSONObject()).length())
    }
}
