package co.camilo.transmicrone.data.catalog

import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.TextNorm
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import co.camilo.transmicrone.domain.OperatingWindow
import co.camilo.transmicrone.domain.ServiceOperatingHours
import co.camilo.transmicrone.domain.CatalogVariant
import co.camilo.transmicrone.domain.ServiceRoute
import co.camilo.transmicrone.domain.RouteStop
import co.camilo.transmicrone.domain.matchingCatalogVariants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream

private const val SUPPORTED_CATALOG_SCHEMA_VERSION = 1

private fun foldSearchText(value: String): String {
    val folded = TextNorm.fold(value)
    val result = StringBuilder(folded.length)
    var pendingSpace = false
    for (character in folded) {
        if (character.isWhitespace() || Character.isSpaceChar(character)) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            result.append(character)
            pendingSpace = false
        }
    }
    return result.toString()
}

private fun relevanceRank(value: String, query: String): Int {
    return when {
        value == query -> 0
        value.startsWith(query) -> 1
        else -> 2
    }
}

private data class SearchEntry<T>(val value: T, val foldedFields: List<String>)
private data class SearchHit<T>(val value: T, val rank: Int)

private fun <T> matchingHits(index: List<SearchEntry<T>>, query: String): List<SearchHit<T>> =
    index.mapNotNull { entry ->
        if (entry.foldedFields.any { it.contains(query) }) {
            SearchHit(entry.value, entry.foldedFields.minOf { relevanceRank(it, query) })
        } else {
            null
        }
    }

interface CatalogRepository {
    suspend fun search(query: String, limit: Int = 40): List<Place>
    suspend fun explore(query: String, limit: Int = Int.MAX_VALUE): CatalogResults
    suspend fun service(painted: String, destino: String): CatalogEntry.Service?
    fun place(placeId: String): Place?
    suspend fun generatedAt(): String?
    suspend fun routes(option: ServicioOption): List<ServiceRoute> = emptyList()
}

class JsonCatalogRepository(
    private val json: Json,
    private val gzip: Boolean,
    private val opener: () -> InputStream,
) : CatalogRepository {
    @Volatile
    private var loaded: CatalogMetadata? = null

    @Volatile
    private var byId: Map<String, Place> = emptyMap()
    private var services: List<CatalogEntry.Service> = emptyList()
    private var routeVariants: List<CatalogVariant> = emptyList()
    private var routesById: Map<String, ServiceRoute> = emptyMap()
    // Published together with services by the volatile write to loaded.
    private var placeSearchIndex: List<SearchEntry<Place>> = emptyList()
    private var serviceSearchIndex: List<SearchEntry<CatalogEntry.Service>> = emptyList()

    private fun catalog(): CatalogMetadata {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            opener().use { raw ->
                val stream = if (gzip) GZIPInputStream(raw) else raw
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                val packed = json.decodeFromString(PackedCatalog.serializer(), text)
                require(packed.schemaVersion == SUPPORTED_CATALOG_SCHEMA_VERSION) {
                    "Unsupported catalog schema_version: ${packed.schemaVersion}"
                }
                // These caches are only needed while resolving the packed tree.
                val options = HashMap<PackedServicio, ServicioOption>()
                val routeIds = HashMap<List<Int>, List<Int>>()
                val colors = HashMap<String, String?>()
                fun resolve(row: PackedServicio): ServicioOption = options.getOrPut(row) {
                    ServicioOption(
                        painted = row.painted,
                        destino = TextNorm.nfc(row.destino),
                        routeIds = routeIds.getOrPut(row.routeIds) { row.routeIds.distinct() },
                        color = row.color?.let { color ->
                            if (!colors.containsKey(color)) colors[color] = TextNorm.hexColor(color)
                            colors[color]
                        },
                    )
                }
                val dictionary = packed.servicios.map(::resolve)
                val hours = packed.serviceHours.map { row ->
                    ServiceOperatingHours(row.catalogId, row.painted, TextNorm.nfc(row.destino), row.fetchedAt,
                        row.windows.map { OperatingWindow(it.dayGroup, it.startTime, it.endTime) })
                }
                val hoursById = hours.associateBy { it.catalogId }
                val hourVariants = hours.map { CatalogVariant(it.catalogId, it.painted, it.destino) }
                routeVariants = packed.serviceRoutes.map { CatalogVariant(it.catalogId, it.painted, TextNorm.nfc(it.destino)) }
                routesById = packed.serviceRoutes.associate { row ->
                    row.catalogId to ServiceRoute(row.catalogId, row.stops.map { stop ->
                        RouteStop(stop.code, TextNorm.nfc(stop.name).ifBlank { stop.code },
                            stop.wagon?.trim()?.takeIf { it.isNotBlank() && it != "0" },
                            stop.address?.let(TextNorm::nfc)?.takeIf { it.isNotBlank() }, stop.stopsHere)
                    })
                }
                val places = packed.places.map { place ->
                    val resolved = place.servicioIndices?.let { indices ->
                        require(place.servicios.isEmpty()) { "Place ${place.placeId} has both servicio shapes" }
                        indices.map { index ->
                            require(index in dictionary.indices) { "Invalid servicio index $index for ${place.placeId}" }
                            dictionary[index]
                        }
                    } ?: place.servicios.map(::resolve)
                    place.toPlace(resolved)
                }
                byId = places.associateBy { it.placeId }
                services = places.flatMap { place -> place.servicios.map { it to place } }
                    .groupBy { (option, _) -> option.painted to option.destino }
                    .values.map { rows ->
                        CatalogEntry.Service(
                            rows.first().first.copy(
                                routeIds = rows.flatMap { it.first.routeIds }.distinct().sorted(),
                                color = rows.firstNotNullOfOrNull { it.first.color },
                            ),
                            rows.map { it.second }.distinctBy { it.placeId }
                                .sortedWith(compareBy<Place> { it.kind.ordinal }.thenBy { it.name }.thenBy { it.disambiguator }),
                            matchingCatalogVariants(rows.first().first, hourVariants).mapNotNull { hoursById[it.catalogId] },
                        )
                    }
                placeSearchIndex = places.map { SearchEntry(it, placeSearchValues(it).map(::foldSearchText)) }
                serviceSearchIndex = services.map { SearchEntry(it, serviceSearchValues(it).map(::foldSearchText)) }
                val metadata = CatalogMetadata(packed.generatedAt)
                // Publish every domain index before marking the catalog loaded. Do not retain packed data.
                loaded = metadata
                return metadata
            }
        }
    }

    override suspend fun search(query: String, limit: Int): List<Place> {
        val q = foldSearchText(query)
        if (q.length < 2 || limit <= 0) return emptyList()
        catalog()
        return matchingPlaces(q, limit)
    }

    private fun matchingPlaces(query: String, limit: Int): List<Place> =
        matchingHits(placeSearchIndex, query)
            .sortedWith(compareBy<SearchHit<Place>> { it.rank }
                .thenBy { it.value.kind.ordinal }.thenBy { it.value.name }.thenBy { it.value.disambiguator })
            .take(limit).map { it.value }

    override fun place(placeId: String): Place? {
        catalog()
        return byId[placeId]
    }

    override suspend fun explore(query: String, limit: Int): CatalogResults {
        val q = foldSearchText(query)
        if (q.isEmpty() || limit <= 0) return CatalogResults()
        catalog()
        return CatalogResults(
            services = matchingHits(serviceSearchIndex, q)
                .sortedWith(compareBy<SearchHit<CatalogEntry.Service>> { it.rank }
                    .thenBy { it.value.option.painted }.thenBy { it.value.option.destino })
                .take(limit).map { it.value },
            places = matchingPlaces(q, limit),
        )
    }

    private fun placeSearchValues(place: Place): List<String> = listOfNotNull(
        place.fold,
        place.name,
        place.disambiguator,
        place.queryParadero,
        "${place.name} ${place.disambiguator.orEmpty()} ${place.queryParadero}",
    )

    private fun serviceSearchValues(service: CatalogEntry.Service): List<String> =
        listOf(service.option.painted) + service.option.destino.split("||")

    override suspend fun service(painted: String, destino: String): CatalogEntry.Service? {
        catalog()
        return services.find { it.option.painted == painted && it.option.destino == destino }
    }

    override suspend fun generatedAt(): String? = catalog().generatedAt

    override suspend fun routes(option: ServicioOption): List<ServiceRoute> {
        catalog()
        return matchingCatalogVariants(option, routeVariants).mapNotNull { routesById[it.catalogId] }
            .filter { it.stops.isNotEmpty() }
            // Equal stops can belong to distinct destinations or operating schedules.
            .distinctBy { it.catalogId }
    }
}

