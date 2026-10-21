package co.camilo.transmicrone

import androidx.test.platform.app.InstrumentationRegistry
import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import co.camilo.transmicrone.data.feed.ArrivalsParser
import co.camilo.transmicrone.data.feed.BodegaFeedClient
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.data.local.InstallationId
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in, four live calls. Does not write boards, settings, or the app's arrivals cache. */
class LiveServiceUpdatesTest {
    @Test
    fun selectedServicesFinishIndependentlyOnTheLiveFeed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSmoke") == "1")
        check(!BuildConfig.MOCK_FEED && BuildConfig.FEED_APPID.isNotBlank() && BuildConfig.FEED_APPID != "unconfigured")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json { ignoreUnknownKeys = true }
        val catalog = JsonCatalogRepository(json, gzip = false) { context.assets.open("catalog.json") }
        val place = catalog.place("estacion:TM0069")!!
        val services = place.servicios.filter { it.painted in setOf("S43", "S48") }
            .map { SelectedServicio(it.painted, it.destino, it.routeIds, it.color) }
        assertEquals(2, services.size)
        assertEquals(4, services.sumOf { it.routeIds.size })
        val board = Board("live-smoke", place.placeId, place.kind, place.queryParadero,
            place.name, place.disambiguator, services)
        val http = BodegaFeedClient.httpClient(BuildConfig.HTTP_CONNECT_TIMEOUT_MS, BuildConfig.HTTP_READ_TIMEOUT_MS)
        val feed = BodegaFeedClient(BuildConfig.FEED_BASE_URL, BuildConfig.FEED_APPID,
            InstallationId.getOrCreate(context), ArrivalsParser(json), SystemClock, http)
        val directory = File(context.cacheDir, "service-smoke-${System.nanoTime()}")
        val repo = ArrivalsRepository(feed, FileArrivalsCache(directory, json))
        val finished = mutableSetOf<ServiceKey>()
        var last: BoardArrivals? = null
        val start = System.nanoTime()
        try {
            withTimeout(35000) {
                repo.refreshUpdates(board).collect { snapshot ->
                    last = snapshot
                    snapshot.services.filter { it.status != ServiceStatus.Pending }.forEach {
                        if (finished.add(it.selection.serviceKey())) {
                            println("LIVE_SERVICE ${it.selection.painted} ms=${(System.nanoTime() - start) / 1e6} status=${it.status} arrivals=${it.arrivals.size}")
                        }
                    }
                }
            }
            assertEquals(services.map { it.serviceKey() }.toSet(), finished)
            assertFalse(last!!.pending)
            val restored = repo.cachedState(board)
            assertEquals(last!!.services.map { it.cached?.arrivals }, restored.services.map { it.cached?.arrivals })
            assertEquals(last!!.services.map { it.cached?.fetchedAt?.toEpochMilli() },
                restored.services.map { it.cached?.fetchedAt?.toEpochMilli() })
            println("LIVE_SERVICE_BOARD ms=${(System.nanoTime() - start) / 1e6} arrivals=${last!!.arrivals.size}")
        } finally {
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
            directory.deleteRecursively()
        }
    }
}
