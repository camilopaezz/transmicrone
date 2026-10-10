package co.camilo.transmicrone.data

import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.domain.ArrivalReducer
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CachedArrivals
import co.camilo.transmicrone.domain.FetchResult

class ArrivalsRepository(
    private val feed: FeedClient,
    private val cache: ArrivalsCache,
) {
    private val cacheLock = Any()
    private val generations = mutableMapOf<String, Long>()

    fun cached(board: Board): CachedArrivals? = cache.read(board.boardId)

    fun remove(boardId: String) = synchronized(cacheLock) {
        generations[boardId] = (generations[boardId] ?: 0L) + 1L
        cache.remove(boardId)
    }

    suspend fun refresh(board: Board): FetchResult {
        val generation = synchronized(cacheLock) { generations[board.boardId] ?: 0L }
        val raw = feed.fetchArrivals(board)
        return when (raw) {
            is FetchResult.Success -> {
                val prepared = ArrivalReducer.prepare(raw.arrivals, board.servicios)
                val result = if (prepared.isEmpty()) {
                    FetchResult.Empty(raw.fetchedAt)
                } else {
                    FetchResult.Success(prepared, raw.fetchedAt)
                }
                write(board, result, generation)
                result
            }
            is FetchResult.Empty -> {
                write(board, raw, generation)
                raw
            }
            is FetchResult.Unsupported -> raw
            is FetchResult.Failure -> raw.copy(cached = cache.read(board.boardId))
        }
    }

    private fun write(board: Board, result: FetchResult, generation: Long) = synchronized(cacheLock) {
        // A response issued before deletion must not recreate that board's cache.
        if ((generations[board.boardId] ?: 0L) != generation) return@synchronized
        when (result) {
            is FetchResult.Success -> cache.write(
                board.boardId,
                CachedArrivals(result.arrivals, result.fetchedAt),
            )
            is FetchResult.Empty -> cache.write(
                board.boardId,
                CachedArrivals(emptyList(), result.fetchedAt),
            )
            else -> Unit
        }
    }
}
