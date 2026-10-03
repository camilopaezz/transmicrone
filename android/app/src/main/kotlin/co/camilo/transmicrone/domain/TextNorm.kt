package co.camilo.transmicrone.domain

import java.text.Normalizer

object TextNorm {
    fun nfc(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return Normalizer.normalize(value, Normalizer.Form.NFC)
    }

    fun fold(value: String?): String {
        val nfd = Normalizer.normalize(nfc(value), Normalizer.Form.NFD)
        val stripped = buildString(nfd.length) {
            nfd.forEach { ch ->
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
        return stripped.lowercase()
    }

    fun hexColor(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (!HEX.matches(v)) return null
        return v.uppercase()
    }

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")
}
