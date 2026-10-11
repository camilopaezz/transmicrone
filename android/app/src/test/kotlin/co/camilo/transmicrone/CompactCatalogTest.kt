package co.camilo.transmicrone

import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompactCatalogTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun repository(source: String) = JsonCatalogRepository(json, gzip = false) {
        source.byteInputStream()
    }

    @Test fun compactAndInlineCatalogsHaveSameOptionsAndExploreRanking() = runBlocking {
        val rows = listOf(
            """{"painted":"E48","destino":"CÁD","route_ids":[2,1,2],"color":null}""",
            """{"painted":"E48","destino":"CÁD","route_ids":[3,2],"color":"#ff0000"}""",
            """{"painted":"100","destino":"Cadillac","route_ids":[4]}""",
            """{"painted":"500","destino":"Acadia","route_ids":[5]}""",
        ).map { json.parseToJsonElement(it) }
        fun place(id: String, name: String, indices: List<Int>, compact: Boolean) = buildJsonObject {
            put("place_id", id)
            put("kind", if (id == "a") "estacion" else "paradero")
            put("name", name)
            put("fold", name.lowercase())
            put("query_paradero", id)
            put(if (compact) "servicio_indices" else "servicios", JsonArray(indices.map {
                if (compact) JsonPrimitive(it) else rows[it]
            }))
        }
        fun source(compact: Boolean) = buildJsonObject {
            put("schema_version", 1)
            put("generated_at", "2026-10-10")
            // Include a duplicate tuple to check interning, not just index reuse.
            if (compact) put("servicios", JsonArray(rows + rows[0]))
            put("places", JsonArray(listOf(
                place("a", "Cadillac", listOf(2, 0, 3), compact),
                place("b", "Acadia", listOf(if (compact) 4 else 0, 1), compact),
            )))
        }.toString()
        val inline = repository(source(false))
        val compact = repository(source(true))

        for (id in listOf("a", "b")) assertEquals(inline.place(id), compact.place(id))
        for (query in listOf("cad", "e48", "100", "acadia")) {
            assertEquals(inline.explore(query), compact.explore(query))
            assertEquals(inline.search(query), compact.search(query))
        }
        assertEquals(listOf("E48", "100", "500"), compact.explore("cad").services.map { it.option.painted })
        for (repo in listOf(inline, compact)) {
            assertSame(repo.place("a")!!.servicios[1], repo.place("b")!!.servicios[0])
            assertEquals(listOf(1, 2, 3), repo.service("E48", "CÁD")!!.option.routeIds)
            assertEquals("#FF0000", repo.service("E48", "CÁD")!!.option.color)
            assertEquals("2026-10-10", repo.generatedAt())
        }
    }

    @Test fun packagedCatalogResolvesCommutePlacesAndServicios() = runBlocking {
        val asset = File("src/main/assets/catalog.json.gz")
        assertTrue("Packaged catalog is missing: ${asset.absolutePath}", asset.isFile)
        val catalog = JsonCatalogRepository(json, gzip = true) { asset.inputStream() }
        val leon = catalog.search("leon").first { it.name == "León XIII" }
        assertTrue(leon.servicios.map { it.painted }.containsAll(listOf("E48", "K43")))
        assertTrue(catalog.search("ricaurte").any { place ->
            place.servicios.map { it.painted }.containsAll(listOf("S48", "S43"))
        })
    }
}
