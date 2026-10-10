package co.camilo.transmicrone.data.catalog

import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.TextNorm
import co.camilo.transmicrone.domain.CatalogEntry
import co.camilo.transmicrone.domain.CatalogResults
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    val folded = foldSearchText(value)
    return when {
        folded == query -> 0
        folded.startsWith(query) -> 1
        else -> 2
    }
}

interface CatalogRepository {
    suspend fun search(query: String, limit: Int = 40): List<Place>
    suspend fun explore(query: String, limit: Int = Int.MAX_VALUE): CatalogResults
    suspend fun service(painted: String, destino: String): CatalogEntry.Service?
    fun place(placeId: String): Place?
    suspend fun generatedAt(): String?
}

class JsonCatalogRepository(
    private val json: Json,
    private val gzip: Boolean,
    private val opener: () -> InputStream,
) : CatalogRepository {
    @Volatile
    private var loaded: PackedCatalog? = null

    @Volatile
    private var byId: Map<String, Place> = emptyMap()
    private var services: List<CatalogEntry.Service> = emptyList()

    private fun catalog(): PackedCatalog {
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
                val places = packed.places.map { it.toPlace() }
                packed.resolved = places
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
                        )
                    }
                loaded = packed
                return packed
            }
        }
    }

    override suspend fun search(query: String, limit: Int): List<Place> {
        val q = foldSearchText(query)
        if (q.length < 2 || limit <= 0) return emptyList()
        val hits = catalog().resolvedPlaces().filter { place ->
            placeSearchValues(place).any { foldSearchText(it).contains(q) }
        }
        return hits.sortedWith(compareBy<Place> { place -> placeSearchValues(place).minOf { relevanceRank(it, q) } }
            .thenBy { it.kind.ordinal }.thenBy { it.name }.thenBy { it.disambiguator })
            .take(limit)
    }

    override fun place(placeId: String): Place? {
        catalog()
        return byId[placeId]
    }

    override suspend fun explore(query: String, limit: Int): CatalogResults {
        val q = foldSearchText(query)
        if (q.isEmpty() || limit <= 0) return CatalogResults()
        val places = catalog().resolvedPlaces()
        return CatalogResults(
            services = services.filter { service ->
                serviceSearchValues(service).any { foldSearchText(it).contains(q) }
            }.sortedWith(compareBy<CatalogEntry.Service> { service ->
                serviceSearchValues(service).minOf { relevanceRank(it, q) }
            }
                    .thenBy { it.option.painted }.thenBy { it.option.destino }).take(limit),
            places = places.filter { place ->
                placeSearchValues(place).any { foldSearchText(it).contains(q) }
            }.sortedWith(compareBy<Place> { place -> placeSearchValues(place).minOf { relevanceRank(it, q) } }
                    .thenBy { it.kind.ordinal }
                    .thenBy { it.name }.thenBy { it.disambiguator }).take(limit),
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
}

@Serializable
internal data class PackedCatalog(
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val places: List<PackedPlace> = emptyList(),
) {
    @Transient
    var resolved: List<Place> = emptyList()

    fun resolvedPlaces(): List<Place> =
        resolved.ifEmpty { places.map { it.toPlace() } }
}

@Serializable
internal data class PackedPlace(
    @SerialName("place_id") val placeId: String,
    val kind: String,
    val name: String,
    val fold: String,
    val disambiguator: String? = null,
    @SerialName("query_paradero") val queryParadero: String,
    val servicios: List<PackedServicio> = emptyList(),
) {
    fun toPlace(): Place = Place(
        placeId = placeId,
        kind = if (kind == "estacion") PlaceKind.ESTACION else PlaceKind.PARADERO,
        name = TextNorm.nfc(name),
        fold = fold,
        disambiguator = disambiguator?.let(TextNorm::nfc)?.ifBlank { null },
        queryParadero = queryParadero,
        servicios = servicios.map { it.toOption() },
    )
}

@Serializable
internal data class PackedServicio(
    val painted: String,
    val destino: String,
    @SerialName("route_ids") val routeIds: List<Int> = emptyList(),
    val color: String? = null,
) {
    fun toOption(): ServicioOption = ServicioOption(
        painted = painted,
        destino = TextNorm.nfc(destino),
        routeIds = routeIds.distinct(),
        color = TextNorm.hexColor(color),
    )
}
