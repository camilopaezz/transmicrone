package co.camilo.transmicrone.data

import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.domain.ArrivalReducer
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.CachedArrivals
import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.FetchResult

class ArrivalsRepository(
    private val feed: FeedClient,
    private val cache: ArrivalsCache,
    private val clock: Clock,
) {
    fun cached(board: Board): CachedArrivals? = cache.read(board.boardId)

    suspend fun refresh(board: Board): FetchResult {
        val raw = feed.fetchArrivals(board)
        return when (raw) {
            is FetchResult.Success -> {
                val prepared = ArrivalReducer.prepare(raw.arrivals, board.servicios)
                val result = if (prepared.isEmpty()) {
                    FetchResult.Empty(raw.fetchedAt)
                } else {
                    FetchResult.Success(prepared, raw.fetchedAt)
                }
                write(board, result)
                result
            }
            is FetchResult.Empty -> {
                cache.write(
                    board.boardId,
                    CachedArrivals(emptyList(), raw.fetchedAt, emptySuccess = true),
                )
                raw
            }
            is FetchResult.Unsupported -> raw
            is FetchResult.Failure -> raw.copy(cached = cache.read(board.boardId))
        }
    }

    private fun write(board: Board, result: FetchResult) {
        when (result) {
            is FetchResult.Success -> cache.write(
                board.boardId,
                CachedArrivals(result.arrivals, result.fetchedAt, emptySuccess = false),
            )
            is FetchResult.Empty -> cache.write(
                board.boardId,
                CachedArrivals(emptyList(), result.fetchedAt, emptySuccess = true),
            )
            else -> Unit
        }
    }
}
