package co.camilo.transmicrone.domain

sealed interface CatalogEntry {
    data class Location(val place: Place) : CatalogEntry
    data class Service(
        val option: ServicioOption,
        val places: List<Place>,
        val operatingHours: List<ServiceOperatingHours> = emptyList(),
    ) : CatalogEntry
}

data class CatalogResults(
    val services: List<CatalogEntry.Service> = emptyList(),
    val places: List<Place> = emptyList(),
)
