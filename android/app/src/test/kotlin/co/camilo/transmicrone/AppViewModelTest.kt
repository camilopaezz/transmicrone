package co.camilo.transmicrone

import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.CatalogRepository
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.data.local.BoardRepository
import co.camilo.transmicrone.data.local.BoardSnapshot
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.data.local.saving
import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CachedArrivals
import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.FetchResult
import co.camilo.transmicrone.domain.Occupancy
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.TextNorm
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import co.camilo.transmicrone.ui.AppViewModel
import co.camilo.transmicrone.ui.Freshness
import co.camilo.transmicrone.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val clock = Clock { Instant.ofEpochMilli(1_700_000_000_000L) }
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun reset() {
        stores.forEach { it.clear() }
        stores.clear()
        Dispatchers.resetMain()
    }

    private fun vmTest(body: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                body()
            } finally {
                stores.forEach { it.clear() }
                stores.clear()
            }
        }

    @Test
    fun coldStartDoesNotTripleFetchSamePage() = vmTest {
        val feed = ScriptedFeed()
        val boards = MemoryBoards(listOf(leonBoard(), ricaurteBoard()))
        val vm = viewModel(feed, boards)
        runCurrent()
        vm.setStarted(true)
        vm.onPage(0)
        boards.setLastBoard("leon")
        runCurrent()
        assertEquals(listOf("TM0101"), feed.calls)
    }

    @Test
    fun olderInFlightResultDoesNotReplaceNewer() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(5_000, FetchResult.Failure(FailureKind.TIMEOUT, null))
        feed.enqueue(10, FetchResult.Success(listOf(arrival("S43", "Entre 2 y 4 min")), clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        assertEquals(1, feed.calls.size)
        vm.onPage(1)
        advanceTimeBy(10)
        runCurrent()
        val view = vm.state.value.boards[1]
        assertTrue(view.freshness is Freshness.Updated)
        assertEquals("S43", view.arrivals.single().painted)
        advanceTimeBy(5_000)
        runCurrent()
        val after = vm.state.value.boards[1]
        assertTrue(after.freshness is Freshness.Updated)
        assertFalse(after.freshness is Freshness.FailedCached)
        assertEquals("S43", after.arrivals.single().painted)
    }

    @Test
    fun setupStopsPollingHiddenBoard() = vmTest {
        val feed = ScriptedFeed()
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        val afterStart = feed.calls.size
        assertTrue(afterStart >= 1)
        vm.openSetup(null)
        runCurrent()
        assertEquals(Screen.SETUP, vm.state.value.screen)
        advanceTimeBy(BuildConfig.AUTO_REFRESH_MS * 2)
        runCurrent()
        assertEquals(afterStart, feed.calls.size)
    }

    @Test
    fun stoppedCancelsInFlightApply() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(30_000, FetchResult.Success(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()))
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "tm-vm-${System.nanoTime()}")
        val cache = FileArrivalsCache(cacheDir, json)
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())), cache)
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.setStarted(false)
        advanceTimeBy(30_000)
        runCurrent()
        val freshness = vm.state.value.boards.single().freshness
        assertFalse(freshness is Freshness.Updated)
    }

    @Test
    fun diskCacheIsStaleUntilThisProcessRefresh() = vmTest {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "tm-vm-${System.nanoTime()}")
        val cache = FileArrivalsCache(cacheDir, json)
        cache.write(
            "leon",
            CachedArrivals(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now(), emptySuccess = false),
        )
        val feed = ScriptedFeed()
        feed.enqueue(60_000, FetchResult.Success(listOf(arrival("E48", "Entre 5 y 7 min")), clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())), cache)
        runCurrent()
        val restored = vm.state.value.boards.single()
        assertTrue(restored.freshness is Freshness.Cached)
        assertEquals("E48", restored.arrivals.single().painted)
        assertFalse(restored.freshness is Freshness.Updated)
    }

    @Test
    fun searchKeepsLatestQueryAndWaitsForCatalog() = vmTest {
        val catalog = FakeCatalog(
            places = listOf(leonPlace(), ricaurtePlace()),
            generatedAtDelayMs = 5_000,
            searchDelays = mapOf("ricaurte" to 2_000L, "leon" to 1L),
        )
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = catalog)
        vm.openSetup(null)
        vm.onSearch("ricaurte")
        assertTrue(vm.state.value.setupSearching)
        assertFalse(vm.state.value.catalogReady)
        vm.onSearch("leon")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("leon", vm.state.value.setupQuery)
        assertEquals(listOf("estacion:TM0101"), vm.state.value.setupResults.map { it.placeId })
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("estacion:TM0101"), vm.state.value.setupResults.map { it.placeId })
        assertFalse(vm.state.value.setupSearching)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(vm.state.value.catalogReady)
    }

    @Test
    fun failedRefreshKeepsCacheAndKind() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Success(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()))
        feed.enqueue(0, FetchResult.Failure(FailureKind.HOST_REJECTED, null))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        assertTrue(vm.state.value.boards.single().freshness is Freshness.Updated)
        vm.manualRefresh()
        runCurrent()
        val failed = vm.state.value.boards.single().freshness as Freshness.FailedCached
        assertEquals(FailureKind.HOST_REJECTED, failed.kind)
        assertEquals("E48", vm.state.value.boards.single().arrivals.single().painted)
    }

    private fun viewModel(
        feed: FeedClient,
        boards: BoardRepository,
        cache: ArrivalsCache = FileArrivalsCache(
            File(System.getProperty("java.io.tmpdir"), "tm-vm-${System.nanoTime()}"),
            json,
        ),
        catalog: CatalogRepository = FakeCatalog(listOf(leonPlace(), ricaurtePlace())),
    ): AppViewModel {
        val store = ViewModelStore()
        stores += store
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppViewModel(
                    catalog = catalog,
                    boards = boards,
                    arrivals = ArrivalsRepository(feed, cache, clock),
                    clock = clock,
                    io = dispatcher,
                    compute = dispatcher,
                ) as T
            }
        }
        return ViewModelProvider(store, factory)[AppViewModel::class.java]
    }

    private fun leonBoard() = Board(
        boardId = "leon",
        placeId = "estacion:TM0101",
        kind = PlaceKind.ESTACION,
        queryParadero = "TM0101",
        displayName = "León XIII",
        disambiguator = "Soacha",
        servicios = listOf(SelectedServicio("E48", "CAD", listOf(13266))),
    )

    private fun ricaurteBoard() = Board(
        boardId = "ricaurte",
        placeId = "estacion:TM0069",
        kind = PlaceKind.ESTACION,
        queryParadero = "TM0069",
        displayName = "Ricaurte - NQS",
        disambiguator = "NQS",
        servicios = listOf(SelectedServicio("S43", "San Mateo", listOf(13277, 13278, 13279))),
    )

    private fun leonPlace() = Place(
        placeId = "estacion:TM0101",
        kind = PlaceKind.ESTACION,
        name = "León XIII",
        fold = "leon xiii soacha",
        disambiguator = "Soacha",
        queryParadero = "TM0101",
        servicios = listOf(ServicioOption("E48", "CAD", listOf(13266), null)),
    )

    private fun ricaurtePlace() = Place(
        placeId = "estacion:TM0069",
        kind = PlaceKind.ESTACION,
        name = "Ricaurte - NQS",
        fold = "ricaurte - nqs nqs",
        disambiguator = "NQS",
        queryParadero = "TM0069",
        servicios = listOf(ServicioOption("S43", "San Mateo", listOf(13277, 13278, 13279), null)),
    )

    private fun arrival(painted: String, phrase: String) = Arrival(
        painted = painted,
        destino = if (painted == "S43") "San Mateo" else "CAD",
        routeId = if (painted == "S43") 13277 else 13266,
        nodo = 1,
        etaPhrase = phrase,
        distanceLabel = null,
        occupancy = Occupancy.UNKNOWN,
        colorRuta = null,
        colorTexto = null,
    )
}

