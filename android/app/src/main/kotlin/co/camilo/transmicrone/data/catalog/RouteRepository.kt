package co.camilo.transmicrone.data.catalog

import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface RouteRepository {
    suspend fun load(option: ServicioOption): List<ServiceRoute>
}

class CatalogRouteRepository(private val catalog: CatalogRepository) : RouteRepository {
    override suspend fun load(option: ServicioOption): List<ServiceRoute> = catalog.routes(option)
}

class PublicRouteRepository(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build(),
    private val baseUrl: String = "https://api.buscador-rutas.transmilenio.gov.co/loader.php",
) : RouteRepository {
    private val cache = ConcurrentHashMap<ServicioOption, List<ServiceRoute>>()

    override suspend fun load(option: ServicioOption): List<ServiceRoute> {
        cache[option]?.let { return it }
        val destinations = option.destino.split("||").map { destination ->
            val raw = destination.trim()
            val prefix = componentCodePrefix(raw)
            RouteDestination(
                code = prefix?.first,
                name = routeName(prefix?.second ?: raw),
            )
        }
        val componentCodes = destinations.mapNotNull { it.code }.toSet()
        val primaryCandidates = searchCandidates(option.painted)
        // These modifiers apply to the whole service even when GTFS puts them
        // only on the second endpoint. Prefer that sibling when the API has it.
        val qualifiers = listOf("ciclovia", "festivo").filter { qualifier ->
            destinations.any { it.name.endsWith(" $qualifier") }
        }

        val matchingByDestination = destinations.map { destination ->
            matchingCandidates(primaryCandidates, listOf(destination), componentCodes, qualifiers, requireComponentCode = false)
        }.toMutableList()
        destinations.forEachIndexed { index, destination ->
            if (matchingByDestination[index].isEmpty() && destination.code != null) {
                // A painted-code response can contain only one side of a
                // composite route. Query component codes only for unresolved sides.
                val componentCandidates = searchCandidates(destination.code)
                matchingByDestination[index] = matchingCandidates(
                    componentCandidates, listOf(destination), componentCodes, qualifiers, requireComponentCode = true,
                )
            }
        }
        val matching = matchingByDestination.flatten().distinctBy { text(it, "id") }
        // Catalog routeIds belong to GTFS, not the route API's id namespace.
        if (matching.isEmpty()) return emptyList()
        val semaphore = kotlinx.coroutines.sync.Semaphore(2)
        val routes = coroutineScope {
            matching.map { candidate -> async {
                semaphore.acquire()
                try {
                    val id = text(candidate, "id")
                    val code = text(candidate, "codigo")
                    val info = request("infoRuta", mapOf("idRuta" to id,
                        "nombre" to text(candidate, "nombre"), "codigo" to code))
                    ServiceRoute(id, parseRouteStops(info))
                } finally { semaphore.release() }
            } }.awaitAll()
        }.filter { it.stops.isNotEmpty() }.distinctBy { route -> route.stops.map { listOf(it.code, it.name, it.wagon, it.address, it.stopsHere.toString()) } }
        if (routes.isNotEmpty()) cache[option] = routes
        return routes
    }

    private suspend fun searchCandidates(code: String): List<JsonObject> {
        val response = request("searchRutaByTipo", mapOf("tipo_ruta" to "TIPORUTA", "search" to code))
        return response["lista_rutas"]?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { text(it, "codigo") == code }
    }

    private fun matchingCandidates(
        candidates: List<JsonObject>,
        destinations: List<RouteDestination>,
        componentCodes: Set<String>,
        qualifiers: List<String>,
        requireComponentCode: Boolean,
    ): List<JsonObject> = destinations.flatMap { destination ->
        val eligible = candidates.filter { candidate ->
            val candidateCode = text(candidate, "codigo")
            (!requireComponentCode || destination.code == candidateCode) &&
                (destination.code == null || candidateCode == destination.code || candidateCode !in componentCodes)
        }
        fun name(candidate: JsonObject) = routeName(stripRecognizedPrefix(text(candidate, "nombre"), componentCodes))
        val explicitQualifier = qualifiers.firstOrNull { destination.name.endsWith(" $it") }
        val qualified = when {
            explicitQualifier != null -> eligible.filter { name(it) == destination.name }
            else -> eligible.filter { candidate ->
                qualifiers.any { name(candidate) == "${destination.name} $it" }
            }
        }
        qualified.ifEmpty { eligible.filter { name(it) == destination.name } }
    }.distinctBy { text(it, "id") }

    private suspend fun request(function: String, parameters: Map<String, String>): JsonObject {
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("lServicio", "Rutas")
            .addQueryParameter("lTipo", "api").addQueryParameter("lFuncion", function)
        parameters.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val call = client.newCall(Request.Builder().url(url.build()).header("Accept", "application/json").build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw IOException("Catalog HTTP ${it.code}")
                            json.parseToJsonElement(it.body.string()).jsonObject
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}

private data class RouteDestination(val code: String?, val name: String)

private fun componentCodePrefix(value: String): Pair<String, String>? {
    val match = Regex("^([A-Z0-9][A-Z0-9-]*\\d[A-Z0-9-]*)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value.trim())
        ?: return null
    return match.groupValues[1].uppercase() to match.groupValues[2].trim()
}

private fun stripRecognizedPrefix(value: String, componentCodes: Set<String>): String {
    val prefix = componentCodePrefix(value) ?: return value
    return if (prefix.first in componentCodes) prefix.second else value
}

private fun routeName(value: String): String = TextNorm.fold(value)
    .replace(Regex("\\s+-\\s+"), " ")
    .replace(Regex("\\s+"), " ").trim()

private fun text(row: JsonObject, key: String): String = row[key]?.jsonPrimitive?.contentOrNull.orEmpty()

internal fun parseRouteStops(info: JsonObject): List<RouteStop> {
    val recorrido = info["recorrido"] ?: throw IllegalArgumentException("Missing recorrido")
    val rows = when (recorrido) {
        is JsonArray -> recorrido
        is JsonObject -> recorrido["data"]?.jsonArray ?: throw IllegalArgumentException("Missing recorrido.data")
        else -> throw IllegalArgumentException("Invalid recorrido")
    }.map { it.jsonObject }
    // The array itself is ordered when the API omits positions. Keep repeated visits.
    val ordered = if (rows.all { it["posicion"]?.jsonPrimitive?.doubleOrNull != null }) {
        rows.sortedBy { it["posicion"]!!.jsonPrimitive.double }
    } else rows
    return ordered.map { row ->
        RouteStop(
            code = text(row, "codigo"), name = TextNorm.nfc(text(row, "nombre")),
            wagon = text(row, "vagon").trim().takeIf { it.isNotBlank() && it != "0" },
            address = TextNorm.nfc(text(row, "direccion")).takeIf { it.isNotBlank() },
            stopsHere = text(row, "parada") != "0",
        )
    }
}
