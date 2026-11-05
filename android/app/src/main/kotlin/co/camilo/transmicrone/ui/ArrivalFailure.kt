package co.camilo.transmicrone.ui

import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.ServiceStatus
import co.camilo.transmicrone.domain.SelectedServicio
import co.camilo.transmicrone.domain.serviceKey
import java.time.Instant

data class FailedArrivalService(val selection: SelectedServicio, val fetchedAt: Instant?)

data class ArrivalFailure(
    val kind: FailureKind?,
    val services: List<FailedArrivalService>,
    val partial: Boolean,
    val hasPreviousArrivals: Boolean,
    val oldestFetchedAt: Instant? = null,
)

/** Wait for the current fetch to finish so progressive updates don't flash errors. */
fun BoardView.arrivalFailure(): ArrivalFailure? {
    if ((freshness == Freshness.Loading || freshness is Freshness.UpdatingCached) && unresolvedFailures.isEmpty()) return null
    val failed = services.filter { it.status is ServiceStatus.Failed || it.selection.serviceKey() in unresolvedFailures }
    if (failed.isNotEmpty()) return ArrivalFailure(
        kind = failed.map { (it.status as? ServiceStatus.Failed)?.kind
            ?: unresolvedFailures.getValue(it.selection.serviceKey()) }.distinct().singleOrNull(),
        services = failed.map { FailedArrivalService(it.selection, it.cached?.fetchedAt) },
        partial = failed.size < services.size,
        hasPreviousArrivals = failed.any { it.arrivals.isNotEmpty() },
        oldestFetchedAt = failed.mapNotNull { it.cached?.fetchedAt }.minOrNull(),
    )
    val kind = when (val state = freshness) {
        is Freshness.Failed -> state.kind
        is Freshness.FailedCached -> state.kind
        else -> return null
    }
    return ArrivalFailure(kind, emptyList(), false, arrivals.isNotEmpty(),
        (freshness as? Freshness.FailedCached)?.cachedAt)
}
