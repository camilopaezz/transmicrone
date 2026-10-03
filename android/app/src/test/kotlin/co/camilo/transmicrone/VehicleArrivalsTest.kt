package co.camilo.transmicrone

import co.camilo.transmicrone.data.feed.ArrivalsParser
import co.camilo.transmicrone.data.feed.BodegaFeedClient
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VehicleArrivalsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-10-02T13:19:03Z")
    private val board = Board("test", "estacion:TM0069", PlaceKind.ESTACION, "TM0069", "Ricaurte - NQS", "NQS",
        listOf(SelectedServicio("S43", "San Mateo", listOf(13278))))

    @Test
    fun stationUsesIdentifiedVehicleArrivalsRatherThanServiceSummary() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(javaClass.classLoader!!.getResource("nearby-S43.json")!!.readText()))
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val result = client.fetchArrivals(board) as FetchResult.Success
            assertEquals(2, result.arrivals.size)
            assertTrue(result.arrivals.all { it.identity == ArrivalIdentity.VEHICLE })
            assertEquals("41405", result.arrivals.first().vehicleId)
            assertTrue(result.arrivals.first().etaPhrase.contains("4 min. y 7 min."))
            val request = server.takeRequest()
            assertEquals("/getServicios", request.path)
            assertEquals("test-installation", request.getHeader("uuid"))
            assertTrue(request.body.readUtf8().contains("\"estacion\":\"TM0069\""))
        } finally { server.shutdown() }
    }

    @Test
    fun vehicleReportsCarrySourceTimeAndSurviveDiskCache() {
        val parser = ArrivalsParser(json)
        val rows = parser.parseVehicles(javaClass.classLoader!!.getResource("nearby-S43.json")!!.readText(), now)
        val first = rows.first()
        assertEquals(Instant.parse("2026-10-02T13:18:07Z"), first.sourceReportedAt)
        assertEquals("2594 m", first.distanceLabel)
        assertEquals(EtaOrder.Key(1, 4, 7), EtaOrder.key(first.etaPhrase))
        val newer = first.copy(etaPhrase = "Entre 2 min. y 5 min.", sourceReportedAt = now)
        assertEquals(listOf(newer), ArrivalReducer.dedup(listOf(first, newer)))
        val dir = java.nio.file.Files.createTempDirectory("vehicle-cache").toFile()
        try {
            val cache = co.camilo.transmicrone.data.local.FileArrivalsCache(dir, json)
            cache.write("test", CachedArrivals(rows, now, false))
            assertEquals(rows, cache.read("test")!!.arrivals)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun sourceClockHandlesMidnightAndMissingTime() {
        val body = """[{"vehicleid":1,"ruta_extraida":"S43","lasttime":"11:59:00 PM"}]"""
        val parser = ArrivalsParser(json)
        val row = parser.parseVehicles(body, Instant.parse("2026-10-03T05:01:00Z")).single()
        assertEquals(Instant.parse("2026-10-03T04:59:00Z"), row.sourceReportedAt)
        assertNull(parser.parseVehicles(body.replace("11:59:00 PM", "bad time"), now).single().sourceReportedAt)
    }

    @Test
    fun queryingAllVariantsDoesNotDuplicateTheSameBus() = runBlocking {
        val server = MockWebServer()
        val fixture = javaClass.classLoader!!.getResource("nearby-S43.json")!!.readText()
        repeat(3) { server.enqueue(MockResponse().setBody(fixture)) }
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val variants = board.copy(servicios = listOf(board.servicios.single().copy(routeIds = listOf(13277, 13278, 13279))))
            val result = client.fetchArrivals(variants) as FetchResult.Success
            assertEquals(2, result.arrivals.size)
            assertEquals(3, server.requestCount)
            val ids = (1..3).map { json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["idRuta"]!!.jsonPrimitive.int }.toSet()
            assertEquals(setOf(13277, 13278, 13279), ids)
        } finally { server.shutdown() }
    }

    @Test
    fun rejectedVehicleQueryNeverFallsBackToArrivingServiceSummary() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val result = client.fetchArrivals(board) as FetchResult.Failure
            assertEquals(FailureKind.HOST_REJECTED, result.kind)
            assertEquals(1, server.requestCount)
            assertEquals("/getServicios", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test
    fun successfulEmptyVehicleBodyIsNoNearbyBuses() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(""))
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            assertTrue(client.fetchArrivals(board) is FetchResult.Empty)
        } finally { server.shutdown() }
    }
}
