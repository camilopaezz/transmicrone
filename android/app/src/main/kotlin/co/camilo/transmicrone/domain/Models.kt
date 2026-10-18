package co.camilo.transmicrone.domain

import java.time.Instant

data class Place(
    val placeId: String,
    val kind: PlaceKind,
    val name: String,
    val fold: String,
    val disambiguator: String?,
    val queryParadero: String,
    val servicios: List<ServicioOption>,
)

enum class PlaceKind { ESTACION, PARADERO }

data class ServicioOption(
    val painted: String,
    val destino: String,
    val routeIds: List<Int>,
    val color: String?,
)

data class SelectedServicio(
    val painted: String,
    val destino: String,
    val routeIds: List<Int>,
    val color: String? = null,
)

data class Board(
    val boardId: String,
    val placeId: String,
    val kind: PlaceKind,
    val queryParadero: String,
    val displayName: String,
    val disambiguator: String?,
    val servicios: List<SelectedServicio>,
)

enum class Occupancy {
    UNKNOWN,
    VACIO,
    MEDIO,
    LLENO,
}

enum class ArrivalIdentity {
    SERVICIO_ESTIMATE,
    VEHICLE,
}

data class Arrival(
    val painted: String,
    val destino: String,
    val routeId: Int?,
    val nodo: Int?,
    val etaPhrase: String,
    val distanceLabel: String?,
    val occupancy: Occupancy,
    val colorRuta: String?,
    val identity: ArrivalIdentity = ArrivalIdentity.SERVICIO_ESTIMATE,
    val vehicleId: String? = null,
    val label: String? = null,
    val sourceReportedAt: Instant? = null,
)

data class CachedArrivals(
    val arrivals: List<Arrival>,
    val fetchedAt: Instant,
    val services: List<CachedService> = emptyList(),
    val queryScope: String? = null,
)

/** Saved selection identity, including its variant set, never a guessed live direction. */
data class ServiceKey(val painted: String, val destino: String, val routeIds: List<Int>)

fun SelectedServicio.serviceKey() = ServiceKey(painted, TextNorm.nfc(destino), routeIds.distinct().sorted())
fun Board.queryScope() = "${kind.name}:${Paradero.normalize(queryParadero) ?: queryParadero}"

data class CachedService(val key: ServiceKey, val arrivals: List<Arrival>, val fetchedAt: Instant)
data class ServiceFetchResult(val key: ServiceKey, val result: FetchResult)

sealed interface ServiceStatus {
    data object Idle : ServiceStatus
    data object CachedEmpty : ServiceStatus
    data object Cached : ServiceStatus
    data object Pending : ServiceStatus
    data object Updated : ServiceStatus
    data object Empty : ServiceStatus
    data class Failed(val kind: FailureKind) : ServiceStatus
    data object Unsupported : ServiceStatus
}

fun CachedService?.restoredStatus(): ServiceStatus = when {
    this == null -> ServiceStatus.Idle
    arrivals.isEmpty() -> ServiceStatus.CachedEmpty
    else -> ServiceStatus.Cached
}

data class ServiceArrivals(
    val selection: SelectedServicio,
    val cached: CachedService?,
    val status: ServiceStatus,
) {
    val arrivals: List<Arrival> get() = cached?.arrivals.orEmpty()
}

data class BoardArrivals(val services: List<ServiceArrivals>, val queryScope: String) {
    val arrivals: List<Arrival> get() = ArrivalReducer.prepare(
        services.flatMap { it.arrivals }, services.map { it.selection },
    )
    val pending: Boolean get() = services.any { it.status == ServiceStatus.Pending }
    fun cache(): CachedArrivals? {
        val saved = services.mapNotNull { it.cached }
        return saved.minOfOrNull { it.fetchedAt }?.let { CachedArrivals(arrivals, it, saved, queryScope) }
    }
}

sealed class FetchResult {
    data class Success(val arrivals: List<Arrival>, val fetchedAt: Instant) : FetchResult()
    data class Empty(val fetchedAt: Instant) : FetchResult()
    data class Unsupported(val fetchedAt: Instant) : FetchResult()
    data class Failure(val kind: FailureKind, val cached: CachedArrivals?) : FetchResult()
}

enum class FailureKind {
    HOST_REJECTED,
    UNAUTHORIZED,
    TRANSPORT,
    MALFORMED,
    TIMEOUT,
}

fun interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
