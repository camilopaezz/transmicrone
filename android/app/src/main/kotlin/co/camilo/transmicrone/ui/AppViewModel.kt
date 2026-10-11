package co.camilo.transmicrone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.CatalogRepository
import co.camilo.transmicrone.data.catalog.RouteRepository
import co.camilo.transmicrone.domain.ServiceRoute
import co.camilo.transmicrone.data.local.BoardRepository
import co.camilo.transmicrone.di.AppContainer
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CachedArrivals
import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.FetchResult
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.Paradero
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import co.camilo.transmicrone.domain.ServicioOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

enum class Screen { HOME, SETUP, SEARCH, DETAILS, SETTINGS }

data class DeletedBoard(val boardId: String, val displayName: String)

private data class BoardDeletion(
    val board: Board,
    val originalOrder: List<String>,
    val previousLastBoardId: String?,
    val lastBoardIdAfterDelete: String?,
)

data class BoardView(
    val board: Board,
    val arrivals: List<co.camilo.transmicrone.domain.Arrival> = emptyList(),
    val freshness: Freshness = Freshness.Idle,
    val cache: CachedArrivals? = null,
)

sealed class Freshness {
    data object Idle : Freshness()
    data object Loading : Freshness()
    data class Updated(val at: Instant) : Freshness()
    data class Cached(val cachedAt: Instant) : Freshness()
    data class UpdatingCached(val cachedAt: Instant) : Freshness()
    data class FailedCached(val cachedAt: Instant, val kind: FailureKind) : Freshness()
    data class Failed(val kind: FailureKind) : Freshness()
    data class Empty(val at: Instant) : Freshness()
    data object Unsupported : Freshness()
}

sealed interface RouteState {
    data object Loading : RouteState
    data class Loaded(val routes: List<ServiceRoute>) : RouteState
    data object Failed : RouteState
    data object Unavailable : RouteState
}

data class ServiceLookupState(
    val option: ServicioOption,
    val history: List<CatalogEntry>,
    val failed: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val settingsSaveFailed: Boolean = false,
    val boards: List<BoardView> = emptyList(),
    val boardsReady: Boolean = false,
    val pageIndex: Int = 0,
    val started: Boolean = false,
    val setupQuery: String = "",
    val setupResults: List<Place> = emptyList(),
    val setupSearching: Boolean = false,
    val setupPlace: Place? = null,
    val setupSelected: Set<String> = emptySet(),
    val setupCustomName: String = "",
    val editingBoardId: String? = null,
    val catalogReady: Boolean = false,
    val catalogLoadFailed: Boolean = false,
    val setupSearchFailed: Boolean = false,
    val setupSaveFailed: Boolean = false,
    val pendingDeletion: DeletedBoard? = null,
    val catalogQuery: String = "",
    val catalogResults: CatalogResults = CatalogResults(),
    val catalogSearching: Boolean = false,
    val catalogSearchFailed: Boolean = false,
    val details: List<CatalogEntry> = emptyList(),
    val serviceLookup: ServiceLookupState? = null,
    val routes: Map<String, RouteState> = emptyMap(),
    val selectedRoutes: Map<String, Int> = emptyMap(),
)

