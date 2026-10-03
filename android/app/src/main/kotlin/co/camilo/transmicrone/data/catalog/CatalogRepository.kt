package co.camilo.transmicrone.data.catalog

import co.camilo.transmicrone.domain.Place
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ServicioOption
import co.camilo.transmicrone.domain.TextNorm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream

interface CatalogRepository {
    suspend fun search(query: String, limit: Int = 40): List<Place>
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

    private fun catalog(): PackedCatalog {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            opener().use { raw ->
                val stream = if (gzip) GZIPInputStream(raw) else raw
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                val packed = json.decodeFromString(PackedCatalog.serializer(), text)
                val places = packed.places.map { it.toPlace() }
                packed.resolved = places
                byId = places.associateBy { it.placeId }
                loaded = packed
                return packed
            }
        }
    }

    override suspend fun search(query: String, limit: Int): List<Place> {
        val q = TextNorm.fold(query).trim()
        if (q.length < 2) return emptyList()
        val hits = catalog().resolvedPlaces().filter { it.fold.contains(q) }
        val estaciones = hits.filter { it.kind == PlaceKind.ESTACION }.sortedBy { it.name }
        val paraderos = hits.filter { it.kind == PlaceKind.PARADERO }.sortedBy { it.name }
        return (estaciones + paraderos).take(limit)
    }

    override fun place(placeId: String): Place? {
        catalog()
        return byId[placeId]
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
