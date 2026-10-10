package co.camilo.transmicrone.domain

/** Resolve catalog line selections without changing persisted board keys.
 *
 * A composite GTFS name does not establish which live direction owns each ID.
 * Keep verified mappings explicit; unknown IDs must not become empty successes.
 * Evidence and the deliberately limited mapping are recorded in docs/arrivals.md.
 */
object FeedSelection {
    private val compositeCode = Regex("^[A-Z]{2}[0-9]{1,3}$")

    fun isComposite(service: SelectedServicio): Boolean =
        compositeCode.matches(service.painted) && service.destino.contains("||")

    fun resolve(service: SelectedServicio): List<SelectedServicio> {
        if (!isComposite(service)) return listOf(service)
        if (service.painted != "FZ63" ||
            TextNorm.nfc(service.destino).trim() != "F63 Pradera || Z63 Tibanica" ||
            service.routeIds.isEmpty() || service.routeIds.any { it != 13789 }
        ) return emptyList()
        return listOf(SelectedServicio("Z63", "Tibanica", listOf(13789), service.color))
    }
}
