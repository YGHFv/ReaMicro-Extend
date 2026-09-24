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
    fun `禁当期物清单覆盖脚本里的 11 到 18 加按名禁当的青圭`() {
        val prohibited = CloudTaskLocalRunner.PROHIBITED_PAWN_PROP_HINTS
        // 青圭的 propId 静态拿不到（背包 materials 里没有），用 name: 前缀键占位，
        // 执行时按当日期物名字兜底匹配。
        assertEquals(((11..18).map(Int::toString) + "name:青圭").toSet(), prohibited.keys)
        assertTrue(prohibited.values.all { it.isNotBlank() })
        // 12/14/15 是祈禳消耗品：典当掉就没法祈禳了，这条最容易踩。
        assertTrue(prohibited.getValue("12").contains("祈禳"))
        assertTrue(prohibited.getValue("14").contains("祈禳"))
        assertTrue(prohibited.getValue("15").contains("祈禳"))
        assertTrue(prohibited.getValue("name:青圭").contains("招募"))
    }

    @Test
    fun `禁当清单解析选择器回传的 ID 列表`() {
        assertEquals(setOf("11", "12"), CloudTaskLocalRunner.parseForbiddenPawnPropIds("11,12"))
        assertEquals(setOf("99"), CloudTaskLocalRunner.parseForbiddenPawnPropIds(" 99 "))
        // 一项都没选 = 显式不禁止任何期物，不能回落成默认清单。
        assertEquals(emptySet<String>(), CloudTaskLocalRunner.parseForbiddenPawnPropIds(""))
    }

    @Test
    fun `图鉴合并保留已学到的品质，不完整条目直接丢掉`() {
        val catalog = JSONObject().put("21", JSONObject().put("name", "青玉").put("quality", "BLUE"))
        val merged = CloudTaskLocalRunner.mergePawnPropCatalog(
            catalog,
            listOf(
                // 背包只给了名字没给品质：不能把上次记下的品质抹掉。
                CloudTaskLocalRunner.PawnPropChoice("21", "青玉", ""),
                // 缺 ID 或缺名字的条目记下来只会污染清单。
                CloudTaskLocalRunner.PawnPropChoice("", "没有 ID", "RED"),
                CloudTaskLocalRunner.PawnPropChoice("23", "", "RED"),
                CloudTaskLocalRunner.PawnPropChoice("24", "残卷", "RED"),
            ),
        )
        assertEquals("BLUE", merged.getJSONObject("21").getString("quality"))
        assertEquals(2, merged.length())
        assertEquals("RED", merged.getJSONObject("24").getString("quality"))
    }

    @Test
    fun `背包条目按宿主字段名读取，没名字的与占位期物不进图鉴`() {
        // 之前这里读的是不存在的 propName/propQuality，导致背包学到的期物全被丢掉，
        // 配置页只剩内置清单——用户看到的就是"读不全"。
        val catalog = CloudTaskLocalRunner.mergePawnPropCatalog(
            JSONObject(),
            CloudTaskLocalRunner.bagPawnPropChoices(
                JSONArray()
                    .put(JSONObject().put("propId", 21).put("name", "青玉").put("quality", "BLUE").put("quantity", 2))
                    // 别的材料没有名字，混进来只会变成一行点不动的空条目。
                    .put(JSONObject().put("propId", 31).put("quantity", 3))
                    // 老字段名只在服务端临时改回去时用得上，保底别丢。
                    .put(JSONObject().put("propId", 24).put("propName", "残卷").put("propQuality", "RED"))
                    // 0 是服务端"今天没有期物"的占位值。
                    .put(JSONObject().put("propId", 0).put("name", "空期物").put("quality", "GREY")),
            ),
        )
        assertEquals(2, catalog.length())
        assertEquals("青玉", catalog.getJSONObject("21").getString("name"))
        assertEquals("BLUE", catalog.getJSONObject("21").getString("quality"))
        assertEquals("残卷", catalog.getJSONObject("24").getString("name"))
    }

    @Test
    fun `配置页期物清单按品质排序且不丢内置清单`() {
        val state = JSONObject().put(
            CloudTaskLocalRunner.KEY_PAWN_PROP_CATALOG,
            JSONObject()
                .put("21", JSONObject().put("name", "青玉").put("quality", "BLUE"))
                .put("22", JSONObject().put("name", "残卷").put("quality", "RED")),
        )
        val choices = CloudTaskLocalRunner.pawnPropChoices(state)
        // 还没见过的期物也必须留在清单里，否则用户没法提前锁它。
        assertTrue(choices.map { it.propId }.containsAll((11..18).map(Int::toString)))
        // 品质高的排前面，没有品质的（内置清单里的那些）垫底。
        val red = choices.indexOfFirst { it.propId == "22" }
        val blue = choices.indexOfFirst { it.propId == "21" }
        val unknown = choices.indexOfFirst { it.propId == "11" }
        assertTrue(red < blue && blue < unknown)
        // 内置清单只写得出用途，期物名在括号里，列表要显示名字而不是整句用途。
        val seed = choices.first { it.propId == "11" }
        assertEquals("清酒", seed.name)
        assertEquals("传承消耗物品", seed.hint)
        assertEquals("清酒（传承消耗物品）", seed.label)
        // 学到的名字盖掉内置的推断名；不在内置清单里的期物只显示名字。
        assertEquals("青玉", choices.first { it.propId == "21" }.label)
        assertEquals("剡藤（祈禳消耗物品）", choices.first { it.propId == "12" }.label)
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
