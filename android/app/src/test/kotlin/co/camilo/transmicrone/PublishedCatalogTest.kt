package co.camilo.transmicrone

import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import co.camilo.transmicrone.data.catalog.PublishedCatalogRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class PublishedCatalogTest {
    @get:Rule val directory = TemporaryFolder()

    private val bundledDate = "2026-10-02T04:51:04Z"
    private val publishedDate = "2026-11-11T18:00:42Z"
    private val version = "20261111T180040Z-43073559253-1"
    private val artifactPath = "/catalog/versions/$version/catalog.json.gz"
    private val cache: File get() = File(directory.root, "catalog.json.gz")

    @Test fun verifiedUpdateIsUsedOfflineOnNextStart() = runBlocking {
        MockWebServer().use { server ->
            val bytes = gzip(catalog(publishedDate, "Published"))
            server.enqueue(manifest(server, bytes))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val running = repository(server)
            assertEquals("Bundled", running.place("paradero:123")?.name)

            running.checkForUpdate()

            assertTrue(bytes.contentEquals(cache.readBytes()))
            assertEquals("Bundled", running.place("paradero:123")?.name)
            assertEquals("/catalog/current.json", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals(artifactPath, request.path)
            assertEquals("transminuto-catalog/1.0", request.getHeader("User-Agent"))

            val restarted = repository(server)
            assertEquals(publishedDate, restarted.generatedAt())
            assertEquals("Published", restarted.search("published").single().name)
            val service = restarted.explore("E48").services.single()
            assertEquals("12444", service.operatingHours.single().catalogId)
            assertEquals("123", restarted.routes(service.option).single().stops.single().code)

            server.enqueue(manifest(server, bytes))
            restarted.checkForUpdate()
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun brokenDownloadsKeepLastGoodCatalog() {
        MockWebServer().use { server ->
            val previous = gzip(catalog(publishedDate, "Previous"))
            cache.writeBytes(previous)
            val latest = "2026-11-18T00:00:00Z"
            val valid = gzip(catalog(latest, "New"))
            val invalid = listOf(
                valid.copyOf(valid.size - 1) to valid,
                valid.copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() } to valid,
                gzip(catalog(latest, "Invalid index", serviceIndex = 1)).let { it to it },
                gzip(catalog(latest, "Wrong schema").replace("\"schema_version\":1", "\"schema_version\":2")).let { it to it },
                previous to previous,
            )
            for ((body, declared) in invalid) {
                server.enqueue(manifest(server, declared, latest))
                server.enqueue(MockResponse().setBody(Buffer().write(body)))
                assertThrows(IllegalArgumentException::class.java) { repository(server).checkForUpdate() }
                assertTrue(previous.contentEquals(cache.readBytes()))
                assertEquals("Previous", repository(server).place("paradero:123")?.name)
                assertFalse(File(directory.root, "catalog.json.gz.pending").exists())
            }
        }
    }

    @Test fun unsupportedManifestAndForeignArtifactAreRejectedBeforeDownload() {
        MockWebServer().use { server ->
            val bytes = gzip(catalog(publishedDate, "Published"))
            server.enqueue(manifest(server, bytes, schema = 2))
            server.enqueue(manifest(server, bytes, url = "https://example.com$artifactPath"))

            repeat(2) {
                assertThrows(IllegalArgumentException::class.java) { repository(server).checkForUpdate() }
            }
            assertEquals(2, server.requestCount)
            assertFalse(cache.exists())
        }
    }

    @Test fun unavailablePublisherAndCorruptCacheLeaveTheBundleUsable() {
        MockWebServer().use { server ->
            cache.writeText("invalid gzip")
            server.enqueue(MockResponse().setResponseCode(503))

            val repository = repository(server)
            assertThrows(IOException::class.java) { repository.checkForUpdate() }
            assertEquals("Bundled", repository.place("paradero:123")?.name)
        }
    }

    @Test fun newerBundleTakesPrecedenceOverAnOlderCacheAndManifest() {
        MockWebServer().use { server ->
            val oldDate = "2026-09-30T00:00:00Z"
            val bytes = gzip(catalog(oldDate, "Old"))
            cache.writeBytes(bytes)
            server.enqueue(manifest(server, bytes, oldDate))

            val repository = repository(server)
            repository.checkForUpdate()
            assertEquals("Bundled", repository.place("paradero:123")?.name)
            assertEquals(1, server.requestCount)
        }
    }

    private fun repository(server: MockWebServer) = PublishedCatalogRepository(
        bundled = { JsonCatalogRepository(Json, gzip = false) { catalog(bundledDate, "Bundled").byteInputStream() } },
        cache = cache,
        catalogUrl = server.url("/catalog/"),
    )

    private fun catalog(date: String, name: String, serviceIndex: Int = 0) = """
        {"schema_version":1,"generated_at":"$date",
         "servicios":[{"painted":"E48","destino":"CAD","route_ids":[7]}],
         "places":[{"place_id":"paradero:123","kind":"paradero","name":"$name","fold":"${name.lowercase()}",
                    "query_paradero":"123","servicio_indices":[$serviceIndex]}],
         "service_hours":[{"catalog_id":"12444","painted":"E48","destino":"CAD"}],
         "service_routes":[{"catalog_id":"12444","painted":"E48","destino":"CAD",
                            "stops":[{"code":"123","name":"$name"}]}]}
    """.trimIndent()

    private fun manifest(
        server: MockWebServer,
        bytes: ByteArray,
        date: String = publishedDate,
        schema: Int = 1,
        url: String = server.url(artifactPath).toString(),
    ): MockResponse {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return MockResponse().setBody("""
            {"schema_version":$schema,"catalog_schema_version":1,"generated_at":"$date","version":"$version",
             "checked_at":"2026-11-11T18:00:40.872840+00:00",
             "catalog":{"url":"$url","bytes":${bytes.size},"sha256":"$sha256"}}
        """.trimIndent())
    }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
    }.toByteArray()
}
