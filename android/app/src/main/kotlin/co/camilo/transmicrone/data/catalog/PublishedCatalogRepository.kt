package co.camilo.transmicrone.data.catalog

import co.camilo.transmicrone.domain.ServicioOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.GzipSource
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val MAX_MANIFEST_BYTES = 64L * 1024
private const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024
private const val MAX_CATALOG_BYTES = 32L * 1024 * 1024

internal class PublishedCatalogRepository(
    private val bundled: () -> JsonCatalogRepository,
    private val cache: File,
    private val catalogUrl: HttpUrl,
) : CatalogRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // Pin one snapshot for the process, including any searches and edits already in progress.
    private val active by lazy {
        val previous = if (cache.isFile) {
            try {
                cachedRepository(cache).also { it.load() }
            } catch (_: Exception) {
                null
            }
        } else null
        val shipped = bundled()
        val shippedDate = try {
            shipped.load()?.let(Instant::parse)
        } catch (failure: Exception) {
            if (previous != null) return@lazy previous
            throw failure
        }
        val previousDate = previous?.load()?.let(Instant::parse)
        if (previousDate != null && (shippedDate == null || previousDate > shippedDate)) previous
        else shipped
    }

    override suspend fun search(query: String, limit: Int) = active.search(query, limit)
    override suspend fun explore(query: String, limit: Int) = active.explore(query, limit)
    override suspend fun service(painted: String, destino: String) = active.service(painted, destino)
    override fun place(placeId: String) = active.place(placeId)
    override suspend fun generatedAt() = active.generatedAt()
    override suspend fun routes(option: ServicioOption) = active.routes(option)

    /** Called once per process on the IO dispatcher. The cache is replaced only after validation. */
    fun checkForUpdate() {
        val activeDate = active.load()?.let(Instant::parse)
        val manifest = fetch(catalogUrl.newBuilder().addPathSegment("current.json").build()) { body ->
            val source = body.source()
            require(!source.request(MAX_MANIFEST_BYTES + 1)) { "Catalog manifest exceeds size limit" }
            json.decodeFromString<PublishedCatalogManifest>(source.readUtf8())
        }
        require(manifest.schemaVersion == 1 && manifest.catalogSchemaVersion == SUPPORTED_CATALOG_SCHEMA_VERSION) {
            "Unsupported published catalog schema"
        }
        val generatedAt = Instant.parse(manifest.generatedAt)
        if (activeDate != null && generatedAt <= activeDate) return

        val artifact = manifest.catalog
        require(manifest.version.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Invalid catalog version" }
        val artifactUrl = catalogUrl.newBuilder()
            .addPathSegments("versions/${manifest.version}/catalog.json.gz")
            .build()
        require(artifact.url == artifactUrl.toString()) { "Unexpected catalog artifact URL" }
        require(artifact.bytes in 1..MAX_DOWNLOAD_BYTES && artifact.sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Invalid catalog artifact size or checksum"
        }

        val pending = File(cache.parentFile, "${cache.name}.pending")
        try {
            fetch(artifactUrl) { download ->
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                pending.outputStream().buffered().use { output ->
                    download.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            received += count
                            require(received <= artifact.bytes) { "Catalog download exceeds declared size" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(received == artifact.bytes && digest.digest().toHexString() == artifact.sha256) {
                    "Catalog download differs from manifest"
                }
            }
            require(cachedRepository(pending).load()?.let(Instant::parse) == generatedAt) {
                "Catalog timestamp differs from manifest"
            }
            Files.move(pending.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            pending.delete()
        }
    }

    private fun cachedRepository(file: File) = JsonCatalogRepository {
        require(file.length() in 1..MAX_DOWNLOAD_BYTES) { "Invalid cached catalog size" }
        val packed = GzipSource(file.source()).buffer().use { source ->
            require(!source.request(MAX_CATALOG_BYTES + 1)) { "Decompressed catalog exceeds size limit" }
            json.decodeFromString<PackedCatalog>(source.readUtf8())
        }
        require(!packed.generatedAt.isNullOrBlank()) { "Published catalog has no generation timestamp" }
        Instant.parse(packed.generatedAt)
        require(packed.places.isNotEmpty() && packed.places.distinctBy { it.placeId }.size == packed.places.size) {
            "Published catalog needs unique place IDs"
        }
        packed
    }

    private fun <T> fetch(url: HttpUrl, read: (ResponseBody) -> T): T {
        val request = Request.Builder().url(url)
            .header("User-Agent", "transminuto-catalog/1.0")
            .header("Accept-Encoding", "identity")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Catalog request failed with HTTP ${response.code}")
            read(response.body)
        }
    }
}

@Serializable
private data class PublishedCatalogManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("catalog_schema_version") val catalogSchemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    val version: String,
    val catalog: Artifact,
) {
    @Serializable
    data class Artifact(val url: String, val bytes: Long, val sha256: String)
}
