package co.camilo.transmicrone

import co.camilo.transmicrone.data.feed.ArrivalsParser
import co.camilo.transmicrone.data.feed.BodegaFeedClient
import co.camilo.transmicrone.domain.*
import co.camilo.transmicrone.ui.destinationDisplay
import co.camilo.transmicrone.ui.key
import co.camilo.transmicrone.ui.board.arrivalListKeys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale

class DestinationDisplayTest {
    @Test fun formatsKnownNamesAndSpanishConnectorsWithoutGuessingAcronyms() {
        mapOf(
            "PORTAL TUNAL" to "Portal Tunal", "PORTAL 80" to "Portal 80",
            "PORTAL AMÉRICAS" to "Portal Américas", "PORTAL 20 DE JULIO" to "Portal 20 de Julio",
            "P.SUBA || P. ELDORADO" to "P.Suba || P. Eldorado",
            "UNIVERSIDADES" to "Universidades", "P.USME" to "P.Usme",
            "Portal Suba" to "Portal Suba", "Portal Américas" to "Portal Américas",
            "Ricaurte - NQS" to "Ricaurte - NQS", "CAD" to "CAD", "CAN" to "CAN",
            "SENA SITP TM" to "SENA SITP TM", "KR 7 - CLL 107A" to "KR 7 - CLL 107A",
            "San Juan de Dios" to "San Juan de Dios", "TransMiCable" to "TransMiCable",
            "" to "", "  PORTAL 80  " to "  Portal 80  ",
        ).forEach { (raw, expected) ->
            assertEquals(raw, expected, destinationDisplay(raw))
            assertEquals(expected, destinationDisplay(destinationDisplay(raw)))
        }
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("Universidades", destinationDisplay("UNIVERSIDADES"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun displayLeavesParsedArrivalSavedSelectionAndActualFeedQueryUnchanged() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val raw = "PORTAL TUNAL"
        val row = ArrivalsParser(json).parseBoard("""[{"ruta_extraida":"H21","destino_limpio":"PORTAL TUNAL","ruta_sae":123,"labeltiempo":"Servicio llegando"}]""").single()
        val selection = SelectedServicio("H21", raw, listOf(123))
        val keys = arrivalListKeys(listOf(row))
        val selectionKey = selection.key()
        assertEquals("Portal Tunal", destinationDisplay(row.destino))
        assertEquals("Portal Tunal", destinationDisplay(selection.destino))
        assertEquals(raw, row.destino)
        assertEquals(raw, selection.destino)
        assertEquals(keys, arrivalListKeys(listOf(row)))
        assertEquals(selectionKey, selection.key())
        assertNotEquals(selectionKey, selection.copy(destino = "Portal Tunal").key())
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        val http = BodegaFeedClient.httpClient(2000, 2000)
        try {
            val client = BodegaFeedClient(server.url("/").toString(), "test", "installation", ArrivalsParser(json), Clock { Instant.now() }, http)
            client.fetchArrivals(Board("test", "estacion:TM0069", PlaceKind.ESTACION, "TM0069", "Ricaurte", "NQS", listOf(selection)))
            val request = server.takeRequest()
            assertEquals("/getServicios", request.path)
            val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(raw, body["Nombre"]!!.jsonPrimitive.content)
            assertEquals("H21", body["ruta"]!!.jsonPrimitive.content)
            assertEquals(123, body["idRuta"]!!.jsonPrimitive.int)
        } finally {
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
