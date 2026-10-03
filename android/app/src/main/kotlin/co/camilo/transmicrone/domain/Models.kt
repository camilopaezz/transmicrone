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
    val colorTexto: String?,
    val identity: ArrivalIdentity = ArrivalIdentity.SERVICIO_ESTIMATE,
    val vehicleId: String? = null,
    val label: String? = null,
    val sourceReportedAt: Instant? = null,
)

data class CachedArrivals(
    val arrivals: List<Arrival>,
    val fetchedAt: Instant,
    val emptySuccess: Boolean,
)

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
