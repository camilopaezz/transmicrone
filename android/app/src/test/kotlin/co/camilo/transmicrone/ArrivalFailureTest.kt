package co.camilo.transmicrone

import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ArrivalFailureTest {
    private val at = Instant.parse("2026-10-27T12:00:00Z")
    private val first = SelectedServicio("S43", "San Mateo", listOf(1))
    private val second = SelectedServicio("S48", "Soacha", listOf(2))
    private val board = Board("board", "station", PlaceKind.ESTACION, "TM0069", "Ricaurte", null, listOf(first, second))
    private val arrival = Arrival("S43", "San Mateo", 1, null, "Entre 2 y 4 min", null, Occupancy.UNKNOWN, null)

    @Test
    fun partialFailureNamesOnlyFailedSelectionAndWarnsAboutItsPreviousArrivals() {
        val cached = CachedService(first.serviceKey(), listOf(arrival), at)
        val view = BoardView(board, listOf(arrival), Freshness.FailedCached(at, FailureKind.TIMEOUT), services = listOf(
            ServiceArrivals(first, cached, ServiceStatus.Failed(FailureKind.TIMEOUT)),
            ServiceArrivals(second, null, ServiceStatus.Empty),
        ))
        val problem = view.arrivalFailure()!!
        assertTrue(problem.partial)
        assertTrue(problem.hasPreviousArrivals)
        assertEquals(listOf(first), problem.services.map { it.selection })
        assertEquals(FailureKind.TIMEOUT, problem.kind)
        assertEquals(at, problem.oldestFetchedAt)
        assertEquals(at, problem.services.single().fetchedAt)
    }

    @Test
    fun freshSiblingArrivalsAreNotDescribedAsPreviousData() {
        val view = BoardView(board, listOf(arrival), Freshness.FailedCached(at, FailureKind.TRANSPORT), services = listOf(
            ServiceArrivals(first, CachedService(first.serviceKey(), listOf(arrival), at), ServiceStatus.Updated),
            ServiceArrivals(second, null, ServiceStatus.Failed(FailureKind.TRANSPORT)),
        ))
        assertFalse(view.arrivalFailure()!!.hasPreviousArrivals)
        assertNull(view.arrivalFailure()!!.oldestFetchedAt)
        assertEquals(listOf(second), view.arrivalFailure()!!.services.map { it.selection })
    }

    @Test
    fun differentFailuresUseGenericExplanationWithoutHidingAffectedServices() {
        val view = BoardView(board, freshness = Freshness.Failed(FailureKind.TIMEOUT), services = listOf(
            ServiceArrivals(first, null, ServiceStatus.Failed(FailureKind.TIMEOUT)),
            ServiceArrivals(second, null, ServiceStatus.Failed(FailureKind.UNAUTHORIZED)),
        ))
        assertNull(view.arrivalFailure()!!.kind)
        assertFalse(view.arrivalFailure()!!.partial)
        assertEquals(2, view.arrivalFailure()!!.services.size)
    }

    @Test
    fun oldestFailedCacheAgeRetainsIndividualServiceTimes() {
        val older = at.minusSeconds(300)
        val view = BoardView(board, freshness = Freshness.FailedCached(older, FailureKind.TIMEOUT), services = listOf(
            ServiceArrivals(first, CachedService(first.serviceKey(), listOf(arrival), at), ServiceStatus.Failed(FailureKind.TIMEOUT)),
            ServiceArrivals(second, CachedService(second.serviceKey(), emptyList(), older), ServiceStatus.Failed(FailureKind.TIMEOUT)),
        ))
        val problem = view.arrivalFailure()!!
        assertEquals(older, problem.oldestFetchedAt)
        assertEquals(listOf(at, older), problem.services.map { it.fetchedAt })
    }

    @Test
    fun pendingAndRecoveredBoardsHaveNoFailureNotice() {
        val pending = BoardView(board, freshness = Freshness.UpdatingCached(at), services = listOf(
            ServiceArrivals(first, null, ServiceStatus.Failed(FailureKind.TIMEOUT)),
            ServiceArrivals(second, null, ServiceStatus.Pending),
        ))
        assertNull(pending.arrivalFailure())
        assertNull(BoardView(board, freshness = Freshness.Empty(at)).arrivalFailure())
        assertNull(BoardView(board, freshness = Freshness.Updated(at)).arrivalFailure())
        assertNull(BoardView(board, freshness = Freshness.Unsupported).arrivalFailure())
    }
}
