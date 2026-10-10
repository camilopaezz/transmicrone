package co.camilo.transmicrone.data.feed

import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.ArrivalIdentity
import co.camilo.transmicrone.domain.OccupancyParser
import co.camilo.transmicrone.domain.TextNorm
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ArrivalsParser(private val json: Json) {
    fun parseBoard(body: String): List<Arrival> {
        val element = json.parseToJsonElement(body)
        val array = element as? JsonArray
            ?: error("arrivals body is not a JSON array")
        return array.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            parseRow(obj)
        }
    }

    fun parseVehicles(body: String, fetchedAt: Instant): List<Arrival> {
        val array = json.parseToJsonElement(body) as? JsonArray ?: error("vehicles body is not an array")
        return array.map { node ->
            val obj = node as? JsonObject ?: error("vehicle is not an object")
            val row = parseRow(obj) ?: error("vehicle has no service")
            val id = obj.int("vehicleid") ?: error("vehicle has no identity")
            row.copy(
                identity = ArrivalIdentity.VEHICLE,
                vehicleId = id.toString(),
                label = obj.str("etiqueta"),
                distanceLabel = obj.int("distancia")?.takeIf { it >= 0 }?.let { "$it m" },
                sourceReportedAt = reportTime(obj.str("lasttime"), fetchedAt),
            )
        }
    }

    // The feed supplies only a local clock time. Infer the most recent Bogota
    // date, allowing five minutes of clock skew around midnight.
    private fun reportTime(raw: String?, fetchedAt: Instant): Instant? = runCatching {
        val time = LocalTime.parse(raw?.trim()?.uppercase(Locale.US), DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.US))
        val zone = ZoneId.of("America/Bogota")
        var report = fetchedAt.atZone(zone).toLocalDate().atTime(time).atZone(zone)
        if (report.toInstant().isAfter(fetchedAt.plusSeconds(300))) report = report.minusDays(1)
        report.toInstant()
    }.getOrNull()

    private fun parseRow(obj: JsonObject): Arrival? {
        val painted = obj.str("ruta_extraida")?.trim().orEmpty()
        if (painted.isEmpty()) return null
        val destino = TextNorm.nfc(obj.str("destino_limpio"))
        val phrase = obj.str("labeltiempo")?.trim().orEmpty()
        return Arrival(
            painted = painted,
            destino = destino,
            routeId = obj.int("ruta_sae"),
            nodo = obj.int("nodo"),
            etaPhrase = phrase,
            distanceLabel = obj.str("labelparadero")?.trim()?.ifBlank { null },
            occupancy = OccupancyParser.parse(obj.str("ocupacion_bus") ?: obj.str("ocupacion")),
            colorRuta = TextNorm.hexColor(obj.str("color_ruta")),
            identity = ArrivalIdentity.SERVICIO_ESTIMATE,
        )
    }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] ?: return null
        return runCatching { v.jsonPrimitive.content }.getOrNull()
    }

    private fun JsonObject.int(key: String): Int? {
        val v = this[key] ?: return null
        val prim = runCatching { v.jsonPrimitive }.getOrNull() ?: return null
        prim.intOrNull?.let { return it }
        return prim.content.toIntOrNull()
    }
}
