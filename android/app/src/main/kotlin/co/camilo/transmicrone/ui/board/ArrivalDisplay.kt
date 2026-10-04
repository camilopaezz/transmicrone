package co.camilo.transmicrone.ui.board

import co.camilo.transmicrone.domain.Arrival
import co.camilo.transmicrone.domain.ArrivalIdentity
import java.math.BigDecimal

/** Presentation cleanup only. The feed phrase retained on Arrival remains unchanged. */
data class ArrivalDisplay(val phrase: String, val distance: String?)

private val metric = Regex("""(\d+(?:[.,]\d+)?)\s*(kilómetros?|kilometros?|kms?|metros?|mts?|m)\b""", RegexOption.IGNORE_CASE)
private val distanceSuffix = Regex("""\s+a\s*:?\s*(\d+(?:[.,]\d+)?)\s*(kilómetros?|kilometros?|kms?|metros?|mts?|m)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)

private fun metricNumber(number: String, unit: String): BigDecimal {
    // Metre labels can use the Spanish thousands separator; kilometre labels
    // commonly arrive with an upstream decimal point instead.
    val normalized = if (!unit.lowercase().startsWith("k") && Regex("""\d{1,3}\.\d{3}""").matches(number))
        number.replace(".", "") else number.replace(',', '.')
    return normalized.toBigDecimal()
}

private fun metres(number: String, unit: String): BigDecimal =
    metricNumber(number, unit).multiply(if (unit.lowercase().startsWith("k")) BigDecimal(1000) else BigDecimal.ONE)

private fun formatMetric(number: String, unit: String): String =
    metricNumber(number, unit).stripTrailingZeros().toPlainString().replace('.', ',') +
        if (unit.lowercase().startsWith("k")) " km" else " m"

fun arrivalDisplay(arrival: Arrival): ArrivalDisplay {
    val distance = arrival.distanceLabel?.trim()?.takeIf { it.isNotEmpty() }
    var phrase = arrival.etaPhrase.trim()
        .replace(Regex("llegando(?=a\\b)", RegexOption.IGNORE_CASE), "llegando ")
        .replace(Regex("(?:llegando\\s*){2,}", RegexOption.IGNORE_CASE), "llegando ")
        .replace(Regex("\\s+"), " ").trim()
    val suffix = distanceSuffix.find(phrase)
    val exactDistance = distance?.let { metric.matchEntire(it) }
    // A rounded phrase and an exact distance can disagree. Keep both in that case.
    if (suffix != null && exactDistance != null &&
        metres(suffix.groupValues[1], suffix.groupValues[2]).compareTo(
            metres(exactDistance.groupValues[1], exactDistance.groupValues[2])) == 0
    ) phrase = phrase.substring(0, suffix.range.first).trim()
    phrase = metric.replace(phrase) { formatMetric(it.groupValues[1], it.groupValues[2]) }
    val formattedDistance = distance?.let { value -> metric.replace(value) { formatMetric(it.groupValues[1], it.groupValues[2]) } }
    return ArrivalDisplay(phrase, formattedDistance)
}

/** Mutable ETA/distance values never form the identity of an identified vehicle. */
fun arrivalListKeys(arrivals: List<Arrival>): List<String> {
    val counts = mutableMapOf<String, Int>()
    return arrivals.map { arrival ->
        val fields = if (arrival.identity == ArrivalIdentity.VEHICLE) listOf(
            "vehicle", arrival.vehicleId ?: arrival.label.orEmpty(), arrival.painted, arrival.destino,
            arrival.routeId?.toString().orEmpty(), arrival.nodo?.toString().orEmpty(),
        ) else listOf("service", arrival.painted, arrival.destino,
            arrival.routeId?.toString().orEmpty(), arrival.nodo?.toString().orEmpty())
        // Length prefixes avoid collisions if a feed value contains a delimiter.
        val base = fields.joinToString("") { "${it.length}:$it" }
        val occurrence = counts.getOrDefault(base, 0)
        counts[base] = occurrence + 1
        "$base#$occurrence"
    }
}
