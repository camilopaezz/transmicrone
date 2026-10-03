package co.camilo.transmicrone.di

import android.content.Context
import co.camilo.transmicrone.BuildConfig
import co.camilo.transmicrone.data.ArrivalsRepository
import co.camilo.transmicrone.data.catalog.CatalogRepository
import co.camilo.transmicrone.data.catalog.JsonCatalogRepository
import co.camilo.transmicrone.data.feed.ArrivalsParser
import co.camilo.transmicrone.data.feed.BodegaFeedClient
import co.camilo.transmicrone.data.feed.FeedClient
import co.camilo.transmicrone.data.feed.MockFeedClient
import co.camilo.transmicrone.data.local.ArrivalsCache
import co.camilo.transmicrone.data.local.BoardRepository
import co.camilo.transmicrone.data.local.DataStoreBoardRepository
import co.camilo.transmicrone.data.local.FileArrivalsCache
import co.camilo.transmicrone.data.local.InstallationId
import co.camilo.transmicrone.domain.Clock
import co.camilo.transmicrone.domain.SystemClock
import kotlinx.serialization.json.Json
import java.io.File

class AppContainer(
    context: Context,
    val clock: Clock = SystemClock,
    json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    },
    feedClient: FeedClient? = null,
    catalogRepository: CatalogRepository? = null,
    boardRepository: BoardRepository? = null,
    arrivalsCache: ArrivalsCache? = null,
) {
    val json: Json = json

    // Android's asset merger expands .gz sources and removes their .gz suffix.
    val catalog: CatalogRepository = catalogRepository ?: JsonCatalogRepository(json, gzip = false) {
        context.assets.open("catalog.json")
    }

    val boards: BoardRepository = boardRepository ?: DataStoreBoardRepository(context, json)

    private val cache: ArrivalsCache = arrivalsCache ?: FileArrivalsCache(
        File(context.filesDir, if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) "arrivals-cache-demo" else "arrivals-cache"),
        json,
    )

    private val feed: FeedClient = feedClient ?: if (BuildConfig.DEBUG && BuildConfig.MOCK_FEED) MockFeedClient(clock) else BodegaFeedClient(
        baseUrl = BuildConfig.FEED_BASE_URL,
        appId = BuildConfig.FEED_APPID,
        installationId = InstallationId.getOrCreate(context),
        parser = ArrivalsParser(json),
        clock = clock,
        client = BodegaFeedClient.httpClient(
            BuildConfig.HTTP_CONNECT_TIMEOUT_MS,
            BuildConfig.HTTP_READ_TIMEOUT_MS,
        ),
    )

    val arrivals = ArrivalsRepository(feed, cache, clock)
}