class AppViewModel(
    private val catalog: CatalogRepository,
    private val boards: BoardRepository,
    private val arrivals: ArrivalsRepository,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val routeRepository: RouteRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var loop: Job? = null
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var catalogSearchJob: Job? = null
    private var serviceLookupJob: Job? = null
    private val routeGenerations = mutableMapOf<String, Long>()
    private val routeJobs = mutableMapOf<String, Job>()
    private var navigationGeneration = 0L
    private var setupReturnsToDetails = false

    private fun cancelServiceLookup() {
        navigationGeneration++
        serviceLookupJob?.cancel()
        _state.update { it.copy(serviceLookup = null) }
    }
    // Search hides the chosen place, but its selection remains a draft until another place is chosen.
    private var setupDraftPlaceId: String? = null
    private var fetchGeneration: Long = 0
    private var lastPersistedId: String? = null
    private var lastManual: Instant = Instant.EPOCH
    private val boardMutation = Mutex()
    private var boardDeletion: BoardDeletion? = null

    init {
        viewModelScope.launch {
            try {
                withContext(io) { catalog.generatedAt() }
                _state.update { it.copy(catalogReady = true, catalogLoadFailed = false) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(catalogReady = false, catalogLoadFailed = true) }
            }
            boards.observe().collect { snap ->
                applySnapshot(snap)
            }
        }
    }

    fun setStarted(started: Boolean) {
        _state.update { it.copy(started = started) }
        if (started) restartLoop() else stopPolling()
    }

    fun onPage(index: Int) {
        val st = _state.value
        if (index !in st.boards.indices) return
        val id = st.boards[index].board.boardId
        val pageChanged = index != st.pageIndex
        if (pageChanged) {
            _state.update { it.copy(pageIndex = index) }
            restartLoop()
        }
        if (lastPersistedId != id) {
            viewModelScope.launch { boards.setLastBoard(id) }
        }
    }

    fun openSetup(editId: String? = null) {
        cancelServiceLookup()
        val generation = navigationGeneration
        setupReturnsToDetails = false
        val editing = editId?.let { id -> _state.value.boards.find { it.board.boardId == id }?.board }

        fun lookupPlace(): Pair<Place?, Boolean> = try {
            editing?.let { catalog.place(it.placeId) } to false
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null to true
        }

        fun applySetup(editingPlace: Place?, placeLookupFailed: Boolean) {
            var applied = false
            _state.update {
                applied = navigationGeneration == generation
                if (!applied) it else it.copy(
                    screen = Screen.SETUP,
                    editingBoardId = editId,
                    setupQuery = "",
                    setupResults = emptyList(),
                    setupSearching = false,
                    setupSearchFailed = placeLookupFailed,
                    setupSaveFailed = false,
                    catalogLoadFailed = it.catalogLoadFailed || placeLookupFailed,
                    catalogReady = it.catalogReady && !placeLookupFailed,
                    setupPlace = editingPlace,
                    setupCustomName = editing?.displayName?.takeIf { it != editingPlace?.name }.orEmpty(),
                    setupSelected = editing?.servicios?.map { s -> s.key() }?.toSet() ?: emptySet(),
                )
            }
            if (applied) {
                setupDraftPlaceId = editing?.placeId
                stopPolling()
            }
        }

        val st = _state.value
        if (editing == null || (st.catalogReady && !st.catalogLoadFailed)) {
            val (place, failed) = lookupPlace()
            applySetup(place, failed)
        } else {
            viewModelScope.launch {
                val (place, failed) = withContext(io) { lookupPlace() }
                applySetup(place, failed)
            }
        }
    }

    fun backSetup() {
        cancelServiceLookup()
        if (_state.value.screen == Screen.SETUP && setupReturnsToDetails) {
            searchJob?.cancel()
            setupReturnsToDetails = false
            setupDraftPlaceId = null
            _state.update { it.copy(screen = Screen.DETAILS, setupPlace = null, setupSelected = emptySet(), setupSearching = false) }
            return
        }
        when (_state.value.screen) {
            Screen.DETAILS -> {
                _state.update {
                    val previous = it.details.dropLast(1)
                    it.copy(details = previous, screen = if (previous.isEmpty()) Screen.SEARCH else Screen.DETAILS)
                }
                return
            }
            Screen.SETTINGS -> { goHome(); return }
            Screen.SEARCH -> { catalogSearchJob?.cancel(); goHome(); return }
            else -> Unit
        }
        if (_state.value.editingBoardId == null && _state.value.setupPlace != null) changeSetupPlace()
        else goHome()
    }

    fun openSettings() {
        cancelServiceLookup()
        _state.update { it.copy(screen = Screen.SETTINGS, settingsSaveFailed = false) }
        stopPolling()
    }

    fun moveBoard(boardId: String, offset: Int) {
        viewModelScope.launch {
            boardMutation.withLock {
                try {
                    boards.move(boardId, offset)
                    _state.update { it.copy(settingsSaveFailed = false) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _state.update { it.copy(settingsSaveFailed = true) }
                }
            }
        }
    }

    fun openSearch() {
        cancelServiceLookup()
        _state.update { it.copy(screen = Screen.SEARCH, details = emptyList()) }
        stopPolling()
        searchCatalog(_state.value.catalogQuery)
    }

    fun searchCatalog(query: String) {
        catalogSearchJob?.cancel()
        val searching = query.isNotBlank()
        _state.update { it.copy(catalogQuery = query, catalogResults = CatalogResults(),
            catalogSearching = searching, catalogSearchFailed = false) }
        if (!searching) return
        catalogSearchJob = viewModelScope.launch(compute) {
            delay(150)
            try {
                val results = catalog.explore(query)
                if (isActive) _state.update {
                    if (it.catalogQuery != query) it else it.copy(catalogResults = results, catalogSearching = false,
                        catalogReady = true, catalogLoadFailed = false)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isActive) _state.update {
                    if (it.catalogQuery != query) it else it.copy(catalogSearching = false, catalogSearchFailed = true)
                }
            }
        }
    }

    fun openDetails(entry: CatalogEntry) {
        cancelServiceLookup()
        _state.update { it.copy(screen = Screen.DETAILS, details = it.details + entry) }
        if (entry is CatalogEntry.Service) loadRoute(entry.option)
    }

    fun loadRoute(option: ServicioOption, retry: Boolean = false) {
        val key = option.key()
        val previous = _state.value.routes[key]
        if ((previous == RouteState.Loading && !retry) || (!retry && previous is RouteState.Loaded)) return
        val generation = (routeGenerations[key] ?: 0L) + 1L
        routeGenerations[key] = generation
        routeJobs[key]?.cancel()
        _state.update { it.copy(routes = it.routes + (key to RouteState.Loading)) }
        routeJobs[key] = viewModelScope.launch(io) {
            val result = try {
                val routes = routeRepository.load(option).map { route -> route.copy(stops = route.stops.map { stop ->
                    val code = stop.code.removeSuffix("_TM")
                    val kind = if (code.startsWith("TM")) "estacion" else "paradero"
                    stop.copy(place = catalog.place("$kind:$code"))
                }) }
                if (routes.isEmpty()) RouteState.Unavailable else RouteState.Loaded(routes)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { RouteState.Failed }
            if (isActive && routeGenerations[key] == generation) {
                _state.update { it.copy(routes = it.routes + (key to result)) }
            }
        }
    }

    fun selectRoute(option: ServicioOption, index: Int) {
        val routes = (_state.value.routes[option.key()] as? RouteState.Loaded)?.routes ?: return
        if (index !in routes.indices) return
        _state.update { it.copy(selectedRoutes = it.selectedRoutes + (option.key() to index)) }
    }

    fun openService(option: ServicioOption) {
        if (_state.value.screen != Screen.DETAILS) return
        cancelServiceLookup()
        val generation = navigationGeneration
        val previous = _state.value.details
        _state.update { it.copy(serviceLookup = ServiceLookupState(option, previous)) }
        serviceLookupJob = viewModelScope.launch {
            val entry = try {
                withContext(compute) { catalog.service(option.painted, option.destino) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isActive && navigationGeneration == generation) {
                    _state.update { it.copy(serviceLookup = ServiceLookupState(option, previous, failed = true)) }
                }
                return@launch
            }
            if (!isActive || navigationGeneration != generation) return@launch
            if (entry == null) {
                _state.update { it.copy(serviceLookup = ServiceLookupState(option, previous, failed = true)) }
                return@launch
            }
            _state.update {
                if (it.screen != Screen.DETAILS || it.details != previous) it
                else it.copy(details = it.details + entry, serviceLookup = null)
            }
            if (_state.value.details.lastOrNull() == entry) loadRoute(entry.option)
        }
    }

    fun retryServiceLookup() {
        val current = _state.value
        val lookup = current.serviceLookup ?: return
        if (!lookup.failed || current.screen != Screen.DETAILS || current.details != lookup.history) return
        openService(lookup.option)
    }

    fun createBoardFrom(place: Place) {
        openSetup(null)
        setupReturnsToDetails = true
        pickPlace(place)
    }

    fun goHome() {
        cancelServiceLookup()
        setupReturnsToDetails = false
        _state.update { it.copy(screen = Screen.HOME, editingBoardId = null) }
        restartLoop()
    }

    fun onSearch(query: String) {
        val searching = query.trim().length >= 2
        _state.update {
            it.copy(
                setupQuery = query,
                setupPlace = null,
                setupSearching = searching,
                setupSearchFailed = false,
                setupResults = if (searching) it.setupResults else emptyList(),
            )
        }
        searchJob?.cancel()
        if (!searching) {
            _state.update { it.copy(setupResults = emptyList(), setupSearching = false) }
            return
        }
        val issued = query
        searchJob = viewModelScope.launch(compute) {
            try {
                val results = catalog.search(issued)
                if (!isActive) return@launch
                _state.update { ui ->
                    if (ui.setupQuery != issued) ui
                    else ui.copy(setupResults = results, setupSearching = false, setupSearchFailed = false,
                        catalogReady = true, catalogLoadFailed = false)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isActive) _state.update { ui ->
                    if (ui.setupQuery != issued) ui
                    else ui.copy(setupResults = emptyList(), setupSearching = false, setupSearchFailed = true)
                }
            }
        }
    }

    fun pickPlace(place: Place) {
        searchJob?.cancel()
        val samePlace = setupDraftPlaceId == place.placeId
        setupDraftPlaceId = place.placeId
        _state.update {
            it.copy(
                setupPlace = place,
                setupSelected = if (samePlace) it.setupSelected else emptySet(),
                setupResults = emptyList(),
                setupSearching = false,
                setupSaveFailed = false,
                setupSearchFailed = false,
            )
        }
    }

    fun changeSetupPlace() {
        val st = _state.value
        val query = st.setupQuery.ifBlank { st.setupPlace?.name.orEmpty() }
        // The same board is edited if the user chooses a different place; saving replaces it.
        onSearch(query)
    }

    fun toggleServicio(optionKey: String) {
        _state.update {
            val next = it.setupSelected.toMutableSet()
            if (!next.add(optionKey)) next.remove(optionKey)
            it.copy(setupSelected = next)
        }
    }

    fun onSetupCustomName(name: String) {
        _state.update { it.copy(setupCustomName = name.replace('\n', ' ').replace('\r', ' '), setupSaveFailed = false) }
    }

    fun saveSetup() {
        val st = _state.value
        val place = st.setupPlace ?: return
        val selected = place.servicios.filter { it.key() in st.setupSelected }
        if (selected.isEmpty()) return
        if (Paradero.normalize(place.queryParadero) == null) {
            _state.update { it.copy(setupSaveFailed = true) }
            return
        }
        _state.update { it.copy(setupSaveFailed = false) }
        val board = Board(
            boardId = st.editingBoardId ?: boards.newId(),
            placeId = place.placeId,
            kind = place.kind,
            queryParadero = place.queryParadero,
            displayName = st.setupCustomName.trim().ifEmpty { place.name },
            disambiguator = place.disambiguator,
            servicios = selected.map {
                SelectedServicio(it.painted, it.destino, it.routeIds, it.color)
            },
        )
        viewModelScope.launch {
            try {
                boards.save(board)
                setupReturnsToDetails = false
                _state.update { it.copy(screen = Screen.HOME, editingBoardId = null) }
                restartLoop()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(setupSaveFailed = true) }
            }
        }
    }

    fun deleteBoard(boardId: String) {
        viewModelScope.launch {
            boardMutation.withLock {
                val before = boards.snapshot()
                val deleted = before.boards.find { it.boardId == boardId } ?: return@withLock
                boards.delete(boardId)
                arrivals.remove(deleted.boardId)
                val after = boards.snapshot()
                boardDeletion = BoardDeletion(deleted, before.boards.map { it.boardId }, before.lastBoardId, after.lastBoardId)
                _state.update { it.copy(screen = Screen.HOME, editingBoardId = null,
                    pendingDeletion = DeletedBoard(deleted.boardId, deleted.displayName)) }
                restartLoop()
            }
        }
    }

    fun undoDeleteBoard(boardId: String) {
        viewModelScope.launch {
            boardMutation.withLock {
                val deleted = boardDeletion?.takeIf { it.board.boardId == boardId } ?: return@withLock
                boards.restoreDeleted(deleted.board, deleted.originalOrder, deleted.previousLastBoardId, deleted.lastBoardIdAfterDelete)
                boardDeletion = null
                _state.update { it.copy(pendingDeletion = null) }
            }
        }
    }

    fun dismissDeletedBoard(boardId: String) {
        viewModelScope.launch {
            boardMutation.withLock {
                if (boardDeletion?.board?.boardId != boardId) return@withLock
                boardDeletion = null
                _state.update { it.copy(pendingDeletion = null) }
            }
        }
    }

    fun manualRefresh() {
        val st = _state.value
        if (!st.started || st.screen != Screen.HOME || refreshJob?.isActive == true) return
        val visible = st.boards.getOrNull(st.pageIndex) ?: return
        val retryingFailure = visible.freshness is Freshness.Failed || visible.freshness is Freshness.FailedCached
        val now = clock.now()
        if (!retryingFailure && now.toEpochMilli() - lastManual.toEpochMilli() < BuildConfig.MANUAL_REFRESH_THROTTLE_MS) return
        lastManual = now
        refreshVisible()
    }

    private fun applySnapshot(snap: co.camilo.transmicrone.data.local.BoardSnapshot) {
        lastPersistedId = snap.lastBoardId
        val current = _state.value
        val views = snap.boards.map { board ->
            current.boards.find { it.board.boardId == board.boardId }?.copy(board = board)
                ?: BoardView(board = board, cache = arrivals.cached(board)).withCache()
        }
        val idx = snap.lastBoardId?.let { id ->
            views.indexOfFirst { it.board.boardId == id }.takeIf { it >= 0 }
        } ?: current.pageIndex.coerceAtMost((views.size - 1).coerceAtLeast(0))
        val page = if (views.isEmpty()) 0 else idx.coerceIn(0, views.lastIndex)
        val before = current.boards.getOrNull(current.pageIndex)?.board
        val after = views.getOrNull(page)?.board
        _state.update {
            it.copy(
                boards = views,
                boardsReady = true,
                pageIndex = page,
            )
        }
        val visibleChanged = after != null && (
            before?.boardId != after.boardId ||
                before.servicios != after.servicios ||
                before.queryParadero != after.queryParadero
            )
        if (visibleChanged) restartLoop()
    }

    private fun stopPolling() {
        fetchGeneration++
        loop?.cancel()
        refreshJob?.cancel()
        loop = null
        refreshJob = null
    }

    private fun restartLoop() {
        loop?.cancel()
        val st = _state.value
        if (!st.started || st.screen != Screen.HOME || st.boards.isEmpty()) {
            refreshJob?.cancel()
            refreshJob = null
            return
        }
        loop = viewModelScope.launch {
            refreshVisible()
            while (isActive) {
                delay(BuildConfig.AUTO_REFRESH_MS)
                val now = _state.value
                if (!now.started || now.screen != Screen.HOME) break
                refreshVisible()
            }
        }
    }

    private fun refreshVisible() {
        val st = _state.value
        if (!st.started || st.screen != Screen.HOME) return
        val view = st.boards.getOrNull(st.pageIndex) ?: return
        val boardId = view.board.boardId
        val gen = ++fetchGeneration
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(io) {
            val cached = arrivals.cached(view.board)
            _state.update { ui ->
                ui.copy(
                    boards = ui.boards.map { bv ->
                        if (bv.board.boardId != boardId) bv
                        else bv.copy(
                            cache = cached ?: bv.cache,
                            arrivals = (cached ?: bv.cache)?.arrivals ?: bv.arrivals,
                            freshness = when {
                                cached != null -> Freshness.UpdatingCached(cached.fetchedAt)
                                bv.cache != null -> Freshness.UpdatingCached(bv.cache.fetchedAt)
                                else -> Freshness.Loading
                            },
                        )
                    },
                )
            }
            try {
                val result = arrivals.refresh(view.board)
                if (gen != fetchGeneration || !isActive) return@launch
                val live = _state.value
                if (!live.started || live.screen != Screen.HOME) return@launch
                _state.update { ui ->
                    ui.copy(
                        boards = ui.boards.map { bv ->
                            if (bv.board.boardId != boardId) bv else bv.applyResult(result)
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (gen == fetchGeneration) {
                    _state.update { ui ->
                        ui.copy(
                            boards = ui.boards.map { bv ->
                                if (bv.board.boardId != boardId) bv
                                else revertCancelledRefresh(bv)
                            },
                        )
                    }
                }
                throw e
            }
        }
    }

    class Factory(
        private val container: AppContainer,
        private val io: CoroutineDispatcher = Dispatchers.IO,
        private val compute: CoroutineDispatcher = Dispatchers.Default,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppViewModel(
                container.catalog,
                container.boards,
                container.arrivals,
                container.clock,
                io,
                compute,
                container.routes,
            ) as T
        }
    }
}

private fun revertCancelledRefresh(bv: BoardView): BoardView {
    if (bv.freshness !is Freshness.UpdatingCached && bv.freshness !is Freshness.Loading) return bv
    val cached = bv.cache
    return if (cached != null) {
        bv.copy(arrivals = cached.arrivals, freshness = Freshness.Cached(cached.fetchedAt))
    } else {
        bv.copy(freshness = Freshness.Idle)
    }
}

private fun BoardView.withCache(): BoardView {
    val cached = cache ?: return this
    return copy(
        arrivals = cached.arrivals,
        freshness = Freshness.Cached(cached.fetchedAt),
    )
}

private fun BoardView.applyResult(result: FetchResult): BoardView = when (result) {
    is FetchResult.Success -> copy(
        arrivals = result.arrivals,
        cache = CachedArrivals(result.arrivals, result.fetchedAt),
        freshness = Freshness.Updated(result.fetchedAt),
    )
    is FetchResult.Empty -> copy(
        arrivals = emptyList(),
        cache = CachedArrivals(emptyList(), result.fetchedAt),
        freshness = Freshness.Empty(result.fetchedAt),
    )
    is FetchResult.Unsupported -> copy(freshness = Freshness.Unsupported)
    is FetchResult.Failure -> {
        val cached = result.cached
        if (cached != null) {
            copy(
                arrivals = cached.arrivals,
                cache = cached,
                freshness = Freshness.FailedCached(cached.fetchedAt, result.kind),
            )
        } else {
            copy(freshness = Freshness.Failed(result.kind))
        }
    }
}

fun co.camilo.transmicrone.domain.ServicioOption.key(): String = "$painted\u0001$destino"

fun SelectedServicio.key(): String = "$painted\u0001$destino"