private data class CatalogMetadata(val generatedAt: String?)

@Serializable
internal data class PackedCatalog(
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val servicios: List<PackedServicio> = emptyList(),
    val places: List<PackedPlace> = emptyList(),
    @SerialName("service_hours") val serviceHours: List<PackedServiceHours> = emptyList(),
    @SerialName("service_routes") val serviceRoutes: List<PackedServiceRoute> = emptyList(),
)

@Serializable
internal data class PackedServiceRoute(
    @SerialName("catalog_id") val catalogId: String,
    val painted: String,
    val destino: String,
    val stops: List<PackedRouteStop> = emptyList(),
)

@Serializable
internal data class PackedRouteStop(
    val code: String,
    val name: String,
    val wagon: String? = null,
    val address: String? = null,
    @SerialName("stops_here") val stopsHere: Boolean = true,
)

@Serializable
internal data class PackedServiceHours(
    @SerialName("catalog_id") val catalogId: String,
    val painted: String,
    val destino: String,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    val windows: List<PackedOperatingWindow> = emptyList(),
)

@Serializable
internal data class PackedOperatingWindow(
    @SerialName("day_group") val dayGroup: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

@Serializable
internal data class PackedPlace(
    @SerialName("place_id") val placeId: String,
    val kind: String,
    val name: String,
    val fold: String,
    val disambiguator: String? = null,
    @SerialName("query_paradero") val queryParadero: String,
    val servicios: List<PackedServicio> = emptyList(),
    @SerialName("servicio_indices") val servicioIndices: List<Int>? = null,
) {
    fun toPlace(options: List<ServicioOption>): Place = Place(
        placeId = placeId,
        kind = if (kind == "estacion") PlaceKind.ESTACION else PlaceKind.PARADERO,
        name = TextNorm.nfc(name),
        fold = fold,
        disambiguator = disambiguator?.let(TextNorm::nfc)?.ifBlank { null },
        queryParadero = queryParadero,
        servicios = options,
    )
}

@Serializable
internal data class PackedServicio(
    val painted: String,
    val destino: String,
    @SerialName("route_ids") val routeIds: List<Int> = emptyList(),
    val color: String? = null,
)
