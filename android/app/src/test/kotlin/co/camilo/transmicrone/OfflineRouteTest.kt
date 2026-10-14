package co.camilo.transmicrone

import co.camilo.transmicrone.data.catalog.CatalogRouteRepository
import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfflineRouteTest {
    @Test fun identicalStopsKeepBothCatalogDirectionsAndTheirSchedules() = runBlocking {
        val catalog = JsonCatalogRepository(Json { ignoreUnknownKeys = true }, gzip = true) {
            File("src/main/assets/catalog.json.gz").inputStream()
        }
        val service = catalog.service("2-5", "San Cristóbal Norte ||  Portal Norte")!!
        val routes = catalog.routes(service.option)
        assertEquals(listOf("516", "13038"), routes.map { it.catalogId })
        assertEquals(routes[0].stops, routes[1].stops)
        val saturdayStarts = service.operatingHours.associate { hours ->
            hours.catalogId to hours.windows.single { it.dayGroup == "S" }.startTime
        }
        assertEquals("04:30", saturdayStarts[routes[0].catalogId])
        assertEquals("05:00", saturdayStarts[routes[1].catalogId])
    }

    @Test fun packagedRouteWorksOnFirstLoadAndAfterRepositoryRecreation() = runBlocking {
        repeat(2) {
            val catalog = JsonCatalogRepository(Json { ignoreUnknownKeys = true }, gzip = true) {
                File("src/main/assets/catalog.json.gz").inputStream()
            }
            val route = CatalogRouteRepository(catalog).load(catalog.service("E48", "CAD")!!.option).single()
            assertEquals("12444", route.catalogId)
            assertEquals("San Mateo", route.stops.first().name)
            assertEquals("CAD", route.stops.last().name)
            assertEquals("A", route.stops.first { it.name == "León XIII" }.wagon)
        }
    }

    @Test fun pairedRoutesKeepOrderRepeatedStopsAndPlatformDifferences() = runBlocking {
        val source = """{"service_routes":[
          {"catalog_id":"1","painted":"FZ63","destino":"Pradera","stops":[
            {"code":"A","name":"Alpha","wagon":"A"},{"code":"B","name":"Beta","stops_here":false},{"code":"A","name":"Alpha"}]},
          {"catalog_id":"2","painted":"Z63","destino":"Tibanica","stops":[{"code":"A","name":"Alpha","wagon":"B"}]},
          {"catalog_id":"3","painted":"Z63","destino":"Tibanica Festivo","stops":[{"code":"C","name":"Charlie"}]}
        ]}"""
        val catalog = JsonCatalogRepository(Json, gzip = false) { source.byteInputStream() }
        val routes = CatalogRouteRepository(catalog).load(ServicioOption("FZ63", "F63 Pradera || Z63 Tibanica", listOf(2), null))
        assertEquals(listOf("1", "2"), routes.map { it.catalogId })
        assertEquals(listOf("A", "B", "A"), routes.first().stops.map { it.code })
        assertFalse(routes.first().stops[1].stopsHere)
        assertEquals("B", routes.last().stops.single().wagon)
        assertTrue(catalog.routes(ServicioOption("Z63", "Unknown", listOf(2), null)).isEmpty())
        assertEquals(listOf("1", "3"), catalog.routes(ServicioOption("FZ63", "F63 Pradera || Z63 Tibanica Festivo", emptyList(), null)).map { it.catalogId })
    }
}
