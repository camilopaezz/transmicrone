package co.camilo.transmicrone

import co.camilo.transmicrone.domain.Paradero
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParaderoTest {
    @Test
    fun acceptsFourAndFiveDigitTroncalStationIds() {
        assertEquals("TM0101", Paradero.normalize("TM0101"))
        (90008..90011).forEach { code ->
            assertEquals("TM$code", Paradero.normalize("TM$code"))
        }
    }

    @Test
    fun rejectsGtfsSuffixAndMalformedTroncalStationIds() {
        assertNull(Paradero.normalize("TM90008_TM"))
        assertNull(Paradero.normalize("TM900"))
        assertNull(Paradero.normalize("TM900001"))
        assertNull(Paradero.normalize("TM90A08"))
        assertNull(Paradero.normalize("TM90008 extra"))
    }
}
