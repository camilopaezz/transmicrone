package co.camilo.transmicrone.data.feed

import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.delay

/** Sample arrivals for UI review, generated from the board's selected services. */
class MockFeedClient(private val clock: Clock) : FeedClient {
    override suspend fun fetchBoard(queryParadero: String): FetchResult = FetchResult.Unsupported(clock.now())

    override suspend fun fetchArrivals(board: Board): FetchResult {
        delay(900)
        val now = clock.now()
        val vehicles = board.kind == PlaceKind.ESTACION
        val rows = board.servicios.flatMapIndexed { serviceIndex, service ->
            List(if (vehicles) 3 else 1) { busIndex ->
                val offset = serviceIndex % 3
                val minutes = 2 + offset + busIndex * 5
                val stale = serviceIndex == 0 && busIndex == 2
                Arrival(
                    painted = service.painted,
                    destino = service.destino,
                    routeId = service.routeIds.firstOrNull(),
                    nodo = null,
                    etaPhrase = "Entre $minutes y ${minutes + 3} min",
                    distanceLabel = "${800 + offset * 400 + busIndex * 2200} m",
                    occupancy = Occupancy.entries[(serviceIndex + busIndex + 2) % Occupancy.entries.size],
                    colorRuta = service.color,
                    identity = if (vehicles) ArrivalIdentity.VEHICLE else ArrivalIdentity.SERVICIO_ESTIMATE,
                    vehicleId = if (vehicles) "demo-$serviceIndex-$busIndex" else null,
                    label = if (vehicles) "T${10412 + serviceIndex * 10 + busIndex}" else null,
                    sourceReportedAt = if (vehicles) now.minusSeconds(if (stale) 240 else 25L + busIndex * 10) else null,
                )
            }
        }
        return if (rows.isEmpty()) FetchResult.Empty(now) else FetchResult.Success(rows, now)
    }
}
