package co.camilo.transmicrone.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.CatalogRepository
import co.camilo.transmicrone.data.local.BoardRepository
import co.camilo.transmicrone.di.AppContainer
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CachedArrivals
import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.FetchResult
import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.SelectedServicio
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

enum class Screen { HOME, SETUP, MANAGE }

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

data class UiState(
    val screen: Screen = Screen.HOME,
    val boards: List<BoardView> = emptyList(),
    val boardsReady: Boolean = false,
    val pageIndex: Int = 0,
    val started: Boolean = false,
    val setupQuery: String = "",
    val setupResults: List<Place> = emptyList(),
    val setupSearching: Boolean = false,
    val setupPlace: Place? = null,
    val setupSelected: Set<String> = emptySet(),
    val editingBoardId: String? = null,
    val catalogReady: Boolean = false,
)

class AppViewModel(
    private val catalog: CatalogRepository,
    private val boards: BoardRepository,
    private val arrivals: ArrivalsRepository,
    private val clock: Clock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var loop: Job? = null
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var fetchGeneration: Long = 0
    private var lastPersistedId: String? = null
    private var lastManual: Instant = Instant.EPOCH

    init {
        viewModelScope.launch {
            withContext(io) {
                runCatching { catalog.generatedAt() }
            }
            _state.update { it.copy(catalogReady = true) }
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
        val editing = editId?.let { id -> _state.value.boards.find { it.board.boardId == id }?.board }
        _state.update {
            it.copy(
                screen = Screen.SETUP,
                editingBoardId = editId,
                setupQuery = "",
                setupResults = emptyList(),
                setupSearching = false,
                setupPlace = editing?.let { b -> catalog.place(b.placeId) },
                setupSelected = editing?.servicios?.map { s -> s.key() }?.toSet() ?: emptySet(),
            )
        }
        stopPolling()
    }

    fun openManage() {
        _state.update { it.copy(screen = Screen.MANAGE) }
        stopPolling()
    }

    fun goHome() {
        _state.update { it.copy(screen = Screen.HOME) }
        restartLoop()
    }

    fun onSearch(query: String) {
        val searching = query.trim().length >= 2
        _state.update {
            it.copy(
                setupQuery = query,
                setupPlace = null,
                setupSelected = emptySet(),
                setupSearching = searching,
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
            val results = catalog.search(issued)
            if (!isActive) return@launch
            _state.update { ui ->
                if (ui.setupQuery != issued) ui
                else ui.copy(setupResults = results, setupSearching = false)
            }
        }
    }

    fun pickPlace(place: Place) {
        searchJob?.cancel()
        _state.update {
            it.copy(
                setupPlace = place,
                setupSelected = emptySet(),
                setupResults = emptyList(),
                setupSearching = false,
            )
        }
    }

    fun toggleServicio(optionKey: String) {
        _state.update {
            val next = it.setupSelected.toMutableSet()
            if (!next.add(optionKey)) next.remove(optionKey)
            it.copy(setupSelected = next)
        }
    }

    fun saveSetup() {
        val st = _state.value
        val place = st.setupPlace ?: return
        val selected = place.servicios.filter { it.key() in st.setupSelected }
        if (selected.isEmpty()) return
        val board = Board(
            boardId = st.editingBoardId ?: boards.newId(),
            placeId = place.placeId,
            kind = place.kind,
            queryParadero = place.queryParadero,
            displayName = place.name,
            disambiguator = place.disambiguator,
            servicios = selected.map {
                SelectedServicio(it.painted, it.destino, it.routeIds, it.color)
            },
        )
        viewModelScope.launch {
            boards.save(board)
            _state.update { it.copy(screen = Screen.HOME, editingBoardId = null) }
            restartLoop()
        }
    }

    fun deleteBoard(boardId: String) {
        viewModelScope.launch { boards.delete(boardId) }
    }

    fun moveBoard(boardId: String, delta: Int) {
        val ids = _state.value.boards.map { it.board.boardId }.toMutableList()
        val i = ids.indexOf(boardId)
        val j = i + delta
        if (i < 0 || j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        viewModelScope.launch { boards.reorder(ids) }
    }

    fun manualRefresh() {
        val now = clock.now()
        if (now.toEpochMilli() - lastManual.toEpochMilli() < BuildConfig.MANUAL_REFRESH_THROTTLE_MS) return
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
        cache = CachedArrivals(result.arrivals, result.fetchedAt, false),
        freshness = Freshness.Updated(result.fetchedAt),
    )
    is FetchResult.Empty -> copy(
        arrivals = emptyList(),
        cache = CachedArrivals(emptyList(), result.fetchedAt, true),
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

fun PlaceKind.label(): String = if (this == PlaceKind.ESTACION) "Estación" else "Paradero"
