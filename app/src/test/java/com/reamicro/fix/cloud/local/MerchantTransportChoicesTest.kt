package com.reamicro.fix.cloud.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantTransportChoicesTest {

    private fun transport(
        id: String,
        type: String,
        owned: Boolean,
    ) = CloudTaskLocalRunner.MerchantTransportOption(
        id = id,
        label = id,
        owned = owned,
        transportType = type,
        carryingCapacity = 45L,
        speedPercent = 10L,
    )

    private val landCity = CloudTaskLocalRunner.MerchantCityOption(
        code = "GUSU",
        label = "姑苏",
        requiredTransportType = "",
        routeType = "LAND",
        baseDurationMinutes = 480L,
    )

    @Test
    fun landCityStillExposesAllOwnedShips() {
        val horse = transport("3", "HORSE", owned = true)
        val ship = transport("8", "SHIP", owned = true)

        val choices = CloudTaskLocalRunner.merchantTransportChoices(listOf(ship, horse), landCity)

        assertEquals(listOf("3", "8"), choices.map { it.id })
    }

    @Test
    fun unownedTransportsStayHidden() {
        val ownedShip = transport("7", "SHIP", owned = true)
        val unownedShip = transport("9", "SHIP", owned = false)

        val choices = CloudTaskLocalRunner.merchantTransportChoices(
            listOf(ownedShip, unownedShip),
            selectedCity = null,
        )

        assertEquals(listOf("7"), choices.map { it.id })
    }

    @Test
    fun cityRequiringShipRejectsNoTransportAndHorse() {
        val city = landCity.copy(
            code = "JIANGLING",
            requiredTransportType = "SHIP",
            routeType = "SEA",
        )

        assertEquals(false, CloudTaskLocalRunner.merchantCityAccepts(city, null))
        assertEquals(false, CloudTaskLocalRunner.merchantCityAccepts(city, transport("3", "HORSE", true)))
        assertEquals(true, CloudTaskLocalRunner.merchantCityAccepts(city, transport("8", "SHIP", true)))
    }
}
