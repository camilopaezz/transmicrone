package co.camilo.transmicrone

import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.board.arrivalDisplay
import co.camilo.transmicrone.ui.board.arrivalListKeys
import org.junit.Assert.*
import org.junit.Test

class ArrivalDisplayTest {
    private fun arrival(phrase: String, distance: String? = null) = Arrival(
        "S43", "San Mateo", 13278, null, phrase, distance,
        Occupancy.UNKNOWN, null, null,
    )

    @Test fun repairsJoinedFeedWordsAndSuppressesMatchingDistanceOnly() {
        val row = arrival("Servicio llegandoa 600 metros", "600 m")
        assertEquals("Servicio llegando", arrivalDisplay(row).phrase)
        assertEquals("600 m", arrivalDisplay(row).distance)
        assertEquals("Servicio llegandoa 600 metros", row.etaPhrase)
        assertEquals("Servicio llegando", arrivalDisplay(arrival("Servicio llegandoLlegando a 600 m", "600 m")).phrase)
    }

    @Test fun preservesRoundedDistanceAndTimeEstimatesWhenExactDistanceDiffers() {
        val display = arrivalDisplay(arrival("Entre 4 min. y 7 min. a: 2.6 km", "2594 m"))
        assertEquals("Entre 4 min. y 7 min. a: 2,6 km", display.phrase)
        assertEquals("2594 m", display.distance)
        assertEquals("Entre 4 min. y 7 min.", arrivalDisplay(arrival("Entre 4 min. y 7 min. a: 2,6 km", "2600 m")).phrase)
    }

    @Test fun retainsDistanceWhenStructuredValueIsMissingOrUnparseable() {
        assertEquals("Servicio llegando a 800 m", arrivalDisplay(arrival("Servicio llegandoa 800 metros")).phrase)
        assertEquals("Servicio llegando a 800 m", arrivalDisplay(arrival("Servicio llegandoa 800 metros", "Sin datos")).phrase)
        assertEquals("Aún faltan 1200 m", arrivalDisplay(arrival("Aún faltan 1200 metros")).phrase)
    }

    @Test fun understandsSpanishMetreGroupingWithoutChangingKilometreDecimals() {
        val display = arrivalDisplay(arrival("Servicio llegando a 1.200 metros", "1200 m"))
        assertEquals("Servicio llegando", display.phrase)
        assertEquals("1200 m", display.distance)
        assertEquals("A 1,234 km", arrivalDisplay(arrival("A 1.234 km")).phrase)
    }

    @Test fun keepsVehicleIdentityStableAcrossUpdatesAndOrdering() {
        val first = arrival("Entre 4 y 7 min", "2594 m").copy(identity = ArrivalIdentity.VEHICLE, vehicleId = "41405")
        val second = first.copy(vehicleId = "41406")
        val updated = first.copy(etaPhrase = "Servicio llegando", distanceLabel = "100 m")
        assertEquals(arrivalListKeys(listOf(first, second)).first(), arrivalListKeys(listOf(second, updated)).last())
        assertNotEquals(arrivalListKeys(listOf(first)).single(), arrivalListKeys(listOf(first.copy(routeId = 13279))).single())
    }

    @Test fun repeatedStopEstimatesAlwaysHaveDistinctKeysWithoutListIndexDependence() {
        val row = arrival("Servicio llegando")
        val other = row.copy(painted = "B23", routeId = 12819)
        val keys = arrivalListKeys(listOf(row, row, other))
        assertEquals(3, keys.toSet().size)
        assertEquals(keys.last(), arrivalListKeys(listOf(other, row, row)).first())
    }
}
