package co.camilo.transmicrone

import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.ArrivalIdentity
import co.camilo.transmicrone.domain.ArrivalReducer
import co.camilo.transmicrone.domain.Occupancy
import co.camilo.transmicrone.domain.SelectedServicio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalReducerTest {
    @Test
    fun pairedCatalogDestinationsMatchEitherNormalizedSide() {
        val selected = SelectedServicio("359", "Calle 53 || Zona Industrial Álamos", listOf(2468))

        assertTrue(ArrivalReducer.matches(arrival("359", "Calle 53"), listOf(selected)))
        assertTrue(ArrivalReducer.matches(arrival("359", "Zona Industrial A\u0301lamos"), listOf(selected)))
        assertTrue(ArrivalReducer.matches(arrival("359", "  Zona   Industrial\tÁlamos  "), listOf(selected)))
        assertFalse(ArrivalReducer.matches(arrival("359", "Industrial Álamos"), listOf(selected)))
    }

    @Test
    fun compositeServiceCodesAreRemovedFromSidesWithoutBypassingPaintedOrRouteChecks() {
        val selected = SelectedServicio("F63", "F63 Pradera || Z63 Tibanica", listOf(8100))

        assertTrue(ArrivalReducer.matches(arrival("F63", "Pradera", 8100), listOf(selected)))
        assertFalse(ArrivalReducer.matches(arrival("F63", "Tibanica", 8100), listOf(selected)))
        assertFalse(ArrivalReducer.matches(arrival("X63", "Pradera", 8100), listOf(selected)))
        assertFalse(ArrivalReducer.matches(arrival("F63", "Pradera", 8101), listOf(selected)))
        assertFalse(ArrivalReducer.matches(arrival("F63", "Pradera Express", 8100), listOf(selected)))
    }

    @Test
    fun realCompositeSummaryMatchesOnlyValidatedDirectionAndRoute() {
        val selected = listOf(SelectedServicio("FZ63", "F63 Pradera || Z63 Tibanica", listOf(13789)))
        assertTrue(ArrivalReducer.matches(arrival("Z63", "Tibanica", 13789), selected))
        assertFalse(ArrivalReducer.matches(arrival("F63", "Pradera", 13789), selected))
        assertFalse(ArrivalReducer.matches(arrival("Z63", "Pradera", 13789), selected))
        assertFalse(ArrivalReducer.matches(arrival("Z63", "Tibanica", 13168), selected))
        assertFalse(ArrivalReducer.matches(arrival("X63", "Tibanica", 13789), selected))
        assertFalse(ArrivalReducer.matches(arrival("Z63", "Tibanica", null), selected))
    }

    private fun arrival(painted: String, destino: String, routeId: Int? = 2468) = Arrival(
        painted = painted,
        destino = destino,
        routeId = routeId,
        nodo = null,
        etaPhrase = "Entre 2 y 4 min",
        distanceLabel = null,
        occupancy = Occupancy.UNKNOWN,
        colorRuta = null,
        identity = ArrivalIdentity.SERVICIO_ESTIMATE,
    )
}
