package co.camilo.transmicrone

import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ServiceOperatingHoursTest {
    private fun hours(id: String, code: String, destination: String) = ServiceOperatingHours(
        id, code, destination, "2026-10-13T01:00:00Z",
        listOf(OperatingWindow("L-V", "04:00", "10:00")),
    )

    @Test fun matchesDestinationAndCatalogIdentityInsteadOfGtfsId() {
        val expected = hours("12444", "E48", "CAD")
        val wrongDirection = hours("13266", "E48", "Another destination")
        assertEquals(listOf(expected), serviceOperatingHours(
            ServicioOption("E48", "cad", listOf(13266), null), listOf(wrongDirection, expected)))
    }

    @Test fun compositeDirectionsKeepTheirOwnSchedulesAndPreferQualifiedSibling() {
        val ordinary = hours("1", "F63", "Pradera")
        val qualified = hours("2", "F63", "Pradera Festivo")
        val other = hours("3", "Z63", "Tibanica - Festivo")
        assertEquals(listOf(qualified, other), serviceOperatingHours(
            ServicioOption("FZ63", "F63 Pradera || Z63 Tibanica Festivo", listOf(999), null),
            listOf(ordinary, qualified, other)))
    }

    @Test fun offlineCatalogRetainsWindowsAndDoesNotMergeDifferentDirections() = runBlocking {
        val source = """{"schema_version":1,"service_hours":[
            {"catalog_id":"12444","painted":"E48","destino":"CAD","fetched_at":"2026-10-13T01:00:00Z",
             "windows":[{"day_group":"L-V","start_time":"04:00","end_time":"10:00"},
                        {"day_group":"L-V","start_time":"16:00","end_time":"00:30"}]},
            {"catalog_id":"13266","painted":"E48","destino":"Other","windows":[]}
        ],"places":[{"place_id":"p","kind":"estacion","name":"León XIII","fold":"leon xiii","query_paradero":"TM0101",
          "servicios":[{"painted":"E48","destino":"CAD","route_ids":[13266]},
                       {"painted":"E48","destino":"Missing","route_ids":[13267]}]}]}"""
        val repo = JsonCatalogRepository(Json, gzip = false) { source.byteInputStream() }
        val schedule = repo.service("E48", "CAD")!!.operatingHours.single()
        assertEquals("12444", schedule.catalogId)
        assertEquals("2026-10-13T01:00:00Z", schedule.fetchedAt)
        assertEquals(listOf(OperatingWindow("L-V", "04:00", "10:00"),
            OperatingWindow("L-V", "16:00", "00:30")), schedule.windows)
        assertTrue(repo.service("E48", "Missing")!!.operatingHours.isEmpty())
    }
}
