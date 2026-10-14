package co.camilo.transmicrone

import androidx.test.platform.app.InstrumentationRegistry
import co.camilo.transmicrone.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PackagedCatalogTest {
    @Test
    fun appRouteRepositoryReadsPackagedOrderedStopsWithoutNetwork() = runBlocking {
        val app = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext)
        val option = app.catalog.service("E48", "CAD")!!.option
        val route = app.routes.load(option).single()
        assertEquals("12444", route.catalogId)
        assertEquals("San Mateo", route.stops.first().name)
        assertEquals("CAD", route.stops.last().name)
        assertEquals("A", route.stops.first { it.name == "León XIII" }.wagon)
    }

    @Test
    fun packagedCatalogResolvesCommutePlacesAndServicios() = runBlocking {
        val catalog = AppContainer(InstrumentationRegistry.getInstrumentation().targetContext).catalog
        val leon = catalog.search("leon").first { it.name == "León XIII" }
        assertTrue(leon.servicios.map { it.painted }.containsAll(listOf("E48", "K43")))
        val ricaurte = catalog.search("ricaurte")
        assertTrue(ricaurte.any { place ->
            place.servicios.map { it.painted }.containsAll(listOf("S48", "S43"))
        })
    }
}
