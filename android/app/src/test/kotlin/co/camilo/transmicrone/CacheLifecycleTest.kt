package co.camilo.transmicrone

import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class CacheLifecycleTest {
    @Test
    fun deletedBoardCacheIsNotRecreatedByAnInflightFetchAndUndoCanFetchAgain() = runBlocking {
        val dir = Files.createTempDirectory("arrivals-delete").toFile()
        try {
            val cache = FileArrivalsCache(dir, Json { ignoreUnknownKeys = true })
            val now = Instant.EPOCH
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<FetchResult>()
            var calls = 0
            val feed = object : FeedClient {
                override suspend fun fetchBoard(queryParadero: String): FetchResult {
                    if (++calls > 1) return FetchResult.Empty(now)
                    entered.complete(Unit)
                    return response.await()
                }
            }
            val board = Board("saved-board", "estacion:TM0069", PlaceKind.ESTACION,
                "TM0069", "Ricaurte", null, listOf(SelectedServicio("S43", "San Mateo", emptyList())))
            val repo = ArrivalsRepository(feed, cache)
            cache.write(board.boardId, CachedArrivals(emptyList(), now))
            val pending = async { repo.refreshUpdates(board).toList() }
            entered.await()
            repo.remove(board.boardId)
            assertNull(cache.read(board.boardId))
            response.complete(FetchResult.Empty(now))
            pending.await()
            assertNull(cache.read(board.boardId))
            repo.refreshUpdates(board).toList()
            assertNotNull(cache.read(board.boardId))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyCacheFieldsAreIgnoredAndArrivalDataSurvives() {
        val dir = Files.createTempDirectory("arrivals-legacy-cache").toFile()
        try {
            val cache = FileArrivalsCache(dir, Json)
            val legacy = """{"fetchedAtEpochMs":0,"emptySuccess":true,"arrivals":[{"painted":"E48","destino":"CAD","routeId":13266,"nodo":62282,"etaPhrase":"Entre 2 y 4 min","distanceLabel":"200 m","occupancy":"UNKNOWN","colorRuta":"#AE6B00","colorTexto":"#FFFFFF"}]}"""
            dir.resolve("saved-board.json").writeText(legacy)

            val restored = cache.read("saved-board")!!
            assertEquals(Instant.EPOCH, restored.fetchedAt)
            assertEquals(1, restored.arrivals.size)
            assertEquals("E48", restored.arrivals.single().painted)
            assertEquals("#AE6B00", restored.arrivals.single().colorRuta)
        } finally {
            dir.deleteRecursively()
        }
    }
}
