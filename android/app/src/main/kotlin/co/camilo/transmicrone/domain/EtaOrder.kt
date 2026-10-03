package co.camilo.transmicrone.domain

/**
 * Approximate ordering of Bodega ETA phrases. Does not invent a countdown.
 * Unknown / unparsed phrases sort last. Stable for equal keys.
 */
object EtaOrder {
    private val RANGE = Regex("""entre\s+(\d+)\s*(?:min\.?\s*)?y\s+(\d+)\s+min""", RegexOption.IGNORE_CASE)
    private val SINGLE = Regex("""(?:a\s+)?(\d+)\s+min""", RegexOption.IGNORE_CASE)

    data class Key(val bucket: Int, val low: Int, val high: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val b = bucket.compareTo(other.bucket)
            if (b != 0) return b
            val l = low.compareTo(other.low)
            if (l != 0) return l
            return high.compareTo(other.high)
        }
    }

    fun key(phrase: String?): Key {
        val t = TextNorm.fold(phrase).trim()
        if (t.isEmpty()) return Key(99, 999, 999)
        if (t.contains("llegando")) return Key(0, 0, 0)
        RANGE.find(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            return Key(1, minOf(a, b), maxOf(a, b))
        }
        SINGLE.find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            return Key(1, n, n)
        }
        return Key(90, 999, 999)
    }

    fun compare(a: Arrival, b: Arrival): Int {
        val k = key(a.etaPhrase).compareTo(key(b.etaPhrase))
        if (k != 0) return k
        val p = a.painted.compareTo(b.painted)
        if (p != 0) return p
        val d = a.destino.compareTo(b.destino)
        if (d != 0) return d
        val r = (a.routeId ?: Int.MAX_VALUE).compareTo(b.routeId ?: Int.MAX_VALUE)
        if (r != 0) return r
        return (a.nodo ?: Int.MAX_VALUE).compareTo(b.nodo ?: Int.MAX_VALUE)
    }
}
