package co.camilo.transmicrone

import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.CatalogRepository
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.data.local.moving
import co.camilo.transmicrone.data.local.BoardRepository
import co.camilo.transmicrone.data.local.BoardSnapshot
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.data.local.saving
import co.camilo.transmicrone.data.local.restoringDeleted
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.domain.Arrival
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CompletableDeferred
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
import co.camilo.transmicrone.ui.key
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
import org.junit.Assert.assertNull
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
    fun catalogDetailsReturnThroughHistoryAndKeepSearchQuery() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.openSearch()
        vm.searchCatalog("leon")
        advanceTimeBy(151)
        runCurrent()
        val place = vm.state.value.catalogResults.places.single()
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        vm.openService(place.servicios.first())
        runCurrent()
        assertEquals(2, vm.state.value.details.size)
        vm.backSetup()
        assertEquals(Screen.DETAILS, vm.state.value.screen)
        vm.backSetup()
        assertEquals(Screen.SEARCH, vm.state.value.screen)
        assertEquals("leon", vm.state.value.catalogQuery)
        assertEquals(listOf(place), vm.state.value.catalogResults.places)
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        vm.createBoardFrom(place)
        assertEquals(Screen.SETUP, vm.state.value.screen)
        assertEquals(place, vm.state.value.setupPlace)
        assertTrue(vm.state.value.setupSelected.isEmpty())
    }

    @Test
    fun latestServiceTapWinsAndBackInvalidatesPendingLookup() = vmTest {
        val first = leonPlace().servicios.single()
        val second = first.copy(painted = "E42", destino = "7 de Agosto")
        val place = leonPlace().copy(servicios = listOf(first, second))
        val catalog = FakeCatalog(listOf(place), serviceDelays = mapOf("E48" to 1000L, "E42" to 100L))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = catalog)
        runCurrent()
        vm.openSearch()
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        vm.openService(first)
        runCurrent()
        vm.openService(second)
        advanceTimeBy(101)
        runCurrent()
        assertEquals(second, (vm.state.value.details.last() as co.camilo.transmicrone.domain.CatalogEntry.Service).option)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(2, vm.state.value.details.size)
        vm.backSetup()
        vm.openService(first)
        runCurrent()
        vm.backSetup()
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf(co.camilo.transmicrone.domain.CatalogEntry.Location(place)), vm.state.value.details)
    }

    @Test
    fun routeSelectionSurvivesOpeningStopAndReturning() = vmTest {
        val option = leonPlace().servicios.single()
        val routes = co.camilo.transmicrone.data.catalog.RouteRepository {
            listOf("one", "two").map { id -> co.camilo.transmicrone.domain.ServiceRoute(id,
                listOf(co.camilo.transmicrone.domain.RouteStop("TM0101", "León XIII"))) }
        }
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), routeRepository = routes)
        runCurrent()
        val entry = co.camilo.transmicrone.domain.CatalogEntry.Service(option, listOf(leonPlace()))
        vm.openDetails(entry)
        runCurrent()
        vm.selectRoute(option, 1)
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(leonPlace()))
        vm.backSetup()
        assertEquals(entry, vm.state.value.details.last())
        assertEquals(1, vm.state.value.selectedRoutes[option.key()])
        vm.selectRoute(option, 10)
        assertEquals(1, vm.state.value.selectedRoutes[option.key()])
    }

    @Test
    fun cancelBoardCreationReturnsToDetailsIncludingAfterChangingPlace() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards())
        runCurrent()
        vm.openSearch()
        val entry = co.camilo.transmicrone.domain.CatalogEntry.Location(leonPlace())
        vm.openDetails(entry)
        repeat(2) { attempt ->
            vm.createBoardFrom(leonPlace())
            if (attempt == 1) vm.changeSetupPlace()
            vm.backSetup()
            assertEquals(Screen.DETAILS, vm.state.value.screen)
            assertEquals(listOf(entry), vm.state.value.details)
            assertNull(vm.state.value.setupPlace)
        }
    }

    @Test
    fun catalogSearchDoesNotShowOldResultsAfterQueryChanges() = vmTest {
        val catalog = FakeCatalog(listOf(leonPlace(), ricaurtePlace()), searchDelays = mapOf("leon" to 1000L))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = catalog)
        runCurrent()
        vm.openSearch()
        vm.searchCatalog("leon")
        advanceTimeBy(200)
        vm.searchCatalog("ricaurte")
        advanceTimeBy(1200)
        runCurrent()
        assertEquals("Ricaurte - NQS", vm.state.value.catalogResults.places.single().name)
        vm.searchCatalog("")
        assertTrue(vm.state.value.catalogResults.places.isEmpty())
        assertFalse(vm.state.value.catalogSearching)
    }

    @Test
    fun failedRouteCanBeRetriedAndSuccessfulRouteKeepsStopOrder() = vmTest {
        var calls = 0
        val routes = co.camilo.transmicrone.data.catalog.RouteRepository {
            calls++
            if (calls == 1) throw java.io.IOException("offline")
            listOf(co.camilo.transmicrone.domain.ServiceRoute("12444", listOf(
                co.camilo.transmicrone.domain.RouteStop("TM0101", "León XIII"),
                co.camilo.transmicrone.domain.RouteStop("TM0069", "Ricaurte"),
            )))
        }
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), routeRepository = routes)
        runCurrent()
        val option = leonPlace().servicios.first()
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Service(option, listOf(leonPlace())))
        assertEquals(co.camilo.transmicrone.ui.RouteState.Loading, vm.state.value.routes[option.key()])
        runCurrent()
        assertEquals(co.camilo.transmicrone.ui.RouteState.Failed, vm.state.value.routes[option.key()])
        vm.loadRoute(option, retry = true)
        runCurrent()
        val loaded = vm.state.value.routes[option.key()] as co.camilo.transmicrone.ui.RouteState.Loaded
        assertEquals(listOf("TM0101", "TM0069"), loaded.routes.single().stops.map { it.code })
        assertEquals("estacion:TM0101", loaded.routes.single().stops.first().place!!.placeId)
        vm.loadRoute(option)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun staleRouteFailureCannotReplaceNewerRetrySuccess() = vmTest {
        var calls = 0
        val routes = co.camilo.transmicrone.data.catalog.RouteRepository {
            calls++
            if (calls == 1) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    delay(1_000)
                }
                throw java.io.IOException("stale failure")
            }
            listOf(co.camilo.transmicrone.domain.ServiceRoute("new", emptyList()))
        }
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), routeRepository = routes)
        runCurrent()
        val option = leonPlace().servicios.single()
        vm.loadRoute(option)
        runCurrent()
        vm.loadRoute(option, retry = true)
        runCurrent()
        assertTrue(vm.state.value.routes[option.key()] is co.camilo.transmicrone.ui.RouteState.Loaded)
        advanceTimeBy(1_000)
        runCurrent()
        val state = vm.state.value.routes[option.key()] as co.camilo.transmicrone.ui.RouteState.Loaded
        assertEquals("new", state.routes.single().catalogId)
    }

    @Test
    fun setupSearchFailureLeavesLoadingAndSetsErrorState() = vmTest {
        val catalog = FakeCatalog(listOf(leonPlace()), searchFailure = java.io.IOException("offline"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = catalog)
        runCurrent()
        vm.openSetup()
        vm.onSearch("leon")
        runCurrent()
        assertFalse(vm.state.value.setupSearching)
        assertTrue(vm.state.value.setupSearchFailed)
        assertTrue(vm.state.value.setupResults.isEmpty())
    }

    @Test
    fun invalidPlaceSaveKeepsSetupOpenAndSetsErrorState() = vmTest {
        val place = leonPlace().copy(queryParadero = "invalid code")
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = FakeCatalog(listOf(place)))
        runCurrent()
        vm.openSetup()
        vm.pickPlace(place)
        vm.toggleServicio(place.servicios.single().key())
        vm.saveSetup()
        runCurrent()
        assertEquals(Screen.SETUP, vm.state.value.screen)
        assertTrue(vm.state.value.setupSaveFailed)
        assertTrue(vm.state.value.boards.isEmpty())
    }

    @Test
    fun deletingBoardRemovesItsArrivalCache() = vmTest {
        val board = leonBoard()
        val cache = FileArrivalsCache(File(System.getProperty("java.io.tmpdir"), "tm-vm-${System.nanoTime()}"), json)
        cache.write(board.boardId, CachedArrivals(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(board)), cache)
        runCurrent()
        assertTrue(cache.read(board.boardId) != null)
        vm.deleteBoard(board.boardId)
        runCurrent()
        assertNull(cache.read(board.boardId))
    }

    @Test
    fun catalogLoadFailureDoesNotMarkCatalogReady() = vmTest {
        val catalog = FakeCatalog(listOf(leonPlace()), generatedAtFailure = java.io.IOException("offline"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = catalog)
        runCurrent()
        assertFalse(vm.state.value.catalogReady)
        assertTrue(vm.state.value.catalogLoadFailed)
        vm.openSetup()
        assertTrue(vm.state.value.catalogLoadFailed)
    }

    @Test
    fun editingAfterCatalogLoadFailureWaitsForPlaceLookup() = vmTest {
        val place = leonPlace()
        val board = leonBoard().copy(displayName = "Mi estación")
        val catalog = FakeCatalog(listOf(place), generatedAtFailure = java.io.IOException("offline"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(board)), catalog = catalog)
        runCurrent()
        assertFalse(vm.state.value.catalogReady)
        assertTrue(vm.state.value.catalogLoadFailed)

        vm.openSetup(board.boardId)
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertEquals(0, catalog.placeCalls)
        assertNull(vm.state.value.setupPlace)
        runCurrent()

        assertEquals(1, catalog.placeCalls)
        assertEquals(Screen.SETUP, vm.state.value.screen)
        assertEquals(board.boardId, vm.state.value.editingBoardId)
        assertEquals(place, vm.state.value.setupPlace)
        assertEquals(board.displayName, vm.state.value.setupCustomName)
        assertEquals(board.servicios.map { it.key() }.toSet(), vm.state.value.setupSelected)
        assertFalse(vm.state.value.setupSearchFailed)
        assertFalse(vm.state.value.setupSaveFailed)
        assertTrue(vm.state.value.catalogLoadFailed)
        assertFalse(vm.state.value.catalogReady)

        vm.changeSetupPlace()
        vm.pickPlace(place)
        assertEquals(board.servicios.map { it.key() }.toSet(), vm.state.value.setupSelected)
    }

    @Test
    fun editingAfterCatalogLoadFailureAppliesPlaceLookupFailure() = vmTest {
        val board = leonBoard().copy(displayName = "Mi estación")
        val catalog = FakeCatalog(
            listOf(leonPlace()),
            generatedAtFailure = java.io.IOException("offline"),
            placeFailure = java.io.IOException("broken catalog"),
        )
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(board)), catalog = catalog)
        runCurrent()
        assertFalse(vm.state.value.catalogReady)
        assertTrue(vm.state.value.catalogLoadFailed)

        vm.openSetup(board.boardId)
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertEquals(0, catalog.placeCalls)
        runCurrent()

        assertEquals(1, catalog.placeCalls)
        assertEquals(Screen.SETUP, vm.state.value.screen)
        assertEquals(board.boardId, vm.state.value.editingBoardId)
        assertNull(vm.state.value.setupPlace)
        assertEquals(board.displayName, vm.state.value.setupCustomName)
        assertEquals(board.servicios.map { it.key() }.toSet(), vm.state.value.setupSelected)
        assertTrue(vm.state.value.setupSearchFailed)
        assertFalse(vm.state.value.setupSaveFailed)
        assertTrue(vm.state.value.catalogLoadFailed)
        assertFalse(vm.state.value.catalogReady)
    }

    @Test
    fun delayedEditLookupDoesNotReplaceSearch() = vmTest {
        val catalog = FakeCatalog(listOf(leonPlace()), generatedAtFailure = java.io.IOException("offline"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(leonBoard())), catalog = catalog)
        runCurrent()

        vm.openSetup("leon")
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertEquals(0, catalog.placeCalls)
        vm.openSearch()
        val searchState = vm.state.value
        assertEquals(Screen.SEARCH, searchState.screen)
        runCurrent()

        assertEquals(1, catalog.placeCalls)
        assertEquals(searchState, vm.state.value)
    }

    @Test
    fun editingBoardSurvivesCatalogPlaceLookupFailure() = vmTest {
        val catalog = FakeCatalog(listOf(leonPlace()), placeFailure = java.io.IOException("broken catalog"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(leonBoard())), catalog = catalog)
        runCurrent()
        vm.openSetup("leon")
        assertEquals(Screen.SETUP, vm.state.value.screen)
        assertEquals("leon", vm.state.value.editingBoardId)
        assertNull(vm.state.value.setupPlace)
        assertEquals(setOf(leonPlace().servicios.single().key()), vm.state.value.setupSelected)
        assertTrue(vm.state.value.setupSearchFailed)
        assertTrue(vm.state.value.catalogLoadFailed)
    }

    @Test
    fun serviceLookupFailureKeepsDetailsHistoryWithoutGlobalCatalogError() = vmTest {
        val place = leonPlace()
        val catalog = FakeCatalog(listOf(place), serviceFailure = java.io.IOException("broken catalog"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(), catalog = catalog)
        runCurrent()
        val history = listOf(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        vm.openSearch()
        vm.searchCatalog("leon")
        vm.openDetails(history.single())
        vm.openService(place.servicios.single())
        runCurrent()
        assertEquals(Screen.DETAILS, vm.state.value.screen)
        assertEquals(history, vm.state.value.details)
        assertFalse("A details lookup failure needs its own retry state", vm.state.value.catalogLoadFailed)
        assertEquals(place.servicios.single(), vm.state.value.serviceLookup!!.option)
        assertTrue(vm.state.value.serviceLookup!!.failed)
        catalog.serviceFailure = null
        vm.retryServiceLookup()
        assertFalse(vm.state.value.serviceLookup!!.failed)
        runCurrent()
        assertNull(vm.state.value.serviceLookup)
        assertEquals(2, vm.state.value.details.size)
        assertEquals("leon", vm.state.value.catalogQuery)
        vm.backSetup()
        assertEquals(history, vm.state.value.details)
        vm.retryServiceLookup()
        runCurrent()
        assertEquals(history, vm.state.value.details)
    }

    @Test
    fun failedLookupClearsOnNewTapAndNavigationAndMissingServiceCanRetry() = vmTest {
        val place = leonPlace()
        val option = place.servicios.single()
        val catalog = FakeCatalog(listOf(place), serviceFailure = java.io.IOException("unavailable"))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(leonBoard())), catalog = catalog)
        runCurrent()
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        vm.openService(option)
        runCurrent()
        assertTrue(vm.state.value.serviceLookup!!.failed)
        catalog.serviceFailure = null
        catalog.serviceDelays = mapOf(option.painted to 1000L)
        vm.openService(option)
        assertFalse(vm.state.value.serviceLookup!!.failed)
        vm.backSetup()
        assertNull(vm.state.value.serviceLookup)
        vm.retryServiceLookup()
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(Screen.SEARCH, vm.state.value.screen)
        assertTrue(vm.state.value.details.isEmpty())
        vm.openDetails(co.camilo.transmicrone.domain.CatalogEntry.Location(place))
        vm.openService(option.copy(painted = "missing"))
        runCurrent()
        assertTrue(vm.state.value.serviceLookup!!.failed)
        vm.goHome()
        assertNull(vm.state.value.serviceLookup)
        assertEquals(1, vm.state.value.boards.size)
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
    fun progressiveBoardShowsFastServiceWhileSlowServiceRetainsCacheAndFails() = vmTest {
        val board = ricaurteBoard().copy(servicios = leonBoard().servicios + ricaurteBoard().servicios)
        val response = CompletableDeferred<FetchResult>()
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "tm-progress-${System.nanoTime()}")
        val cache = FileArrivalsCache(cacheDir, json)
        val old = clock.now().minusSeconds(60)
        cache.write(board.boardId, CachedArrivals(listOf(arrival("S43", "old")), old))
        val feed = object : FeedClient {
            override suspend fun fetchBoard(queryParadero: String): FetchResult = error("Must stream services")
            override fun updates(board: Board) = flow {
                emit(ServiceFetchResult(board.servicios[0].serviceKey(),
                    FetchResult.Success(listOf(arrival("E48", "fast")), clock.now())))
                emit(ServiceFetchResult(board.servicios[1].serviceKey(), response.await()))
            }
        }
        val vm = viewModel(feed, MemoryBoards(listOf(board)), cache)
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        val partial = vm.state.value.boards.single()
        assertEquals(setOf("fast", "old"), partial.arrivals.map { it.etaPhrase }.toSet())
        assertTrue(partial.freshness is Freshness.UpdatingCached)
        assertEquals(ServiceStatus.Updated, partial.services[0].status)
        assertEquals(ServiceStatus.Pending, partial.services[1].status)
        response.complete(FetchResult.Failure(FailureKind.TIMEOUT, null))
        runCurrent()
        val completed = vm.state.value.boards.single()
        assertEquals(ServiceStatus.Updated, completed.services[0].status)
        assertEquals(ServiceStatus.Failed(FailureKind.TIMEOUT), completed.services[1].status)
        assertEquals(old, completed.services[1].cached!!.fetchedAt)
        assertEquals(setOf("fast", "old"), completed.arrivals.map { it.etaPhrase }.toSet())
    }

    @Test
    fun stoppingProgressiveRefreshKeepsCompletedServiceAndResetsPendingStatus() = vmTest {
        val board = ricaurteBoard().copy(servicios = leonBoard().servicios + ricaurteBoard().servicios)
        val response = CompletableDeferred<FetchResult>()
        val feed = object : FeedClient {
            override suspend fun fetchBoard(queryParadero: String): FetchResult = error("Must stream services")
            override fun updates(board: Board) = flow {
                emit(ServiceFetchResult(board.servicios[0].serviceKey(),
                    FetchResult.Success(listOf(arrival("E48", "completed")), clock.now())))
                emit(ServiceFetchResult(board.servicios[1].serviceKey(), response.await()))
            }
        }
        val vm = viewModel(feed, MemoryBoards(listOf(board)))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        assertEquals(ServiceStatus.Pending, vm.state.value.boards.single().services[1].status)
        vm.setStarted(false)
        runCurrent()
        val stopped = vm.state.value.boards.single()
        assertEquals(ServiceStatus.Updated, stopped.services[0].status)
        assertEquals(ServiceStatus.Idle, stopped.services[1].status)
        response.complete(FetchResult.Success(listOf(arrival("S43", "late")), clock.now()))
        runCurrent()
        assertEquals(listOf("completed"), vm.state.value.boards.single().arrivals.map { it.etaPhrase })
    }

    @Test
    fun cancellingAndReloadingRetainsEmptyOutcomesAndOriginalTimes() = vmTest {
        val board = ricaurteBoard().copy(servicios = leonBoard().servicios + ricaurteBoard().servicios)
        val cache = FileArrivalsCache(File(System.getProperty("java.io.tmpdir"), "tm-empty-restore-${System.nanoTime()}"), json)
        val old = clock.now().minusSeconds(60)
        cache.write(board.boardId, CachedArrivals(emptyList(), old))
        val response = CompletableDeferred<FetchResult>()
        val feed = object : FeedClient {
            override suspend fun fetchBoard(queryParadero: String): FetchResult = error("Must stream services")
            override fun updates(board: Board) = flow {
                emit(ServiceFetchResult(board.servicios[0].serviceKey(), FetchResult.Empty(clock.now())))
                emit(ServiceFetchResult(board.servicios[1].serviceKey(), response.await()))
            }
        }
        val vm = viewModel(feed, MemoryBoards(listOf(board)), cache)
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.setStarted(false)
        runCurrent()
        assertEquals(ServiceStatus.Empty, vm.state.value.boards.single().services[0].status)
        assertEquals(ServiceStatus.CachedEmpty, vm.state.value.boards.single().services[1].status)
        val restored = viewModel(ScriptedFeed(), MemoryBoards(listOf(board)), cache)
        runCurrent()
        assertEquals(listOf(ServiceStatus.CachedEmpty, ServiceStatus.CachedEmpty),
            restored.state.value.boards.single().services.map { it.status })
        assertEquals(listOf(clock.now(), old), restored.state.value.boards.single().services.map { it.cached!!.fetchedAt })
    }

    @Test
    fun automaticPollingLetsSlowFetchFinishAndResumesAfterwards() = vmTest {
        val interval = BuildConfig.AUTO_REFRESH_MS
        val feed = ScriptedFeed()
        feed.enqueue(interval * 2 + 1_000, FetchResult.Success(listOf(arrival("E48", "Llegando")), clock.now()))
        feed.enqueue(0, FetchResult.Success(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()

        advanceTimeBy(interval * 2)
        runCurrent()
        assertEquals("Timer ticks must not replace an in-flight fetch", 1, feed.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.boards.single().freshness is Freshness.Updated)
        assertEquals("Llegando", vm.state.value.boards.single().arrivals.single().etaPhrase)

        advanceTimeBy(interval - 1_000)
        runCurrent()
        assertEquals(2, feed.calls.size)
        assertEquals("Entre 2 y 4 min", vm.state.value.boards.single().arrivals.single().etaPhrase)
    }

    @Test
    fun automaticPollingDoesNotCancelSlowManualRefresh() = vmTest {
        val interval = BuildConfig.AUTO_REFRESH_MS
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Empty(clock.now()))
        feed.enqueue(interval + 1_000, FetchResult.Success(listOf(arrival("E48", "Llegando")), clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.manualRefresh()
        runCurrent()

        advanceTimeBy(interval)
        runCurrent()
        assertEquals("The timer must leave a manual refresh running", 2, feed.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.boards.single().freshness is Freshness.Updated)
        assertEquals("Llegando", vm.state.value.boards.single().arrivals.single().etaPhrase)
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
    fun cachedBoardSwitchFetchesQuietlyButOpeningAndManualRefreshShowProgress() = vmTest {
        val cache = FileArrivalsCache(File(System.getProperty("java.io.tmpdir"), "tm-switch-${System.nanoTime()}"), json)
        cache.write("leon", CachedArrivals(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()))
        cache.write(ricaurteBoard().boardId, CachedArrivals(listOf(arrival("S43", "Entre 5 y 7 min")), clock.now()))
        val feed = ScriptedFeed()
        feed.enqueue(1_000, FetchResult.Success(listOf(arrival("E48", "Llegando")), clock.now()))
        feed.enqueue(1_000, FetchResult.Success(listOf(arrival("S43", "Llegando")), clock.now()))
        feed.enqueue(1_000, FetchResult.Failure(FailureKind.TIMEOUT, null))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())), cache)
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        assertFalse((vm.state.value.boards[0].freshness as Freshness.UpdatingCached).quiet)
        vm.onPage(1)
        runCurrent()
        val switching = vm.state.value.boards[1]
        assertTrue((switching.freshness as Freshness.UpdatingCached).quiet)
        assertEquals("Entre 5 y 7 min", switching.arrivals.single().etaPhrase)
        assertEquals(2, feed.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.boards[1].freshness is Freshness.Updated)
        vm.manualRefresh()
        runCurrent()
        assertFalse((vm.state.value.boards[1].freshness as Freshness.UpdatingCached).quiet)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.boards[1].freshness is Freshness.FailedCached)
        assertEquals("Llegando", vm.state.value.boards[1].arrivals.single().etaPhrase)
    }

    @Test
    fun cachedEmptyBoardSwitchAlsoFetchesQuietly() = vmTest {
        val cache = FileArrivalsCache(File(System.getProperty("java.io.tmpdir"), "tm-empty-switch-${System.nanoTime()}"), json)
        cache.write(ricaurteBoard().boardId, CachedArrivals(emptyList(), clock.now()))
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Empty(clock.now()))
        feed.enqueue(1_000, FetchResult.Empty(clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())), cache)
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.onPage(1)
        runCurrent()
        assertTrue((vm.state.value.boards[1].freshness as Freshness.UpdatingCached).quiet)
        assertTrue(vm.state.value.boards[1].arrivals.isEmpty())
    }

    @Test
    fun uncachedBoardSwitchStillShowsInitialLoading() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Empty(clock.now()))
        feed.enqueue(1_000, FetchResult.Empty(clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.onPage(1)
        runCurrent()
        assertEquals(Freshness.Loading, vm.state.value.boards[1].freshness)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.boards[1].freshness is Freshness.Empty)
    }

    @Test
    fun diskCacheIsStaleUntilThisProcessRefresh() = vmTest {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "tm-vm-${System.nanoTime()}")
        val cache = FileArrivalsCache(cacheDir, json)
        cache.write(
            "leon",
            CachedArrivals(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()),
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

    @Test
    fun backFromDirectEditReturnsHomeAndDiscardsDraft() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.openSetup("leon")
        assertEquals(Screen.SETUP, vm.state.value.screen)
        vm.toggleServicio(vm.state.value.setupSelected.first())
        vm.backSetup()
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertNull(vm.state.value.editingBoardId)
        vm.openSetup("leon")
        assertTrue(vm.state.value.setupSelected.isNotEmpty())
        vm.changeSetupPlace()
        vm.backSetup()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun backFromAddingServicesReturnsToSearchBeforeHome() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards())
        runCurrent()
        vm.openSetup()
        vm.pickPlace(leonPlace())
        vm.backSetup()
        assertEquals(Screen.SETUP, vm.state.value.screen)
        assertNull(vm.state.value.setupPlace)
        vm.backSetup()
        assertEquals(Screen.HOME, vm.state.value.screen)
    }

    @Test
    fun editedBoardKeepsDraftSelectionWhenReturningToSamePlace() = vmTest {
        val place = leonPlace().copy(servicios = leonPlace().servicios + ServicioOption("S48", "San Mateo", listOf(12), null))
        val vm = viewModel(ScriptedFeed(), MemoryBoards(listOf(leonBoard())), catalog = FakeCatalog(listOf(place)))
        runCurrent()
        vm.openSetup("leon")
        vm.toggleServicio(place.servicios.last().key())
        val selection = vm.state.value.setupSelected

        vm.changeSetupPlace()
        vm.onSearch("leo")
        vm.onSearch("leon")
        runCurrent()
        assertEquals(null, vm.state.value.setupPlace)
        vm.pickPlace(place)

        assertEquals(selection, vm.state.value.setupSelected)
        assertEquals("leon", vm.state.value.editingBoardId)
        vm.saveSetup()
        runCurrent()
        assertEquals(listOf("E48", "S48"), vm.state.value.boards.single().board.servicios.map { it.painted })
    }

    @Test
    fun newBoardPreservesSelectionDuringSearchButResetsWhenDifferentPlaceIsChosen() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards())
        runCurrent()
        vm.openSetup()
        vm.pickPlace(leonPlace())
        vm.toggleServicio(leonPlace().servicios.single().key())
        val selection = vm.state.value.setupSelected
        vm.changeSetupPlace()
        vm.onSearch("")
        vm.onSearch("leon")
        runCurrent()
        vm.pickPlace(leonPlace())
        assertEquals(selection, vm.state.value.setupSelected)

        vm.changeSetupPlace()
        vm.pickPlace(ricaurtePlace())
        assertTrue(vm.state.value.setupSelected.isEmpty())
        vm.toggleServicio(ricaurtePlace().servicios.single().key())
        vm.changeSetupPlace()
        vm.pickPlace(leonPlace())
        assertTrue(vm.state.value.setupSelected.isEmpty())
    }

    @Test
    fun customBoardNameIsTrimmedRetainedOnEditAndClearedBackToStationName() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards())
        runCurrent()
        val place = leonPlace()
        vm.openSetup()
        vm.pickPlace(place)
        vm.toggleServicio(place.servicios.single().key())
        vm.onSetupCustomName("  Casa  ")
        vm.saveSetup()
        runCurrent()
        val saved = vm.state.value.boards.single().board
        assertEquals("Casa", saved.displayName)
        assertEquals(place.placeId, saved.placeId)
        assertEquals(place.queryParadero, saved.queryParadero)
        vm.openSetup(saved.boardId)
        assertEquals("Casa", vm.state.value.setupCustomName)
        vm.onSetupCustomName("   ")
        vm.saveSetup()
        runCurrent()
        assertEquals(place.name, vm.state.value.boards.single().board.displayName)
        vm.openSetup(saved.boardId)
        assertEquals("", vm.state.value.setupCustomName)
        vm.onSetupCustomName("Abandoned draft")
        vm.goHome()
        vm.openSetup()
        assertEquals("", vm.state.value.setupCustomName)
        vm.pickPlace(place)
        vm.toggleServicio(place.servicios.single().key())
        vm.saveSetup()
        runCurrent()
        assertTrue(vm.state.value.boards.all { it.board.displayName == place.name })
    }

    @Test
    fun openingNewSetupDoesNotRestoreAbandonedDraft() = vmTest {
        val vm = viewModel(ScriptedFeed(), MemoryBoards())
        runCurrent()
        vm.openSetup()
        vm.pickPlace(leonPlace())
        vm.toggleServicio(leonPlace().servicios.single().key())
        vm.goHome()
        vm.openSetup()
        vm.pickPlace(leonPlace())
        assertTrue(vm.state.value.setupSelected.isEmpty())
    }

    @Test
    fun manualRefreshRetriesFailureAndCoalescesInFlightPulls() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Success(listOf(arrival("E48", "Entre 2 y 4 min")), clock.now()))
        feed.enqueue(0, FetchResult.Failure(FailureKind.TIMEOUT, null))
        feed.enqueue(1_000, FetchResult.Success(listOf(arrival("E48", "Llegando")), clock.now()))
        feed.enqueue(0, FetchResult.Success(listOf(arrival("E48", "Actualizado")), clock.now()))
        feed.enqueue(0, FetchResult.Empty(clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.manualRefresh()
        runCurrent()
        assertTrue(vm.state.value.boards.single().freshness is Freshness.FailedCached)

        vm.manualRefresh()
        vm.manualRefresh()
        runCurrent()
        vm.manualRefresh()
        runCurrent()
        assertEquals(3, feed.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(vm.state.value.boards.single().freshness is Freshness.Updated)
        assertEquals(4, feed.calls.size)
        assertEquals("Actualizado", vm.state.value.boards.single().arrivals.single().etaPhrase)
        vm.manualRefresh()
        runCurrent()
        assertEquals(5, feed.calls.size)
    }

    @Test
    fun pullDuringQuietRefreshShowsProgressAndQueuesOneRefresh() = vmTest {
        val cache = FileArrivalsCache(File(System.getProperty("java.io.tmpdir"), "tm-pull-${System.nanoTime()}"), json)
        cache.write(ricaurteBoard().boardId, CachedArrivals(listOf(arrival("S43", "Anterior")), clock.now()))
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Empty(clock.now()))
        feed.enqueue(1_000, FetchResult.Empty(clock.now()))
        feed.enqueue(1_000, FetchResult.Success(listOf(arrival("S43", "Llegando")), clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())), cache)
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.onPage(1)
        runCurrent()
        assertTrue((vm.state.value.boards[1].freshness as Freshness.UpdatingCached).quiet)
        repeat(3) { vm.manualRefresh() }
        assertFalse((vm.state.value.boards[1].freshness as Freshness.UpdatingCached).quiet)
        assertEquals(2, feed.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, feed.calls.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("Llegando", vm.state.value.boards[1].arrivals.single().etaPhrase)
    }

    @Test
    fun queuedManualRefreshIsDiscardedWhenSwitchingBoardsOrStopping() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(10_000, FetchResult.Empty(clock.now()))
        feed.enqueue(10_000, FetchResult.Empty(clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard(), ricaurteBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.manualRefresh()
        vm.onPage(1)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("TM0101", "TM0069"), feed.calls)
        feed.enqueue(10_000, FetchResult.Empty(clock.now()))
        vm.manualRefresh()
        runCurrent()
        vm.manualRefresh()
        vm.setStarted(false)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(3, feed.calls.size)
    }

    @Test
    fun immediateRetryWithoutCacheBypassesThrottle() = vmTest {
        val feed = ScriptedFeed()
        feed.enqueue(0, FetchResult.Failure(FailureKind.TIMEOUT, null))
        feed.enqueue(0, FetchResult.Failure(FailureKind.HOST_REJECTED, null))
        feed.enqueue(0, FetchResult.Empty(clock.now()))
        val vm = viewModel(feed, MemoryBoards(listOf(leonBoard())))
        runCurrent()
        vm.setStarted(true)
        runCurrent()
        vm.manualRefresh()
        runCurrent()
        assertTrue(vm.state.value.boards.single().freshness is Freshness.Failed)
        vm.manualRefresh()
        runCurrent()
        assertEquals(3, feed.calls.size)
        assertTrue(vm.state.value.boards.single().freshness is Freshness.Empty)
    }

    private fun viewModel(
        feed: FeedClient,
        boards: BoardRepository,
        cache: ArrivalsCache = FileArrivalsCache(
            File(System.getProperty("java.io.tmpdir"), "tm-vm-${System.nanoTime()}"),
            json,
        ),
        catalog: CatalogRepository = FakeCatalog(listOf(leonPlace(), ricaurtePlace())),
        routeRepository: co.camilo.transmicrone.data.catalog.RouteRepository = co.camilo.transmicrone.data.catalog.RouteRepository { emptyList() },
    ): AppViewModel {
        val store = ViewModelStore()
        stores += store
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AppViewModel(
                    catalog = catalog,
                    boards = boards,
                    arrivals = ArrivalsRepository(feed, cache),
                    clock = clock,
                    io = dispatcher,
                    compute = dispatcher,
                    routeRepository = routeRepository,
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
    override suspend fun move(boardId: String, offset: Int) {
        state.value = state.value.moving(boardId, offset)
    }
    override suspend fun delete(boardId: String) {
        val boards = state.value.boards.filterNot { it.boardId == boardId }
        state.value = state.value.copy(boards = boards, lastBoardId = boards.firstOrNull()?.boardId)
    }
    override suspend fun restoreDeleted(board: Board, originalOrder: List<String>, previousLastBoardId: String?, lastBoardIdAfterDelete: String?) {
        state.value = state.value.restoringDeleted(board, originalOrder, previousLastBoardId, lastBoardIdAfterDelete)
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
    var serviceDelays: Map<String, Long> = emptyMap(),
    var generatedAtFailure: Exception? = null,
    var searchFailure: Exception? = null,
    var placeFailure: Exception? = null,
    var serviceFailure: Exception? = null,
) : CatalogRepository {
    var placeCalls = 0
        private set

    override suspend fun explore(query: String, limit: Int) = co.camilo.transmicrone.domain.CatalogResults(places = search(query, limit))
    override suspend fun service(painted: String, destino: String): co.camilo.transmicrone.domain.CatalogEntry.Service? {
        serviceFailure?.let { throw it }
        delay(serviceDelays[painted] ?: 0)
        val served = places.filter { p -> p.servicios.any { it.painted == painted && it.destino == destino } }
        val option = served.firstOrNull()?.servicios?.find { it.painted == painted && it.destino == destino } ?: return null
        return co.camilo.transmicrone.domain.CatalogEntry.Service(option, served)
    }
    override suspend fun search(query: String, limit: Int): List<Place> {
        searchFailure?.let { throw it }
        val key = query.trim().lowercase()
        delay(searchDelays[key] ?: 0)
        val q = TextNorm.fold(query).trim()
        if (q.length < 2) return emptyList()
        return places.filter { it.fold.contains(q) }.take(limit)
    }

    override fun place(placeId: String): Place? {
        placeCalls++
        placeFailure?.let { throw it }
        return places.find { it.placeId == placeId }
    }

    override suspend fun generatedAt(): String? {
        delay(generatedAtDelayMs)
        generatedAtFailure?.let { throw it }
        return "test"
    }
}
