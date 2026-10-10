package co.camilo.transmicrone.data.feed

import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.Board
import co.camilo.transmicrone.domain.FailureKind
import co.camilo.transmicrone.domain.FetchResult
import co.camilo.transmicrone.domain.Paradero
import co.camilo.transmicrone.domain.PlaceKind
import co.camilo.transmicrone.domain.ArrivalReducer
import co.camilo.transmicrone.domain.FeedSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

interface FeedClient {
    suspend fun fetchBoard(queryParadero: String): FetchResult
    suspend fun fetchArrivals(board: Board): FetchResult = fetchBoard(board.queryParadero)
}

class BodegaFeedClient(
    private val baseUrl: String,
    private val appId: String,
    private val installationId: String,
    private val parser: ArrivalsParser,
    private val clock: Clock,
    private val client: OkHttpClient,
) : FeedClient {
    override suspend fun fetchBoard(queryParadero: String): FetchResult {
        val code = Paradero.normalize(queryParadero)
            ?: return FetchResult.Unsupported(clock.now())
        return request("/paradero/buses", """{"paradero":"$code"}""", vehicles = false)
    }

    override suspend fun fetchArrivals(board: Board): FetchResult = withContext(Dispatchers.IO) {
        if (board.kind != PlaceKind.ESTACION) return@withContext fetchBoard(board.queryParadero)
        val code = Paradero.normalize(board.queryParadero)
            ?: return@withContext FetchResult.Unsupported(clock.now())
        if (board.servicios.any { it.routeIds.isEmpty() }) return@withContext FetchResult.Unsupported(clock.now())
        val resolved = board.servicios.map(FeedSelection::resolve)
        if (resolved.any { it.isEmpty() }) return@withContext FetchResult.Unsupported(clock.now())
        val services = resolved.flatten().distinctBy { Triple(it.painted, it.destino, it.routeIds.toSet()) }
        val semaphore = Semaphore(2)
        val results = coroutineScope {
            services.flatMap { service ->
                service.routeIds.distinct().map { routeId ->
                    async { semaphore.withPermit {
                        val body = buildJsonObject {
                            put("estacion", code)
                            put("ruta", service.painted)
                            put("idRuta", routeId)
                            put("Nombre", service.destino)
                            put("Distancia", 12000)
                        }.toString()
                        when (val result = request("/getServicios", body, vehicles = true)) {
                            is FetchResult.Success -> result.copy(arrivals = ArrivalReducer.filter(result.arrivals, listOf(service)))
                            else -> result
                        }
                    } }
                }
            }.awaitAll()
        }
        // Keep the previous complete board on any request failure. Never turn a
        // rejected vehicle request into a fresh, coarse service summary.
        results.firstOrNull { it is FetchResult.Failure || it is FetchResult.Unsupported }?.let { return@withContext it }
        val rows = ArrivalReducer.dedup(results.filterIsInstance<FetchResult.Success>().flatMap { it.arrivals })
        if (rows.isEmpty()) FetchResult.Empty(clock.now()) else FetchResult.Success(rows, clock.now())
    }

    private suspend fun request(path: String, body: String, vehicles: Boolean): FetchResult {
        val url = baseUrl.trimEnd('/') + path
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("appid", appId)
            .header("uuid", installationId)
            .header("Accept", "application/json")
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { resp ->
                    val text = runCatching { resp.body.string() }.getOrDefault("")
                    val now = clock.now()
                    if (cont.isActive) cont.resume(interpret(resp.code, text, now, vehicles))
                }
            } catch (e: SocketTimeoutException) {
                if (cont.isActive) cont.resume(FetchResult.Failure(FailureKind.TIMEOUT, cached = null))
            } catch (_: IOException) {
                if (call.isCanceled() || !cont.isActive) {
                    cont.cancel()
                } else {
                    cont.resume(FetchResult.Failure(FailureKind.TRANSPORT, cached = null))
                }
            }
        }
    }

    private fun interpret(code: Int, text: String, now: java.time.Instant, vehicles: Boolean): FetchResult {
        val trimmed = text.trim()
        when (code) {
            401 -> return FetchResult.Failure(FailureKind.UNAUTHORIZED, null)
            403 -> return FetchResult.Failure(FailureKind.HOST_REJECTED, null)
            400 -> return FetchResult.Unsupported(now)
        }
        if (code != 200) {
            return FetchResult.Failure(FailureKind.HOST_REJECTED, null)
        }
        if (trimmed.equals("paradero no valido", ignoreCase = true)) {
            return FetchResult.Unsupported(now)
        }
        // Unlike service summaries, /getServicios returns a blank 200 body
        // when no nearby vehicles are available (observed S48 / TM0069).
        if (vehicles && trimmed.isEmpty()) return FetchResult.Empty(now)
        if (trimmed.isEmpty() || trimmed.startsWith("<") || !trimmed.startsWith("[")) {
            return FetchResult.Failure(FailureKind.MALFORMED, null)
        }
        val rows = try {
            if (vehicles) parser.parseVehicles(trimmed, now) else parser.parseBoard(trimmed)
        } catch (_: Exception) {
            return FetchResult.Failure(FailureKind.MALFORMED, null)
        }
        return if (rows.isEmpty()) FetchResult.Empty(now) else FetchResult.Success(rows, now)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun httpClient(connectMs: Int, readMs: Int): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS)
                .writeTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout((readMs + connectMs).toLong(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
    }
}