private class MemoryBoards(initial: List<Board> = emptyList()) : BoardRepository {
    private val state = MutableStateFlow(
        BoardSnapshot(initial, initial.firstOrNull()?.boardId),
    )

    override fun observe(): Flow<BoardSnapshot> = state
    override suspend fun snapshot(): BoardSnapshot = state.value
    override suspend fun save(board: Board) {
        state.value = state.value.saving(board)
    }
    override suspend fun delete(boardId: String) {
        val boards = state.value.boards.filterNot { it.boardId == boardId }
        state.value = state.value.copy(boards = boards, lastBoardId = boards.firstOrNull()?.boardId)
    }
    override suspend fun reorder(boardIds: List<String>) {
        val map = state.value.boards.associateBy { it.boardId }
        state.value = state.value.copy(boards = boardIds.mapNotNull { map[it] })
    }
    override suspend fun setLastBoard(boardId: String?) {
        state.value = state.value.copy(lastBoardId = boardId)
    }
    override fun newId(): String = UUID.randomUUID().toString()
}

private class ScriptedFeed : FeedClient {
    private val steps = ArrayDeque<Pair<Long, FetchResult>>()
    val calls = mutableListOf<String>()
    var default: FetchResult = FetchResult.Failure(FailureKind.HOST_REJECTED, null)

    fun enqueue(delayMs: Long, result: FetchResult) {
        steps.addLast(delayMs to result)
    }

    override suspend fun fetchBoard(queryParadero: String): FetchResult {
        calls += queryParadero
        val step = steps.removeFirstOrNull() ?: (0L to default)
        delay(step.first)
        return step.second
    }
}

private class FakeCatalog(
    private val places: List<Place>,
    var generatedAtDelayMs: Long = 0,
    var searchDelays: Map<String, Long> = emptyMap(),
) : CatalogRepository {
    override suspend fun search(query: String, limit: Int): List<Place> {
        val key = query.trim().lowercase()
        delay(searchDelays[key] ?: 0)
        val q = TextNorm.fold(query).trim()
        if (q.length < 2) return emptyList()
        return places.filter { it.fold.contains(q) }.take(limit)
    }

    override fun place(placeId: String): Place? = places.find { it.placeId == placeId }

    override suspend fun generatedAt(): String? {
        delay(generatedAtDelayMs)
        return "test"
    }
}
