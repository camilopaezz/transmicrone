package co.camilo.transmicrone.data.catalog

import co.camilo.transmicrone.domain.ServicioOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

// tools/catalog_daily.py checks published artifacts against these limits; keep them in sync.
private const val MAX_MANIFEST_BYTES = 64L * 1024
private const val MAX_DOWNLOAD_BYTES = 5L * 1024 * 1024
private const val MAX_CATALOG_BYTES = 32L * 1024 * 1024

internal class PublishedCatalogRepository(
    // The packaged catalog as uncompressed JSON.
    private val openBundled: () -> InputStream,
    private val cache: File,
    private val catalogUrl: HttpUrl,
    private val now: () -> Instant = Instant::now,
    private val checkInterval: Duration = Duration.ofHours(6),
) : CatalogRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
    private val checked = File(cache.parentFile, "${cache.name}.checked")

    // One snapshot serves the process until activateLatest() switches at a point the UI chooses.
    @Volatile private var current: JsonCatalogRepository? = null
    // Generation time of a verified download that is newer than the current snapshot.
    @Volatile private var staged: Instant? = null

    private val active: JsonCatalogRepository
        get() = current ?: synchronized(this) { current ?: initial().also { current = it } }

    // Compares timestamps without building either catalog, then loads only the newer one.
    private fun initial(): JsonCatalogRepository {
        val cachedDate = if (cache.isFile) try { stamp { openCached(cache) } } catch (_: Exception) { null } else null
        val shippedDate = try { stamp(openBundled) } catch (_: Exception) { null }
        if (cachedDate != null && (shippedDate == null || cachedDate > shippedDate)) {
            try {
                return cachedRepository(cache).also { it.load() }
            } catch (_: Exception) {
                // Fall back to the packaged catalog.
            }
        }
        return JsonCatalogRepository(json, gzip = false, opener = openBundled)
    }

    private fun stamp(open: () -> InputStream): Instant? =
        open().buffered().use { json.decodeFromStream<CatalogStamp>(it) }.generatedAt?.let(Instant::parse)

    private fun openCached(file: File): InputStream =
        LimitedInputStream(GZIPInputStream(file.inputStream().buffered()), MAX_CATALOG_BYTES)

    override suspend fun search(query: String, limit: Int) = active.search(query, limit)
    override suspend fun explore(query: String, limit: Int) = active.explore(query, limit)
    override suspend fun service(painted: String, destino: String) = active.service(painted, destino)
    override fun place(placeId: String) = active.place(placeId)
    override suspend fun generatedAt() = active.generatedAt()
    override suspend fun routes(option: ServicioOption) = active.routes(option)

    override fun prepareLatest(): (() -> Unit)? {
        val target = staged ?: return null
        val replacement = try {
            cachedRepository(cache).also { require(it.load()?.let(Instant::parse) == target) }
        } catch (_: Throwable) {
            // Includes OutOfMemoryError. The current snapshot stays; the next cold start retries.
            synchronized(this) { if (staged == target) staged = null }
            return null
        }
        return {
            synchronized(this) {
                if (staged == target) {
                    current = replacement
                    staged = null
                }
            }
        }
    }

    /**
     * Runs on a background thread at process start. Skips the network if a check succeeded
     * within [checkInterval]. The cache is replaced only after validation.
     */
    fun checkForUpdate() {
        val checkedAt = now()
        val last = lastChecked()
        if (last != null && last <= checkedAt && last > checkedAt - checkInterval) return

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
        if (activeDate != null && generatedAt <= activeDate) return recordChecked(checkedAt)

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
            // Only the timestamp is kept, so the candidate is not held in memory beside the active catalog.
            require(cachedRepository(pending).load()?.let(Instant::parse) == generatedAt) {
                "Catalog timestamp differs from manifest"
            }
            synchronized(this) {
                Files.move(pending.toPath(), cache.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                staged = generatedAt
            }
            recordChecked(checkedAt)
        } finally {
            pending.delete()
        }
    }

    private fun lastChecked(): Instant? = try {
        if (checked.isFile) Instant.ofEpochMilli(checked.readText().trim().toLong()) else null
    } catch (_: Exception) {
        null
    }

    private fun recordChecked(at: Instant) {
        try {
            checked.writeText(at.toEpochMilli().toString())
        } catch (_: IOException) {
            // Without the stamp the next process checks again, which is harmless.
        }
    }

    private fun cachedRepository(file: File) = JsonCatalogRepository {
        require(file.length() in 1..MAX_DOWNLOAD_BYTES) { "Invalid cached catalog size" }
        val packed = openCached(file).buffered().use { json.decodeFromStream<PackedCatalog>(it) }
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

private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    private fun counted(bytes: Long) {
        if (bytes > 0) count += bytes
        require(count <= limit) { "Decompressed catalog exceeds size limit" }
    }

    override fun read(): Int = super.read().also { if (it != -1) counted(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { counted(it.toLong()) }
    override fun skip(n: Long): Long = super.skip(n).also(::counted)
    override fun markSupported() = false
}

@Serializable
private class CatalogStamp(@SerialName("generated_at") val generatedAt: String? = null)

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
