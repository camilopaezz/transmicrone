package co.camilo.transmicrone.domain

object OccupancyParser {
    fun parse(raw: String?): Occupancy {
        return when (raw?.trim().orEmpty().uppercase()) {
            "VACIO" -> Occupancy.VACIO
            "MEDIO" -> Occupancy.MEDIO
            "LLENO" -> Occupancy.LLENO
            else -> Occupancy.UNKNOWN
        }
    }
}
