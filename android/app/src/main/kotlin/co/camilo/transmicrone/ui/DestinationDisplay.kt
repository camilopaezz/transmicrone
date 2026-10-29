package co.camilo.transmicrone.ui

/** Display spellings for known uppercase catalog/feed names. Unknown words stay intact:
 * guessing title case would damage acronyms, street codes and existing mixed-case names.
 * Never use this value for selections, persistence, matching or feed requests.
 */
private val destinationSpellings = mapOf(
    "PORTAL" to "Portal", "TUNAL" to "Tunal", "SUBA" to "Suba",
    "AMÉRICAS" to "Américas", "SUR" to "Sur", "USME" to "Usme",
    "ELDORADO" to "Eldorado", "UNIVERSIDADES" to "Universidades",
    "JULIO" to "Julio", "DE" to "de",
)
private val destinationWord = Regex("""(?<![\p{L}\p{M}\p{N}])[\p{L}\p{M}]+(?![\p{L}\p{M}\p{N}])""")

fun destinationDisplay(value: String): String = destinationWord.replace(value) {
    destinationSpellings[it.value] ?: it.value
}
