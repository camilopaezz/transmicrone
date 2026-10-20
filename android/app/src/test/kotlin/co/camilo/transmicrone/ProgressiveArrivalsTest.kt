package co.camilo.transmicrone

import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressiveArrivalsTest {
    private val old = Instant.parse("2026-10-16T12:00:00Z")
    private val now = old.plusSeconds(25)
    private val fast = SelectedServicio("S48", "San Mateo", listOf(13267))
    private val slow = SelectedServicio("S43", "San Mateo", listOf(13277, 13278, 13279))
    private val board = Board("saved-board", "estacion:TM0069", PlaceKind.ESTACION,
        "TM0069", "Ricaurte", null, listOf(fast, slow))
    private fun row(service: SelectedServicio, phrase: String) = Arrival(service.painted, service.destino,
        service.routeIds.first(), null, phrase, "500 m", Occupancy.UNKNOWN, null)

    private fun feed(response: CompletableDeferred<FetchResult>, first: FetchResult) = object : FeedClient {
        override suspend fun fetchBoard(queryParadero: String): FetchResult = error("Must use service updates")
        override fun updates(board: Board): Flow<ServiceFetchResult> = flow {
            emit(ServiceFetchResult(fast.serviceKey(), first))
            emit(ServiceFetchResult(slow.serviceKey(), response.await()))
        }
    }

    @Test
    fun fastServiceAppearsAndIsPersistedBeforeSlowFailureWithoutChangingItsCacheAge() = runTest {
        val dir = Files.createTempDirectory("progressive-arrivals").toFile()
        try {
            val cache = FileArrivalsCache(dir, Json)
            cache.write(board.boardId, CachedArrivals(listOf(row(fast, "old-fast"), row(slow, "old-slow")), old))
            val response = CompletableDeferred<FetchResult>()
            val repo = ArrivalsRepository(feed(response, FetchResult.Success(listOf(row(fast, "new-fast")), now)), cache)
            val seen = mutableListOf<BoardArrivals>()
            val job = launch { repo.refreshUpdates(board).toList(seen) }
            runCurrent()
            assertTrue(job.isActive)
            val partial = seen.last()
            assertEquals(ServiceStatus.Updated, partial.services[0].status)
            assertEquals(ServiceStatus.Pending, partial.services[1].status)
            assertEquals(listOf("new-fast", "old-slow"), partial.services.map { it.arrivals.single().etaPhrase })
            val restored = ArrivalsRepository(feed(response, FetchResult.Empty(now)), FileArrivalsCache(dir, Json)).cachedState(board)
            assertEquals(listOf(now, old), restored.services.map { it.cached!!.fetchedAt })
            response.complete(FetchResult.Failure(FailureKind.TIMEOUT, null))
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(ServiceStatus.Failed(FailureKind.TIMEOUT), seen.last().services[1].status)
            assertEquals(old, seen.last().services[1].cached!!.fetchedAt)
            assertEquals("new-fast", seen.last().services[0].arrivals.single().etaPhrase)
            assertEquals(seen.last().services.map { it.cached }, repo.cachedState(board).services.map { it.cached })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun emptyResultClearsOnlyItsServiceAndSurvivesCancellationAndReload() = runTest {
        val dir = Files.createTempDirectory("progressive-empty").toFile()
        try {
            val cache = FileArrivalsCache(dir, Json)
            cache.write(board.boardId, CachedArrivals(listOf(row(fast, "old-fast"), row(slow, "old-slow")), old))
            val response = CompletableDeferred<FetchResult>()
            val repo = ArrivalsRepository(feed(response, FetchResult.Empty(now)), cache)
            val seen = mutableListOf<BoardArrivals>()
            val job = launch { repo.refreshUpdates(board).toList(seen) }
            runCurrent()
            assertTrue(seen.last().services[0].arrivals.isEmpty())
            assertEquals(ServiceStatus.Empty, seen.last().services[0].status)
            assertEquals("old-slow", seen.last().arrivals.single().etaPhrase)
            job.cancelAndJoin()
            val restored = repo.cachedState(board)
            assertEquals(now, restored.services[0].cached!!.fetchedAt)
            assertEquals(ServiceStatus.CachedEmpty, restored.services[0].status)
            assertTrue(restored.services[0].arrivals.isEmpty())
            assertEquals(old, restored.services[1].cached!!.fetchedAt)
            assertTrue(repo.cachedState(board.copy(queryParadero = "TM0002")).services.all { it.cached == null })
            val unqueried = repo.cachedState(board.copy(servicios = listOf(fast.copy(routeIds = listOf(999))))).services.single()
            assertNull(unqueried.cached)
            assertEquals(ServiceStatus.Idle, unqueried.status)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun legacyCacheIsBoundToItsOriginalQueryBeforeAnyRefreshOrEdit() {
        val dir = Files.createTempDirectory("progressive-legacy").toFile()
        try {
            val cache = FileArrivalsCache(dir, Json)
            cache.write(board.boardId, CachedArrivals(listOf(row(fast, "old")), old))
            val repo = ArrivalsRepository(feed(CompletableDeferred(), FetchResult.Empty(now)), cache)
            assertEquals(old, repo.cachedState(board).services[0].cached!!.fetchedAt)
            assertEquals(board.queryScope(), cache.read(board.boardId)!!.queryScope)
            assertNull(repo.cachedState(board.copy(queryParadero = "TM0002")).cache())
            assertNull(repo.cachedState(board.copy(servicios = listOf(fast.copy(routeIds = listOf(999))))).cache())
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun deletedBoardIsNotRecreatedByLaterServiceResults() = runTest {
        val dir = Files.createTempDirectory("progressive-delete").toFile()
        try {
            val response = CompletableDeferred<FetchResult>()
            val cache = FileArrivalsCache(dir, Json)
            val repo = ArrivalsRepository(feed(response, FetchResult.Success(listOf(row(fast, "new")), now)), cache)
            val seen = mutableListOf<BoardArrivals>()
            val job = launch { repo.refreshUpdates(board).toList(seen) }
            runCurrent()
            assertNotNull(cache.read(board.boardId))
            val before = seen.size
            repo.remove(board.boardId)
            response.complete(FetchResult.Success(listOf(row(slow, "late")), now))
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(before, seen.size)
            assertNull(cache.read(board.boardId))
        } finally { dir.deleteRecursively() }
    }
}
