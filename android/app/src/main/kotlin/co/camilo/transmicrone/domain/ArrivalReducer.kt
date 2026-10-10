package co.camilo.transmicrone.domain

object ArrivalReducer {
    private val leadingServiceCode = Regex("^[A-Z][0-9]{1,3}\\s+")

    fun filter(
        rows: List<Arrival>,
        selected: List<SelectedServicio>,
    ): List<Arrival> {
        if (selected.isEmpty()) return emptyList()
        return rows.filter { arrival -> matches(arrival, selected) }
    }

    fun matches(arrival: Arrival, selected: List<SelectedServicio>): Boolean {
        val painted = arrival.painted
        val destino = normalizeDestination(arrival.destino)
        return selected.any { original ->
            FeedSelection.resolve(original).any { sel ->
                val rid = arrival.routeId
                sel.painted == painted &&
                    (sel.destino.isEmpty() || destino in destinationOptions(sel.destino, sel.painted)) &&
                    (rid != null || !FeedSelection.isComposite(original)) &&
                    (rid == null || sel.routeIds.isEmpty() || rid in sel.routeIds)
            }
        }
    }

    private fun destinationOptions(value: String, painted: String): Set<String> = value
        .split("||")
        .mapNotNull { side ->
            val normalized = normalizeDestination(side)
            val prefix = leadingServiceCode.find(normalized)
            when {
                prefix == null -> normalized
                prefix.value.trim() == painted -> normalized.removeRange(prefix.range)
                else -> null
            }
        }
        .filter(String::isNotEmpty)
        .toSet()

    private fun normalizeDestination(value: String): String {
        val nfc = TextNorm.nfc(value)
        val result = StringBuilder(nfc.length)
        var pendingSpace = false
        for (character in nfc) {
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

    /**
     * Keep the newest observation of an identified bus across route variants.
     * Service estimates still deduplicate only when all reported fields agree.
     */
    fun dedup(rows: List<Arrival>): List<Arrival> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Arrival>(rows.size)
        for (row in rows.sortedByDescending { it.sourceReportedAt }) {
            val key = if (row.identity == ArrivalIdentity.VEHICLE && row.vehicleId != null) {
                listOf(row.vehicleId, row.painted, row.destino).joinToString("\u0001")
            } else listOf(
                row.identity.name,
                row.vehicleId.orEmpty(),
                row.painted,
                row.destino,
                row.routeId?.toString().orEmpty(),
                row.nodo?.toString().orEmpty(),
                row.etaPhrase,
                row.distanceLabel.orEmpty(),
            ).joinToString("\u0001")
            if (seen.add(key)) out.add(row)
        }
        return out
    }

    fun sort(rows: List<Arrival>): List<Arrival> = rows.sortedWith { a, b -> EtaOrder.compare(a, b) }

    fun prepare(rows: List<Arrival>, selected: List<SelectedServicio>): List<Arrival> {
        return sort(dedup(filter(rows, selected)))
    }
}
