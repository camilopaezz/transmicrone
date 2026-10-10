package co.camilo.transmicrone.domain

data class RouteStop(
    val code: String,
    val name: String,
    val wagon: String? = null,
    val address: String? = null,
    val stopsHere: Boolean = true,
    val place: Place? = null,
)

data class ServiceRoute(val catalogId: String, val stops: List<RouteStop>)
