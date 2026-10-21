package co.camilo.transmicrone.data.feed

import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

interface FeedClient {
    suspend fun fetchBoard(queryParadero: String): FetchResult
    suspend fun fetchArrivals(board: Board): FetchResult = fetchBoard(board.queryParadero)
    fun updates(board: Board): Flow<ServiceFetchResult> = flow {
        val result = fetchArrivals(board)
        board.servicios.distinctBy { it.serviceKey() }.forEach { service ->
            emit(ServiceFetchResult(service.serviceKey(), result.forService(service)))
        }
    }
}

private fun FetchResult.forService(service: SelectedServicio): FetchResult = when (this) {
    is FetchResult.Success -> ArrivalReducer.prepare(arrivals, listOf(service)).let {
        if (it.isEmpty()) FetchResult.Empty(fetchedAt) else copy(arrivals = it)
    }
    else -> this
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

    // Complete-board consumers such as the standalone benchmark retain this contract.
    override suspend fun fetchArrivals(board: Board): FetchResult {
        val results = updates(board).toList().map { it.result }
        results.firstOrNull { it is FetchResult.Failure || it is FetchResult.Unsupported }?.let { return it }
        val rows = ArrivalReducer.dedup(results.filterIsInstance<FetchResult.Success>().flatMap { it.arrivals })
        return if (rows.isEmpty()) FetchResult.Empty(clock.now()) else FetchResult.Success(rows, clock.now())
    }

    override fun updates(board: Board): Flow<ServiceFetchResult> = channelFlow {
        val services = board.servicios.distinctBy { it.serviceKey() }
        if (board.kind != PlaceKind.ESTACION) {
            val result = fetchBoard(board.queryParadero)
            services.forEach { send(ServiceFetchResult(it.serviceKey(), result.forService(it))) }
            return@channelFlow
        }
        val code = Paradero.normalize(board.queryParadero)
        data class Query(val painted: String, val destino: String, val routeId: Int)
        val plans = services.associateWith { service ->
            if (code == null || service.routeIds.isEmpty()) emptyList() else
                FeedSelection.resolve(service).flatMap { resolved ->
                    resolved.routeIds.distinct().map { Query(resolved.painted, resolved.destino, it) }
                }
        }
        // Launch every unique variant before awaiting any service. Shared resolved
        // selections use one request, while retaining their original saved keys.
        val calls = plans.values.flatten().distinct().associateWith { query ->
            async {
                val body = buildJsonObject {
                    put("estacion", code)
                    put("ruta", query.painted)
                    put("idRuta", query.routeId)
                    put("Nombre", query.destino)
                    put("Distancia", 12000)
                }.toString()
                try {
                    request("/getServicios", body, vehicles = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FetchResult.Failure(FailureKind.TRANSPORT, cached = null)
                }
            }
        }
        plans.forEach { (service, queries) -> launch {
            val results = queries.map { calls.getValue(it) }.awaitAll()
            val result = when {
                queries.isEmpty() -> FetchResult.Unsupported(clock.now())
                else -> results.firstOrNull { it is FetchResult.Failure || it is FetchResult.Unsupported }
                    ?: results.filterIsInstance<FetchResult.Success>().flatMap { it.arrivals }.let { rows ->
                        FetchResult.Success(rows, clock.now()).forService(service)
                    }
            }
            send(ServiceFetchResult(service.serviceKey(), result))
        } }
    }.flowOn(Dispatchers.IO)

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
                    val text = resp.body.string()
                    val now = clock.now()
                    if (cont.isActive) cont.resume(interpret(resp.code, text, now, vehicles))
                }
            } catch (e: SocketTimeoutException) {
                if (cont.isActive) cont.resume(FetchResult.Failure(FailureKind.TIMEOUT, cached = null))
            } catch (e: IOException) {
                // OkHttp also cancels its own call when callTimeout expires.
                // Only coroutine cancellation means the consumer is gone.
                if (cont.isActive) {
                    val kind = if (e is InterruptedIOException) FailureKind.TIMEOUT else FailureKind.TRANSPORT
                    cont.resume(FetchResult.Failure(kind, cached = null))
                } else cont.cancel()
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
