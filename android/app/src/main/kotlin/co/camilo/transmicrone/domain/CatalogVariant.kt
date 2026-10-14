package co.camilo.transmicrone.domain

data class CatalogVariant(val catalogId: String, val painted: String, val destino: String)

/** Resolve public-catalog directions, including paired codes and holiday siblings. */
fun matchingCatalogVariants(option: ServicioOption, variants: List<CatalogVariant>): List<CatalogVariant> {
    fun name(value: String) = TextNorm.fold(value).replace(Regex("\\s+-\\s+"), " ")
        .replace(Regex("\\s+"), " ").trim()
    val prefix = Regex("^([A-Z0-9][A-Z0-9-]*\\d[A-Z0-9-]*)\\s+(.+)$", RegexOption.IGNORE_CASE)
    val directions = option.destino.split("||").map { raw ->
        val match = prefix.matchEntire(raw.trim())
        match?.groupValues?.get(1)?.uppercase() to name(match?.groupValues?.get(2) ?: raw)
    }
    val componentCodes = directions.mapNotNull { it.first }.toSet()
    val qualifiers = listOf("ciclovia", "festivo").filter { qualifier ->
        directions.any { it.second.endsWith(" $qualifier") }
    }
    fun matching(candidates: List<CatalogVariant>, code: String?, destination: String): List<CatalogVariant> {
        val eligible = candidates.filter { code == null || it.painted == code || it.painted !in componentCodes }
        fun destinationOf(value: CatalogVariant): String {
            val match = prefix.matchEntire(value.destino.trim())
            return name(match?.takeIf { it.groupValues[1].uppercase() in componentCodes }?.groupValues?.get(2) ?: value.destino)
        }
        val qualified = if (qualifiers.any { destination.endsWith(" $it") }) {
            eligible.filter { destinationOf(it) == destination }
        } else {
            eligible.filter { candidate -> qualifiers.any { destinationOf(candidate) == "$destination $it" } }
        }
        return qualified.ifEmpty { eligible.filter { destinationOf(it) == destination } }
    }
    val primary = variants.filter { it.painted == option.painted }
    return directions.flatMap { (code, destination) ->
        matching(primary, code, destination).ifEmpty {
            if (code == null) emptyList() else matching(variants.filter { it.painted == code }, code, destination)
        }
    }.distinctBy { it.catalogId }
}
