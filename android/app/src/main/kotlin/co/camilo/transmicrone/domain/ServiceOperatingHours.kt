package co.camilo.transmicrone.domain

data class OperatingWindow(val dayGroup: String, val startTime: String, val endTime: String)

data class ServiceOperatingHours(
    val catalogId: String,
    val painted: String,
    val destino: String,
    val fetchedAt: String?,
    val windows: List<OperatingWindow>,
)

/** Match published hours by direction/name. Catalog IDs are not GTFS route IDs. */
fun serviceOperatingHours(option: ServicioOption, hours: List<ServiceOperatingHours>): List<ServiceOperatingHours> {
    val byId = hours.associateBy { it.catalogId }
    return matchingCatalogVariants(option, hours.map { CatalogVariant(it.catalogId, it.painted, it.destino) })
        .mapNotNull { byId[it.catalogId] }
}
