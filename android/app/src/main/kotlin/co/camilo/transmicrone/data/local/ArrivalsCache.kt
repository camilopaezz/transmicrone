package co.camilo.transmicrone.data.local

import co.camilo.transmicrone.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

interface ArrivalsCache {
    fun read(boardId: String): CachedArrivals?
    fun write(boardId: String, cached: CachedArrivals)
    fun remove(boardId: String)
}

class FileArrivalsCache(
    private val dir: File,
    private val json: Json,
) : ArrivalsCache {
    private val cacheJson = Json(json) { ignoreUnknownKeys = true }

    init {
        dir.mkdirs()
    }

    override fun read(boardId: String): CachedArrivals? {
        val file = fileFor(boardId) ?: return null
        if (!file.isFile) return null
        return runCatching {
            val stored = cacheJson.decodeFromString(StoredCache.serializer(), file.readText())
            CachedArrivals(
                arrivals = stored.arrivals.map { it.toArrival() },
                fetchedAt = Instant.ofEpochMilli(stored.fetchedAtEpochMs),
                queryScope = stored.queryScope,
                services = stored.services.map { service -> CachedService(
                    ServiceKey(service.painted, service.destino, service.routeIds),
                    service.arrivals.map { it.toArrival() }, Instant.ofEpochMilli(service.fetchedAtEpochMs),
                ) },
            )
        }.getOrNull()
    }

    override fun write(boardId: String, cached: CachedArrivals) {
        val file = fileFor(boardId) ?: return
        val stored = StoredCache(
            fetchedAtEpochMs = cached.fetchedAt.toEpochMilli(),
            arrivals = cached.arrivals.map { it.toStored() },
            queryScope = cached.queryScope,
            services = cached.services.map { service -> StoredService(
                service.key.painted, service.key.destino, service.key.routeIds,
                service.fetchedAt.toEpochMilli(), service.arrivals.map { it.toStored() },
            ) },
        )
        val temporary = File.createTempFile("arrivals-", ".tmp", dir)
        try {
            temporary.writeText(json.encodeToString(StoredCache.serializer(), stored))
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    override fun remove(boardId: String) {
        fileFor(boardId)?.delete()
    }

    private fun fileFor(boardId: String): File? {
        if (boardId.isBlank() || boardId.any { !it.isLetterOrDigit() && it != '-' }) return null
        return File(dir, "$boardId.json")
    }
}

@Serializable
private data class StoredCache(
    val fetchedAtEpochMs: Long,
    val arrivals: List<StoredArrival> = emptyList(),
    val queryScope: String? = null,
    val services: List<StoredService> = emptyList(),
)

@Serializable
private data class StoredService(
    val painted: String,
    val destino: String,
    val routeIds: List<Int>,
    val fetchedAtEpochMs: Long,
    val arrivals: List<StoredArrival>,
)

@Serializable
private data class StoredArrival(
    val painted: String,
    val destino: String,
    val routeId: Int? = null,
    val nodo: Int? = null,
    val etaPhrase: String,
    val distanceLabel: String? = null,
    val occupancy: String = Occupancy.UNKNOWN.name,
    val colorRuta: String? = null,
    val identity: String = ArrivalIdentity.SERVICIO_ESTIMATE.name,
    val vehicleId: String? = null,
    val label: String? = null,
    val sourceReportEpochMs: Long? = null,
) {
    fun toArrival() = Arrival(
        vehicleId = vehicleId,
        label = label,
        sourceReportedAt = sourceReportEpochMs?.let(Instant::ofEpochMilli),
        painted = painted,
        destino = destino,
        routeId = routeId,
        nodo = nodo,
        etaPhrase = etaPhrase,
        distanceLabel = distanceLabel,
        occupancy = runCatching { Occupancy.valueOf(occupancy) }.getOrDefault(Occupancy.UNKNOWN),
        colorRuta = colorRuta,
        identity = runCatching { ArrivalIdentity.valueOf(identity) }
            .getOrDefault(ArrivalIdentity.SERVICIO_ESTIMATE),
    )
}

private fun Arrival.toStored() = StoredArrival(
    painted = painted,
    destino = destino,
    routeId = routeId,
    nodo = nodo,
    etaPhrase = etaPhrase,
    distanceLabel = distanceLabel,
    occupancy = occupancy.name,
    colorRuta = colorRuta,
    identity = identity.name,
    vehicleId = vehicleId,
    label = label,
    sourceReportEpochMs = sourceReportedAt?.toEpochMilli(),
)
