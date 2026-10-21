package co.camilo.transmicrone

import co.camilo.transmicrone.data.feed.ArrivalsParser
import co.camilo.transmicrone.data.feed.BodegaFeedClient
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    fun everySelectedVariantCanStartBeforeAnyResponseFinishes() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(4)
        val blockedByCap = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                started.countDown()
                if (!started.await(3, java.util.concurrent.TimeUnit.SECONDS)) blockedByCap.set(true)
                return MockResponse().setBody("[]")
            }
        }
        server.start()
        val http = BodegaFeedClient.httpClient(5000, 5000)
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, http)
            val services = listOf(board.servicios.single().copy(routeIds = listOf(13277, 13278, 13279)),
                SelectedServicio("S48", "San Mateo", listOf(13267)))
            assertTrue(client.fetchArrivals(board.copy(servicios = services)) is FetchResult.Empty)
            assertEquals(4, server.requestCount)
            assertFalse("All four requests must reach the host before it releases any response", blockedByCap.get())
        } finally {
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun fastServicePublishesBeforeSlowVariantAndVariantFailureRemainsServiceAtomic() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = java.util.concurrent.CountDownLatch(1)
        val server = MockWebServer()
        val fixture = javaClass.classLoader!!.getResource("nearby-S43.json")!!.readText()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val query = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return if (query["ruta"]!!.jsonPrimitive.content == "S48") {
                    MockResponse().setBody("""[{"vehicleid":42,"ruta_sae":13267,"ruta_extraida":"S48","destino_limpio":"San Mateo","labeltiempo":"Entre 2 y 4 min"}]""")
                } else if (query["idRuta"]!!.jsonPrimitive.int == 13279) {
                    blocked.countDown()
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    MockResponse().setResponseCode(403)
                } else MockResponse().setBody(fixture)
            }
        }
        server.start()
        val http = BodegaFeedClient.httpClient(2000, 6000)
        val first = CompletableDeferred<ServiceFetchResult>()
        val seen = mutableListOf<ServiceFetchResult>()
        val services = listOf(board.servicios.single().copy(routeIds = listOf(13277, 13278, 13279)),
            SelectedServicio("S48", "San Mateo", listOf(13267)))
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, http)
            val job = launch {
                client.updates(board.copy(servicios = services)).collect {
                    seen.add(it)
                    first.complete(it)
                }
            }
            val ready = withTimeout(4000) { first.await() }
            assertEquals(services[1].serviceKey(), ready.key)
            assertTrue(ready.result is FetchResult.Success)
            assertTrue(blocked.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("No partial S43 result may be published before its last variant", 1, seen.size)
            release.countDown()
            withTimeout(4000) { job.join() }
            assertEquals(4, server.requestCount)
            assertEquals(2, seen.size)
            assertEquals(services[0].serviceKey(), seen.last().key)
            assertEquals(FailureKind.HOST_REJECTED, (seen.last().result as FetchResult.Failure).kind)
        } finally {
            release.countDown()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun realCompositeSelectionRequestsAndKeepsObservedZ63Vehicles() = runBlocking {
        val packed = co.camilo.transmicrone.data.catalog.JsonCatalogRepository(json, true) {
            java.io.File("src/main/assets/catalog.json.gz").inputStream()
        }
        val place = packed.place("estacion:TM90009")!!
        val option = place.servicios.single { it.painted == "FZ63" }
        assertEquals(listOf(13789), option.routeIds)
        val composite = board.copy(queryParadero = place.queryParadero,
            servicios = listOf(SelectedServicio(option.painted, option.destino, option.routeIds)))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(javaClass.classLoader!!.getResource("nearby-Z63.json")!!.readText()))
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val result = client.fetchArrivals(composite)
            assertTrue("The real FZ63 selection must retain the three Z63 vehicles", result is FetchResult.Success)
            assertEquals(3, (result as FetchResult.Success).arrivals.size)
            assertEquals(3, ArrivalReducer.prepare(result.arrivals, composite.servicios).size)
            val body = json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("Z63", body["ruta"]!!.jsonPrimitive.content)
            assertEquals("Tibanica", body["Nombre"]!!.jsonPrimitive.content)
            assertEquals(13789, body["idRuta"]!!.jsonPrimitive.int)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun compositeAndOrdinarySelectionDeduplicateAndUnverifiedIdsStayUnsupported() = runBlocking {
        val composite = SelectedServicio("FZ63", "F63 Pradera || Z63 Tibanica", listOf(13789))
        val server = MockWebServer()
        val fixture = javaClass.classLoader!!.getResource("nearby-Z63.json")!!.readText()
        server.enqueue(MockResponse().setBody(fixture))
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val services = listOf(composite, SelectedServicio("Z63", "Tibanica", listOf(13789)))
            val result = client.fetchArrivals(board.copy(servicios = services)) as FetchResult.Success
            assertEquals(3, ArrivalReducer.prepare(result.arrivals + result.arrivals, services).size)
            assertEquals(1, server.requestCount)
            assertTrue(client.fetchArrivals(board.copy(servicios = listOf(composite.copy(routeIds = listOf(13789, 99999))))) is FetchResult.Unsupported)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun stopSummaryUsesTheSameCompositeGuardsAndKeepsSavedSelection() = runBlocking {
        val selected = SelectedServicio("FZ63", "F63 Pradera || Z63 Tibanica", listOf(13789))
        val stop = board.copy(kind = PlaceKind.PARADERO, queryParadero = "080A01", servicios = listOf(selected))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""[
            {"ruta_extraida":"Z63","destino_limpio":"Tibanica","ruta_sae":13789,"labeltiempo":"Servicio llegando"},
            {"ruta_extraida":"Z63","destino_limpio":"Pradera","ruta_sae":13789,"labeltiempo":"Servicio llegando"},
            {"ruta_extraida":"F63","destino_limpio":"Pradera","ruta_sae":13789,"labeltiempo":"Servicio llegando"},
            {"ruta_extraida":"Z63","destino_limpio":"Tibanica","ruta_sae":13168,"labeltiempo":"Servicio llegando"}
        ]"""))
        server.start()
        val dir = java.nio.file.Files.createTempDirectory("composite-summary").toFile()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val repository = co.camilo.transmicrone.data.ArrivalsRepository(client,
                co.camilo.transmicrone.data.local.FileArrivalsCache(dir, json))
            val result = repository.refreshUpdates(stop).last()
            assertEquals(1, result.arrivals.size)
            assertEquals("Z63", result.arrivals.single().painted)
            assertEquals("/paradero/buses", server.takeRequest().path)
            assertEquals(selected, stop.servicios.single())
            assertEquals(result.arrivals, repository.cached(stop)!!.arrivals)
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

    @Test
    fun compositeSuccessAndAnotherVariantFailureRemainAFailure() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(javaClass.classLoader!!.getResource("nearby-Z63.json")!!.readText()))
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, BodegaFeedClient.httpClient(2000, 2000))
            val services = listOf(SelectedServicio("FZ63", "F63 Pradera || Z63 Tibanica", listOf(13789)), board.servicios.single())
            val result = client.fetchArrivals(board.copy(servicios = services)) as FetchResult.Failure
            assertEquals(FailureKind.HOST_REJECTED, result.kind)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

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
            cache.write("test", CachedArrivals(rows, now))
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
    fun callTimeoutEmitsFailureForItsServiceWhileOtherServiceFinishes() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val query = json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return if (query["ruta"]!!.jsonPrimitive.content == "S43") {
                    MockResponse().setHeadersDelay(1000, java.util.concurrent.TimeUnit.MILLISECONDS).setBody("[]")
                } else MockResponse().setHeadersDelay(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .setBody("""[{"vehicleid":42,"ruta_sae":13267,"ruta_extraida":"S48","destino_limpio":"San Mateo"}]""")
            }
        }
        server.start()
        val base = BodegaFeedClient.httpClient(1000, 5000)
        val http = object : okhttp3.OkHttpClient() {
            override fun newCall(request: okhttp3.Request): okhttp3.Call {
                val buffer = okio.Buffer()
                request.body!!.writeTo(buffer)
                val code = json.parseToJsonElement(buffer.readUtf8()).jsonObject["ruta"]!!.jsonPrimitive.content
                return base.newCall(request).also {
                    it.timeout().timeout(if (code == "S43") 100 else 2000, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, http)
            val services = listOf(board.servicios.single(), SelectedServicio("S48", "San Mateo", listOf(13267)))
            val results = withTimeout(4000) { client.updates(board.copy(servicios = services)).toList() }
            assertEquals("Every selected service must finish, including the call that OkHttp cancels on timeout", 2, results.size)
            assertEquals(FailureKind.TIMEOUT,
                (results.single { it.key == services[0].serviceKey() }.result as FetchResult.Failure).kind)
            assertTrue(results.single { it.key == services[1].serviceKey() }.result is FetchResult.Success)
        } finally {
            base.connectionPool.evictAll()
            base.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test
    fun brokenVehicleBodyMustFailInsteadOfClearingServiceAsEmpty() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(javaClass.classLoader!!.getResource("nearby-S43.json")!!.readText())
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        server.start()
        val http = BodegaFeedClient.httpClient(1000, 1000)
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test-appid", "test-installation",
                ArrivalsParser(json), Clock { now }, http)
            val result = client.fetchArrivals(board)
            assertTrue("An interrupted response body is a failed service, never an empty success", result is FetchResult.Failure)
        } finally {
            http.connectionPool.evictAll()
            server.shutdown()
        }
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
