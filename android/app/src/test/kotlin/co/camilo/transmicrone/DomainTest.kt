package co.camilo.transmicrone

import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import co.camilo.transmicrone.data.feed.ArrivalsParser
import co.camilo.transmicrone.data.feed.BodegaFeedClient
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.BoardSnapshot
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.data.local.saving
import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.ArrivalReducer
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.EtaOrder
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.FetchResult
import co.camilo.transmicrone.domain.Occupancy
import co.camilo.transmicrone.domain.OccupancyParser
import co.camilo.transmicrone.domain.Paradero
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.domain.TextNorm
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.time.Instant

class DomainTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun occupancyEmptyIsUnknownNotVacio() {
        assertEquals(Occupancy.UNKNOWN, OccupancyParser.parse(""))
        assertEquals(Occupancy.UNKNOWN, OccupancyParser.parse("   "))
        assertEquals(Occupancy.UNKNOWN, OccupancyParser.parse(null))
        assertEquals(Occupancy.VACIO, OccupancyParser.parse("VACIO"))
        assertEquals(Occupancy.MEDIO, OccupancyParser.parse("medio"))
        assertEquals(Occupancy.LLENO, OccupancyParser.parse("LLENO"))
        assertNotEquals(Occupancy.VACIO, OccupancyParser.parse(""))
    }

    @Test
    fun nfcDestinoAndFoldSearch() {
        val nfd = "Alcalá"
        assertEquals("Alcalá", TextNorm.nfc(nfd))
        assertEquals("leon xiii", TextNorm.fold("León XIII"))
        assertTrue(TextNorm.fold("Ricaurte - NQS").contains("ricaurte"))
    }

    @Test
    fun paraderoRejectsGtfsSuffix() {
        assertEquals("TM0101", Paradero.normalize("TM0101"))
        assertEquals("080A01", Paradero.normalize("080A01"))
        assertNull(Paradero.normalize("080A01_TM"))
        assertNull(Paradero.normalize(""))
        assertNull(Paradero.normalize("TM0101 extra"))
    }

    @Test
    fun etaUnknownLastAndPhraseOrder() {
        val arriving = arrival("E48", "Servicio llegando")
        val mid = arrival("K43", "Entre 2 y 4 min")
        val later = arrival("S43", "Entre 5 y 7 min")
        val unknown = arrival("Z8", "")
        val other = arrival("1", "sin dato")
        val sorted = ArrivalReducer.sort(listOf(later, unknown, arriving, other, mid))
        assertEquals(listOf("E48", "K43", "S43", "1", "Z8"), sorted.map { it.painted })
        assertTrue(EtaOrder.key("").bucket > EtaOrder.key("Entre 2 y 4 min").bucket)
    }

    @Test
    fun parseFilterDedupPreservesS43Variants() {
        val parser = ArrivalsParser(json)
        val body = javaClass.classLoader!!.getResource("arrivals-nfd.json")!!.readText()
        val rows = parser.parseBoard(body)
        assertEquals("Alcalá", rows.first { it.painted == "B23" }.destino)
        assertEquals(Occupancy.UNKNOWN, rows.first { it.painted == "B23" }.occupancy)
        assertEquals(Occupancy.MEDIO, rows.first { it.painted == "E48" && it.destino == "CAD" }.occupancy)
        assertNull(rows.first { it.painted == "K43" }.colorRuta)
        val selected = listOf(
            SelectedServicio("E48", "CAD", listOf(13266)),
            SelectedServicio("K43", "P. ElDorado", listOf(13276)),
            SelectedServicio("S43", "San Mateo", listOf(13277, 13278, 13279)),
        )
        val prepared = ArrivalReducer.prepare(rows, selected)
        assertEquals(1, prepared.count { it.painted == "S43" })
        assertTrue(prepared.none { it.painted == "B23" })
        assertTrue(prepared.none { it.destino == "Otra" })
        assertEquals(listOf("K43", "E48", "S43"), prepared.map { it.painted })
    }

    @Test
    fun catalogSearchNotHardCodedCommuteOnly() = runBlocking {
        val catalog = JsonCatalogRepository(json, gzip = false) {
            javaClass.classLoader!!.getResourceAsStream("catalog-mini.json")!!
        }
        val leon = catalog.search("leon")
        assertEquals("estacion:TM0101", leon.single().placeId)
        assertTrue(leon.single().servicios.any { it.painted == "E48" })
        val rica = catalog.search("ricaurte")
        assertEquals("estacion:TM0069", rica.single().placeId)
        val s43 = rica.single().servicios.single { it.painted == "S43" }
        assertEquals(listOf(13277, 13278, 13279), s43.routeIds)
        val zonal = catalog.search("liberia")
        assertEquals("paradero:080A01", zonal.single().placeId)
        assertTrue(catalog.search("x").isEmpty())
    }

    @Test
    fun cacheRetainedOnFailure() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "tm-cache-${System.nanoTime()}")
        val cache = FileArrivalsCache(dir, json)
        val clock = Clock { Instant.ofEpochMilli(1_700_000_000_000L) }
        var next: FetchResult = FetchResult.Success(
            listOf(arrival("E48", "Entre 2 y 4 min")),
            clock.now(),
        )
        val feed = object : FeedClient {
            override suspend fun fetchBoard(queryParadero: String): FetchResult = next
        }
        val repo = ArrivalsRepository(feed, cache, clock)
        val board = Board(
            boardId = "board-1",
            placeId = "estacion:TM0101",
            kind = PlaceKind.ESTACION,
            queryParadero = "TM0101",
            displayName = "León XIII",
            disambiguator = "Soacha",
            servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
        )
        val ok = repo.refresh(board) as FetchResult.Success
        assertEquals("E48", ok.arrivals.single().painted)
        next = FetchResult.Failure(FailureKind.HOST_REJECTED, null)
        val failed = repo.refresh(board) as FetchResult.Failure
        assertEquals(FailureKind.HOST_REJECTED, failed.kind)
        assertEquals("E48", failed.cached!!.arrivals.single().painted)
        assertEquals(ok.fetchedAt, failed.cached!!.fetchedAt)
    }

    @Test
    fun feedClientMaps403AndDoesNotSendOrigin() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403).setBody(""))
        server.start()
        try {
            val clock = Clock { Instant.ofEpochMilli(1L) }
            val client = BodegaFeedClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                appId = "test-appid-not-from-feed-md",
                installationId = "test-installation-id",
                parser = ArrivalsParser(json),
                clock = clock,
                client = BodegaFeedClient.httpClient(2000, 2000),
            )
            val result = runBlocking { client.fetchBoard("TM0101") } as FetchResult.Failure
            assertEquals(FailureKind.HOST_REJECTED, result.kind)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/paradero/buses", recorded.path)
            assertEquals("test-appid-not-from-feed-md", recorded.getHeader("appid"))
            assertEquals("test-installation-id", recorded.getHeader("uuid"))
            assertNull(recorded.getHeader("Origin"))
            assertTrue(recorded.body.readUtf8().contains("\"paradero\":\"TM0101\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun feedClientParsesDocumentedBoardSample() {
        val server = MockWebServer()
        val body = javaClass.classLoader!!.getResource("arrivals-080A01.json")!!.readText()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json"))
        server.start()
        try {
            val client = BodegaFeedClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                appId = "test-appid-not-from-feed-md",
                installationId = "test-installation-id",
                parser = ArrivalsParser(json),
                clock = Clock { Instant.ofEpochMilli(2L) },
                client = BodegaFeedClient.httpClient(2000, 2000),
            )
            val result = runBlocking { client.fetchBoard("080A01") } as FetchResult.Success
            assertEquals("B419", result.arrivals.single().painted)
            assertEquals("Servicio llegando", result.arrivals.single().etaPhrase)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun saveKeepsExistingBoardIndex() {
        val leon = Board(
            boardId = "leon",
            placeId = "estacion:TM0101",
            kind = PlaceKind.ESTACION,
            queryParadero = "TM0101",
            displayName = "León XIII",
            disambiguator = "Soacha",
            servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
        )
        val ricaurte = Board(
            boardId = "ricaurte",
            placeId = "estacion:TM0069",
            kind = PlaceKind.ESTACION,
            queryParadero = "TM0069",
            displayName = "Ricaurte - NQS",
            disambiguator = "NQS",
            servicios = listOf(SelectedServicio("S43", "San Mateo", listOf(13277))),
        )
        val edited = leon.copy(servicios = listOf(SelectedServicio("K43", "P. ElDorado", listOf(13276))))
        val snap = BoardSnapshot(listOf(leon, ricaurte), lastBoardId = "leon")
        val next = snap.saving(edited)
        assertEquals(listOf("leon", "ricaurte"), next.boards.map { it.boardId })
        assertEquals("K43", next.boards.first().servicios.single().painted)
        assertEquals("leon", next.lastBoardId)
        val added = snap.saving(leon.copy(boardId = "other", displayName = "Otro"))
        assertEquals(listOf("leon", "ricaurte", "other"), added.boards.map { it.boardId })
    }

    @Test
    fun packedGzipCatalogIncludesCommutePlaces() = runBlocking {
        val asset = listOf(
            File("src/main/assets/catalog.json.gz"),
            File("app/src/main/assets/catalog.json.gz"),
        ).firstOrNull { it.isFile }
        assertTrue("packed catalog.json.gz missing", asset != null)
        val catalog = JsonCatalogRepository(json, gzip = true) { asset!!.inputStream() }
        val leon = catalog.search("leon xiii").single { it.placeId == "estacion:TM0101" }
        assertEquals("TM0101", leon.queryParadero)
        val rica = catalog.search("ricaurte").first { it.placeId == "estacion:TM0069" }
        val s43 = rica.servicios.single { it.painted == "S43" }
        assertEquals(listOf(13277, 13278, 13279), s43.routeIds)
        assertTrue(catalog.search("12003").none { it.queryParadero == "12003" })
        assertTrue(catalog.place("estacion:TM0101") != null)
    }

    private fun arrival(painted: String, phrase: String) = Arrival(
        painted = painted,
        destino = "CAD",
        routeId = 13266,
        nodo = 1,
        etaPhrase = phrase,
        distanceLabel = null,
        occupancy = Occupancy.UNKNOWN,
        colorRuta = null,
        colorTexto = null,
    )
}
