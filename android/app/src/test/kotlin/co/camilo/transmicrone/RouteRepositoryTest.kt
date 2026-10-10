package co.camilo.transmicrone

import co.camilo.transmicrone.data.catalog.PublicRouteRepository
import co.camilo.transmicrone.data.catalog.parseRouteStops
import co.camilo.transmicrone.domain.ServicioOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RouteRepositoryTest {
    private fun fixture() = javaClass.classLoader!!.getResource("route-e48.json")!!.readText()

    @Test fun e48IsOrderedFromSanMateoToCadWithAllElevenStops() {
        val info = Json.parseToJsonElement(fixture()).jsonObject
        val stops = parseRouteStops(info)
        assertEquals(listOf("San Mateo", "Terreros - H. Cardiovascular", "León XIII", "La Despensa", "Bosa",
            "Sevillana", "Venecia", "General Santander", "Santa Isabel", "Ricaurte", "CAD"), stops.map { it.name })
        assertEquals("A", stops[2].wagon)
        val reversed = JsonObject(info + ("recorrido" to JsonArray(info["recorrido"]!!.jsonArray.reversed())))
        assertEquals(stops, parseRouteStops(reversed))
    }

    @Test fun repeatedVisitsAndPassThroughStationsKeepTheirPosition() {
        val stops = parseRouteStops(Json.parseToJsonElement("""{"recorrido":{"data":[
            {"codigo":"A","nombre":"Alpha","posicion":0,"parada":"1"},
            {"codigo":"B","nombre":"Beta","posicion":1,"parada":"0"},
            {"codigo":"A","nombre":"Alpha","posicion":2,"parada":"1"}
        ]}}""").jsonObject)
        assertEquals(listOf("A", "B", "A"), stops.map { it.code })
        assertFalse(stops[1].stopsHere)
        assertTrue(stops.last().stopsHere)
    }

    @Test fun resolvesCorrectDestinationAndCachesSuccessfulRoute() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                {"codigo":"E48","nombre":"Other","id":"13266"},
                {"codigo":"E48","nombre":"CAD","id":"12444"}
            ]}"""))
            server.enqueue(MockResponse().setBody(fixture()))
            val repo = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
            val option = ServicioOption("E48", "CAD", listOf(13266), null)
            val routes = repo.load(option)
            assertEquals(11, routes.single().stops.size)
            assertEquals(routes, repo.load(option))
            assertEquals(2, server.requestCount)
            assertEquals("E48", server.takeRequest().requestUrl!!.queryParameter("search"))
            val detail = server.takeRequest().requestUrl!!
            assertEquals("12444", detail.queryParameter("idRuta"))
            assertEquals("CAD", detail.queryParameter("nombre"))
        } finally { server.shutdown() }
    }

    @Test fun pairedHeadsignLoadsBothDirectionsDespiteGtfsIdCollision() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                {"codigo":"8-1","nombre":"Kennedy Central","id":"562"},
                {"codigo":"8-1","nombre":"Banderas","id":"2468"}
            ]}"""))
            for (name in listOf("Kennedy Central", "Banderas")) server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"$name","nombre":"$name"}]}"""))
            val repo = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
            assertEquals(setOf("562", "2468"), repo.load(ServicioOption("8-1", "Kennedy Central || Banderas", listOf(562), null)).map { it.catalogId }.toSet())
        } finally { server.shutdown() }
    }

    @Test fun zeroWagonIsAbsentAndPlatformVariantsStayDistinct() = runBlocking {
        val zero = parseRouteStops(Json.parseToJsonElement("""{"recorrido":[{"codigo":"559A08","nombre":"Stop","vagon":"0"}]}""").jsonObject)
        assertNull(zero.single().wagon)
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                {"codigo":"E48","nombre":"CAD","id":"1"},
                {"codigo":"E48","nombre":"CAD","id":"2"}
            ]}"""))
            for (wagon in listOf("A", "B")) server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"TM0101","nombre":"León XIII","vagon":"$wagon"}]}"""))
            val repo = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
            assertEquals(2, repo.load(ServicioOption("E48", "CAD", emptyList(), null)).size)
        } finally { server.shutdown() }
    }

    @Test fun qualifiedPairedHeadsignsChooseQualifiedSiblingsOnly() = runBlocking {
        for (qualifier in listOf("Ciclovía", "Festivo")) {
            for (qualified in listOf(true, false)) {
                val server = MockWebServer()
                server.start()
                try {
                    server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                        {"codigo":"359","nombre":"Calle 53","id":"regular-a"},
                        {"codigo":"359","nombre":"Calle 53 $qualifier","id":"qualified-a"},
                        {"codigo":"359","nombre":"Zona Industrial Álamos","id":"regular-b"},
                        {"codigo":"359","nombre":"Zona Industrial Álamos $qualifier","id":"qualified-b"}
                    ]}"""))
                    repeat(2) { index -> server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"$index","nombre":"Stop $index"}]}""")) }
                    val repo = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
                    val suffix = if (qualified) " $qualifier" else ""
                    val routes = repo.load(ServicioOption("359", "Calle 53 || Zona Industrial Álamos$suffix", emptyList(), null))
                    val prefix = if (qualified) "qualified" else "regular"
                    assertEquals(listOf("$prefix-a", "$prefix-b"), routes.map { it.catalogId })
                } finally { server.shutdown() }
            }
        }
    }

    @Test fun whitespaceAndSpacedHyphensMatchBothEndpoints() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                {"codigo":"8-10","nombre":"Mundo Aventura","id":"1949"},
                {"codigo":"8-10","nombre":"Est. Av. Americas - Av. Boyacá","id":"253"}
            ]}"""))
            repeat(2) { index -> server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"$index","nombre":"Stop $index"}]}""")) }
            val repo = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
            assertEquals(listOf("1949", "253"), repo.load(ServicioOption("8-10", "Mundo Aventura || Est. Av. Americas  Av. Boyacá", emptyList(), null)).map { it.catalogId })
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[{"codigo":"K300","nombre":"Portal Eldorado","id":"1504"}]}"""))
            server.enqueue(MockResponse().setBody(fixture()))
            assertEquals("1504", repo.load(ServicioOption("K300", "Portal  Eldorado", emptyList(), null)).single().catalogId)
        } finally { server.shutdown() }
    }

    @Test fun doesNotSubstituteAnotherDirectionWhenDestinationIsUnknown() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                {"codigo":"1","nombre":"North","id":"123"},
                {"codigo":"1","nombre":"South","id":"456"}
            ]}"""))
            val repo = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
            assertTrue(repo.load(ServicioOption("1", "Unknown", emptyList(), null)).isEmpty())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun compositePaintedCodesFallBackToComponentCodesAndMatchPrefixedDestinations() = runBlocking {
        val cases = listOf(
            Triple("FZ63", "F63 Pradera || Z63 Tibanica", listOf("F63" to "Pradera", "Z63" to "Tibanica")),
            Triple("CB118", "B118 San Cristóbal Norte || C118 Suba Berlín", listOf("B118" to "San Cristóbal Norte", "C118" to "Suba Berlín")),
            Triple("HA601", "A601 Chapinero || H601 Perdomo", listOf("A601" to "Chapinero", "H601" to "Perdomo")),
        )
        for ((painted, destino, components) in cases) {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody("""{"lista_rutas":[]}"""))
                for ((code, name) in components) {
                    server.enqueue(MockResponse().setBody("""{"lista_rutas":[{"codigo":"$code","nombre":"$name","id":"${code}-id"}]}"""))
                }
                repeat(components.size) { index ->
                    server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"S$index","nombre":"Stop $index"}]}"""))
                }

                val routes = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
                    .load(ServicioOption(painted, destino, emptyList(), null))

                assertEquals(components.map { "${it.first}-id" }, routes.map { it.catalogId })
                val requests = List(server.requestCount) { server.takeRequest().requestUrl!! }
                assertEquals(listOf(painted) + components.map { it.first }, requests.take(components.size + 1)
                    .map { it.queryParameter("search") })
                val details = requests.filter { it.queryParameter("lFuncion") == "infoRuta" }
                assertEquals(components.map { it.first }.toSet(), details.map { it.queryParameter("codigo") }.toSet())
                assertEquals(components.map { it.first + "-id" }.toSet(), details.map { it.queryParameter("idRuta") }.toSet())
            } finally { server.shutdown() }
        }
    }

    @Test fun componentFallbackRetainsHolidayAndCicloviaQualifierMatching() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[]}"""))
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[{"codigo":"A601","nombre":"Chapinero","id":"regular-a"}]}"""))
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[
                {"codigo":"H601","nombre":"Perdomo","id":"regular-h"},
                {"codigo":"H601","nombre":"Perdomo Ciclovía","id":"cycle-h"}
            ]}"""))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"$it","nombre":"Stop $it"}]}""")) }

            val routes = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
                .load(ServicioOption("HA601", "A601 Chapinero || H601 Perdomo Ciclovía", emptyList(), null))

            assertEquals(listOf("regular-a", "cycle-h"), routes.map { it.catalogId })
            val infoRequests = List(server.requestCount) { server.takeRequest().requestUrl!! }
                .filter { it.queryParameter("lFuncion") == "infoRuta" }
            assertEquals(setOf("A601", "H601"), infoRequests.map { it.queryParameter("codigo") }.toSet())
            assertEquals(setOf("Chapinero", "Perdomo Ciclovía"), infoRequests.map { it.queryParameter("nombre") }.toSet())
        } finally { server.shutdown() }
    }

    @Test fun componentFallbackQueriesOnlyEndpointMissingFromPrimaryResults() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[{"codigo":"FZ63","nombre":"Pradera","id":"painted-f"}]}"""))
            server.enqueue(MockResponse().setBody("""{"lista_rutas":[{"codigo":"Z63","nombre":"Tibanica","id":"component-z"}]}"""))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"recorrido":[{"codigo":"$it","nombre":"Stop $it"}]}""")) }

            val routes = PublicRouteRepository(baseUrl = server.url("/loader.php").toString())
                .load(ServicioOption("FZ63", "F63 Pradera || Z63 Tibanica", emptyList(), null))

            assertEquals(listOf("painted-f", "component-z"), routes.map { it.catalogId })
            val requests = List(server.requestCount) { server.takeRequest().requestUrl!! }
            assertEquals(listOf("FZ63", "Z63"), requests.filter { it.queryParameter("lFuncion") == "searchRutaByTipo" }
                .map { it.queryParameter("search") })
            val detailRequests = requests.filter { it.queryParameter("lFuncion") == "infoRuta" }
            assertEquals(setOf("FZ63", "Z63"), detailRequests.map { it.queryParameter("codigo") }.toSet())
        } finally { server.shutdown() }
    }
}
