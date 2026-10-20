package co.camilo.transmicrone.benchmark

import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.data.feed.*
import co.camilo.transmicrone.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Opt-in live experiment. scripts/benchmark-arrivals.py supplies an unchanged baseline client. */
class LiveArrivalsBenchmark {
    @Test
    fun compareLiveClients() = runBlocking {
        assumeTrue(System.getenv("ARRIVALS_BENCHMARK") == "1")
        check(BuildConfig.FEED_APPID != "unconfigured") { "Configure the live feed first" }
        val json = Json { ignoreUnknownKeys = true }
        val output = File(checkNotNull(System.getenv("ARRIVALS_BENCHMARK_OUTPUT")))
        val uuid = checkNotNull(System.getenv("ARRIVALS_BENCHMARK_UUID"))
        val rounds = System.getenv("ARRIVALS_BENCHMARK_ROUNDS")?.toInt() ?: 6
        val intervalMs = System.getenv("ARRIVALS_BENCHMARK_INTERVAL_MS")?.toLong() ?: 25000L
        val catalog = json.parseToJsonElement(java.util.zip.GZIPInputStream(
            File("src/main/assets/catalog.json.gz").inputStream()).bufferedReader().use { it.readText() }).jsonObject
        val place = catalog.getValue("places").jsonArray.map { it.jsonObject }
            .single { it["query_paradero"]?.jsonPrimitive?.content == "TM0069" }
        val options = place.getValue("servicio_indices").jsonArray.map {
            catalog.getValue("servicios").jsonArray[it.jsonPrimitive.int].jsonObject
        }
        fun board(codes: Set<String>) = Board("benchmark", "estacion:TM0069", PlaceKind.ESTACION,
            "TM0069", "Ricaurte - NQS", "NQS", options.filter {
                it.getValue("painted").jsonPrimitive.content in codes
            }.map { SelectedServicio(it.getValue("painted").jsonPrimitive.content,
                it.getValue("destino").jsonPrimitive.content,
                it.getValue("route_ids").jsonArray.map { id -> id.jsonPrimitive.int }) })
        val workloads = listOf(board(setOf("S43", "S48")), board(setOf("S43", "S48", "B12", "J23")))
        val clock = Clock { Instant.now() }
        var start = 0L
        val rows = Collections.synchronizedList(mutableListOf<JsonObject>())
        val active = AtomicInteger()
        val peak = AtomicInteger()
        // callEnd includes body consumption. callStart occurs after the baseline semaphore.
        val http = co.camilo.transmicrone.data.feed.BodegaFeedClient.httpClient(
            BuildConfig.HTTP_CONNECT_TIMEOUT_MS, BuildConfig.HTTP_READ_TIMEOUT_MS).newBuilder()
            .eventListenerFactory { object : EventListener() {
                var began = 0L
                var status = 0
                var bytes = 0L
                var protocol = ""
                override fun callStart(call: Call) {
                    began = System.nanoTime()
                    val count = active.incrementAndGet()
                    peak.updateAndGet { max(it, count) }
                }
                override fun responseHeadersEnd(call: Call, response: Response) {
                    status = response.code; protocol = response.protocol.toString()
                }
                override fun responseBodyEnd(call: Call, byteCount: Long) { bytes = byteCount }
                fun finish(call: Call, failed: Boolean) {
                    active.decrementAndGet()
                    val body = okio.Buffer().also { call.request().body?.writeTo(it) }.readUtf8()
                    val request = json.parseToJsonElement(body).jsonObject
                    rows.add(buildJsonObject {
                        put("route", request.getValue("ruta")); put("route_id", request.getValue("idRuta"))
                        put("start_offset_ms", (began - start) / 1e6)
                        put("call_ms", (System.nanoTime() - began) / 1e6)
                        put("http_status", status); put("transport_failure", failed)
                        put("response_bytes", bytes); put("protocol", protocol)
                    })
                }
                override fun callEnd(call: Call) = finish(call, false)
                override fun callFailed(call: Call, ioe: java.io.IOException) = finish(call, true)
            } }.build()
        val current = BodegaFeedClient(BuildConfig.FEED_BASE_URL, BuildConfig.FEED_APPID, uuid,
            ArrivalsParser(json), clock, http)
        val baseline = Class.forName("co.camilo.transmicrone.data.feed.BaselineBodegaFeedClient")
            .constructors.single().newInstance(BuildConfig.FEED_BASE_URL, BuildConfig.FEED_APPID, uuid,
                ArrivalsParser(json), clock, http) as FeedClient
        try {
            output.parentFile?.mkdirs()
            output.writeText("")
            // Both modes share the app's connection pool. AB/BA pairs balance ordering and drift.
            var previousStart = 0L
            for (round in 0..rounds) {
                for ((workloadIndex, board) in workloads.withIndex()) {
                    val modes = if ((round + workloadIndex) % 2 == 0) listOf("capped", "uncapped")
                        else listOf("uncapped", "capped")
                    for (mode in modes) {
                        if (previousStart != 0L) {
                            val wait = intervalMs - (System.nanoTime() - previousStart) / 1_000_000
                            if (wait > 0) Thread.sleep(wait)
                        }
                        rows.clear(); active.set(0); peak.set(0)
                        start = System.nanoTime(); previousStart = start
                        val timestamp = Instant.now()
                        val result = (if (mode == "capped") baseline else current).fetchArrivals(board)
                        val elapsed = (System.nanoTime() - start) / 1e6
                        val record = buildJsonObject {
                            put("timestamp", timestamp.toString()); put("round", round)
                            put("warmup", round == 0); put("mode", mode)
                            put("request_count", board.servicios.sumOf { it.routeIds.size })
                            put("board_ms", elapsed); put("peak_calls", peak.get())
                            put("result", when (result) {
                                is FetchResult.Success -> "success"
                                is FetchResult.Empty -> "empty"
                                is FetchResult.Failure -> "failure:${result.kind}"
                                is FetchResult.Unsupported -> "unsupported"
                            })
                            put("arrival_count", (result as? FetchResult.Success)?.arrivals?.size ?: 0)
                            val arrivals = (result as? FetchResult.Success)?.arrivals.orEmpty()
                            val ages = arrivals.mapNotNull { it.sourceReportedAt }.map {
                                java.time.Duration.between(it, clock.now()).seconds
                            }
                            put("known_source_ages_seconds", JsonArray(ages.map { JsonPrimitive(it) }))
                            put("calls", JsonArray(rows.toList()))
                        }
                        output.appendText(record.toString() + "\n")
                        println("ARRIVALS_BENCHMARK_JSON $record")
                        println("ARRIVALS_BENCHMARK round=$round mode=$mode requests=${rows.size} ms=$elapsed result=${record["result"]}")
                    }
                }
            }
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}
