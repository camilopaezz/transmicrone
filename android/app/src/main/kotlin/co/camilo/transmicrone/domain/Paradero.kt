package co.camilo.transmicrone.domain

object Paradero {
    private val TM = Regex("^TM\\d{4}$")
    private val CENEFA = Regex("^[0-9]{3}[A-Z][0-9]{2}$")
    private val CENEFA_LOOSE = Regex("^[0-9A-Z]{4,12}$")

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("_TM", ignoreCase = true)) return null
        if (trimmed.contains(" ")) return null
        if (TM.matches(trimmed)) return trimmed
        val upper = trimmed.uppercase()
        if (CENEFA.matches(upper)) return upper
        if (CENEFA_LOOSE.matches(upper) && !upper.startsWith("TM")) return upper
        return null
    }
}
