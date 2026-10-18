package co.camilo.transmicrone.data

import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ArrivalsRepository(
    private val feed: FeedClient,
    private val cache: ArrivalsCache,
) {
    private val cacheLock = Any()
    private val generations = mutableMapOf<String, Long>()

    fun cached(board: Board): CachedArrivals? = cachedState(board).cache()

    fun cachedState(board: Board): BoardArrivals = synchronized(cacheLock) {
        val saved = cache.read(board.boardId)?.takeIf { it.queryScope == null || it.queryScope == board.queryScope() }
        val snapshot = BoardArrivals(board.servicios.distinctBy { it.serviceKey() }.map { service ->
            val entry = if (saved == null) null else if (saved.services.isNotEmpty()) {
                saved.services.find { it.key == service.serviceKey() }
            } else if (saved.queryScope == null) {
                // Old board-wide caches had one successful fetch time. Import
                // that time as cached, never as a fresh per-service response.
                val rows = ArrivalReducer.prepare(saved.arrivals, listOf(service))
                if (rows.isEmpty() && saved.arrivals.isNotEmpty()) null
                else CachedService(service.serviceKey(), rows, saved.fetchedAt)
            } else null
            ServiceArrivals(service, entry, entry.restoredStatus())
        }, board.queryScope())
        if (saved != null && saved.queryScope == null && saved.services.isEmpty()) {
            // Freeze a legacy cache's original query/selection before the user
            // edits the board. A later failed fetch must not transfer its ETAs.
            snapshot.cache()?.let { cache.write(board.boardId, it) }
        }
        snapshot
    }

    fun refreshUpdates(board: Board): Flow<BoardArrivals> = flow {
        val generation = synchronized(cacheLock) {
            ((generations[board.boardId] ?: 0L) + 1L).also { generations[board.boardId] = it }
        }
        var snapshot = cachedState(board).let { initial ->
            initial.copy(services = initial.services.map { it.copy(status = ServiceStatus.Pending) })
        }
        emit(snapshot)
        feed.updates(board).collect { update ->
            currentCoroutineContext().ensureActive()
            val next = synchronized(cacheLock) {
                if (generations[board.boardId] != generation) return@synchronized null
                if (snapshot.services.none { it.selection.serviceKey() == update.key }) return@synchronized null
                snapshot = snapshot.copy(services = snapshot.services.map { service ->
                    if (service.selection.serviceKey() != update.key) service else when (val result = update.result) {
                        is FetchResult.Success -> {
                            val rows = ArrivalReducer.prepare(result.arrivals, listOf(service.selection))
                            service.copy(cached = CachedService(update.key, rows, result.fetchedAt),
                                status = if (rows.isEmpty()) ServiceStatus.Empty else ServiceStatus.Updated)
                        }
                        is FetchResult.Empty -> service.copy(
                            cached = CachedService(update.key, emptyList(), result.fetchedAt), status = ServiceStatus.Empty)
                        is FetchResult.Failure -> service.copy(status = ServiceStatus.Failed(result.kind))
                        is FetchResult.Unsupported -> service.copy(status = ServiceStatus.Unsupported)
                    }
                })
                if (update.result is FetchResult.Success || update.result is FetchResult.Empty) {
                    snapshot.cache()?.let { cache.write(board.boardId, it) }
                }
                snapshot
            }
            if (next != null) emit(next)
        }
    }

    fun remove(boardId: String) = synchronized(cacheLock) {
        generations[boardId] = (generations[boardId] ?: 0L) + 1L
        cache.remove(boardId)
    }
}
