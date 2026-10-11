package co.camilo.transmicrone

import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CatalogExploreTest {
    private fun catalog() = JsonCatalogRepository(Json { ignoreUnknownKeys = true }, gzip = false) {
        javaClass.classLoader!!.getResourceAsStream("catalog-mini.json")!!
    }

    @Test fun matchesCodeDestinationAccentAndPlaceCode() = runBlocking {
        val repo = catalog()
        val service = repo.explore(" e48 ").services.single()
        assertEquals("CAD", service.option.destino)
        assertEquals(listOf("estacion:TM0101"), service.places.map { it.placeId })
        assertEquals("E48", repo.explore("cad").services.single().option.painted)
        assertEquals("León XIII", repo.explore("leon   xiii").places.single().name)
        assertEquals("León XIII", repo.explore("TM0101").places.single().name)
        assertEquals("Br. Liberia", repo.explore("080a01").places.single().name)
        assertTrue(repo.explore("x").services.isEmpty())
        assertTrue(repo.explore("no-such-place").places.isEmpty())
    }

    @Test fun addBoardSearchUsesNamesCodesAndCollapsedWhitespace() = runBlocking {
        val repo = catalog()

        assertEquals("León XIII", repo.search("leon   xiii").single().name)
        assertEquals("León XIII", repo.search("leon xiii soacha").single().name)
        assertEquals("León XIII", repo.search("TM0101").single().name)
        assertEquals("Br. Liberia", repo.search("080a01").single().name)
    }

    @Test fun exploreReturnsAllByDefaultAndHonorsExplicitLimit() = runBlocking {
        val services = (1..49).joinToString(",") { index ->
            """{"painted":"S$index","destino":"Usme $index"}"""
        }
        val source = """{"schema_version":1,"places":[{"place_id":"p","kind":"estacion","name":"Portal","fold":"portal","query_paradero":"TM0001","servicios":[$services]}]}"""
        val repo = JsonCatalogRepository(Json, gzip = false) { source.byteInputStream() }

        assertEquals(49, repo.explore("usme").services.size)
        assertEquals(40, repo.explore("usme", limit = 40).services.size)
    }

    @Test fun serviceExploreUsesBestPaintedOrDestinationRank() = runBlocking {
        val source = """{"schema_version":1,"places":[{"place_id":"p","kind":"estacion","name":"Portal","fold":"portal","query_paradero":"TM0001","servicios":[{"painted":"100","destino":"Cadillac"},{"painted":"500","destino":"CAD"}]}]}"""
        val repo = JsonCatalogRepository(Json, gzip = false) { source.byteInputStream() }

        assertEquals(listOf("500", "100"), repo.explore("cad").services.map { it.option.painted })
    }

    @Test fun cachedPlaceFieldsPreserveRanksAndAllTieBreaks() = runBlocking {
        val source = """{"places":[
            {"place_id":"contains-b","kind":"estacion","name":"Acadia","fold":"acadia","disambiguator":"B","query_paradero":"01"},
            {"place_id":"prefix-stop","kind":"paradero","name":"Cadence","fold":"cadence","query_paradero":"02"},
            {"place_id":"contains-beta","kind":"estacion","name":"Beta Acad","fold":"beta acad","query_paradero":"03"},
            {"place_id":"exact","kind":"paradero","name":"Zulu","fold":"cád","query_paradero":"04"},
            {"place_id":"contains-a","kind":"estacion","name":"Acadia","fold":"acadia","disambiguator":"A","query_paradero":"05"},
            {"place_id":"prefix-station","kind":"estacion","name":"Cadillac","fold":"cadillac","query_paradero":"06"},
            {"place_id":"contains-null","kind":"estacion","name":"Acadia","fold":"acadia","query_paradero":"07"}
        ]}"""
        var opens = 0
        val repo = JsonCatalogRepository(Json, gzip = false) {
            opens++
            source.byteInputStream()
        }
        repo.generatedAt()
        val expected = listOf("exact", "prefix-station", "prefix-stop", "contains-null", "contains-a", "contains-b", "contains-beta")

        assertEquals(expected, repo.search(" CÁD ").map { it.placeId })
        assertEquals(expected, repo.explore("cad").places.map { it.placeId })
        assertEquals("contains-null", repo.search("acadia   07").single().placeId)
        assertEquals(1, opens)
    }

    @Test fun cachedServiceFieldsPreserveSplitDestinationRanksAndTieBreaks() = runBlocking {
        val source = """{"places":[{"place_id":"p","kind":"estacion","name":"Portal","fold":"portal","query_paradero":"TM0001","servicios":[
            {"painted":"200","destino":"Académie"},
            {"painted":"100","destino":"Cadillac"},
            {"painted":"900","destino":"Other||  CÁD  "},
            {"painted":"200","destino":"Acadia"}
        ]}]}"""
        val repo = JsonCatalogRepository(Json, gzip = false) { source.byteInputStream() }

        assertEquals(
            listOf("900" to "Other||  CÁD  ", "100" to "Cadillac", "200" to "Acadia", "200" to "Académie"),
            repo.explore("cad").services.map { it.option.painted to it.option.destino },
        )
    }

    @Test fun rejectsUnsupportedPackedSchemaVersion() = runBlocking {
        val source = """{"schema_version":2,"places":[]}"""
        val repo = JsonCatalogRepository(Json, gzip = false) { source.byteInputStream() }

        assertTrue(runCatching { repo.explore("portal") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun deduplicatesServiceAcrossPlacesWithoutMergingDestinations() = runBlocking {
        val json = """{"places":[
            {"place_id":"a","kind":"estacion","name":"Alpha","fold":"alpha","query_paradero":"A",
             "servicios":[{"painted":"E48","destino":"CAD","route_ids":[1]},{"painted":"E48","destino":"Other","route_ids":[3]},
              {"painted":"11","destino":"North","route_ids":[4]},{"painted":"1","destino":"South","route_ids":[5]}]},
            {"place_id":"b","kind":"estacion","name":"Beta","fold":"beta","query_paradero":"B",
             "servicios":[{"painted":"E48","destino":"CAD","route_ids":[1,2]}]}
        ]}"""
        val repo = JsonCatalogRepository(Json, gzip = false) { json.byteInputStream() }
        val results = repo.explore("E48").services
        assertEquals(2, results.size)
        val cad = repo.service("E48", "CAD")!!
        assertEquals(listOf(1, 2), cad.option.routeIds)
        assertEquals(listOf("a", "b"), cad.places.map { it.placeId })
        assertEquals(listOf("a"), repo.service("E48", "Other")!!.places.map { it.placeId })
        assertNull(repo.service("E48", "Unknown"))
        assertEquals(1, repo.explore("E48", limit = 1).services.size)
        assertEquals(listOf("1", "11"), repo.explore("1").services.map { it.option.painted })
    }
}
