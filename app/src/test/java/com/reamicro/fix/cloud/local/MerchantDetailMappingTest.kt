package com.reamicro.fix.cloud.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行商响应的中文映射。
 *
 * 用的是实机抓到的真实响应切片：响应里带着 `cities[]`（code→name）与 `transports[]`
 * （id→name/speedPercent），`activeTrip` 自带 `blessingName/blessingEffectType/blessingEffectValue`。
 * 原来界面直接把 `LANGYA`、`transportId=5`、`MERCHANT_PROFIT_BONUS` 这类内部值摆给用户看，
 * 所以这里锁住"翻成中文"这一步。
 */
class MerchantDetailMappingTest {

    private val runner = CloudTaskLocalRunner

    /** 实机响应切片（只留与映射相关的字段）。 */
    private val body = JSONObject(
        """
        {"code":0,"message":"OK","data":{
          "cities":[{"id":"1","code":"GUSU","name":"姑苏"},{"id":"2","code":"LANGYA","name":"琅琊"}],
          "transports":[
            {"id":"3","code":"QINGHAI_CONG","name":"青海骢","speedPercent":"15","traitCode":"MOUNTAIN","traitName":"山地"},
            {"id":"5","code":"HEQU_HORSE","name":"河曲马","speedPercent":"5","traitName":""}
          ],
          "activeTrip":{
            "id":"2125","cityCode":"LANGYA","transportId":"5","principal":"60",
            "plannedDurationMinutes":"690","totalDurationMinutes":"690","status":"TRAVELING",
            "startTime":"1789395185","endTime":"1789436585","eventTitle":"","eventContent":"",
            "profitPercent":"0","settlementAmount":"0",
            "blessingName":"增益签","blessingEffectType":"MERCHANT_PROFIT_BONUS","blessingEffectValue":"20"
          }
        }}
        """.trimIndent(),
    )

    @Test
    fun `城池 code 翻成中文名`() {
        assertEquals("琅琊", runner.parseMerchantTrip(body).cityName)
    }

    @Test
    fun `车马翻成名称与速度`() {
        assertEquals("河曲马 · 速度 +5%", runner.parseMerchantTrip(body).transportLabel)
    }

    @Test
    fun `带特长的车马把特长也显示出来`() {
        val withTrait = JSONObject(body.toString())
        withTrait.getJSONObject("data").getJSONObject("activeTrip").put("transportId", "3")
        assertEquals("青海骢 · 速度 +15% · 山地", runner.parseMerchantTrip(withTrait).transportLabel)
    }

    @Test
    fun `行商自带的运签被解析出来`() {
        val trip = runner.parseMerchantTrip(body)
        assertEquals("增益签", trip.blessingName)
        assertEquals("商事盈利收益率提升 20%", runner.blessingEffectText(trip.blessingEffectType, trip.blessingEffectValue))
    }

    @Test
    fun `每日轶闻的运签效果也能翻`() {
        assertEquals(
            "下一次每日轶闻：绿色及以上概率提升 2 个百分点",
            runner.blessingEffectText("LORE_THRESHOLD_BONUS", "2"),
        )
    }

    @Test
    fun `未知效果类型不装懂也不留空`() {
        val text = runner.blessingEffectText("SOMETHING_NEW", "7")
        assertTrue(text.contains("SOMETHING_NEW"))
        assertTrue(text.contains("7"))
    }

    @Test
    fun `查不到对照表时退回原值而不是丢掉信息`() {
        val unknown = JSONObject(body.toString())
        val data = unknown.getJSONObject("data")
        data.remove("cities")
        data.getJSONObject("activeTrip").put("transportId", "99")
        val trip = runner.parseMerchantTrip(unknown)
        assertEquals("LANGYA", trip.cityName)
        assertTrue(trip.transportLabel.isNotBlank())
    }

    @Test
    fun `没有在途行商时不构造假行程`() {
        val empty = JSONObject("""{"code":0,"data":{"activeTrip":null,"cities":[],"transports":[]}}""")
        assertEquals(false, runner.parseMerchantTrip(empty).hasTrip)
    }
}
