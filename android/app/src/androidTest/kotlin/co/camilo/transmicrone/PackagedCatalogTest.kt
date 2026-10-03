package co.camilo.transmicrone

import androidx.test.platform.app.InstrumentationRegistry
import co.camilo.transmicrone.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagedCatalogTest {
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
