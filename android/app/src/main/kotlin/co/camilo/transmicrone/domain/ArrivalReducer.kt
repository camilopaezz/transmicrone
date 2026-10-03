package co.camilo.transmicrone.domain

object ArrivalReducer {
    fun filter(
        rows: List<Arrival>,
        selected: List<SelectedServicio>,
    ): List<Arrival> {
        if (selected.isEmpty()) return emptyList()
        return rows.filter { arrival -> matches(arrival, selected) }
    }

    fun matches(arrival: Arrival, selected: List<SelectedServicio>): Boolean {
        val painted = arrival.painted
        val destino = TextNorm.nfc(arrival.destino)
        return selected.any { sel ->
            if (sel.painted != painted) return@any false
            if (sel.destino.isNotEmpty() && TextNorm.nfc(sel.destino) != destino) return@any false
            val rid = arrival.routeId
            if (rid == null) return@any true
            sel.routeIds.isEmpty() || rid in sel.routeIds
        }
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
