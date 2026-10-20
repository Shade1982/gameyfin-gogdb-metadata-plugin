package io.github.shade1982.gameyfin.plugins.gogdb

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.gameyfin.pluginapi.core.wrapper.GameyfinPlugin
import org.gameyfin.pluginapi.core.wrapper.PluginContext
import org.gameyfin.pluginapi.gamemetadata.GameMetadata
import org.gameyfin.pluginapi.gamemetadata.GameMetadataProvider
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.pf4j.Extension
import org.slf4j.LoggerFactory

class GogDbPlugin(context: PluginContext) : GameyfinPlugin(context) {
    @Suppress("Unused")
    @Extension(ordinal = 3)
    class GogDbMetadataProvider internal constructor(
        private val client: HttpClient
    ) : GameMetadataProvider {
        constructor() : this(createHttpClient())

        private val log = LoggerFactory.getLogger(javaClass)
        private val metadataCache = BoundedCache<String, GameMetadata>(
            maxSize = METADATA_CACHE_SIZE,
            ttlMillis = METADATA_CACHE_TTL_MILLIS
        )

        override val supportedPlatforms: Set<Platform> = setOf(
            Platform.PC_MICROSOFT_WINDOWS,
            Platform.LINUX,
            Platform.MAC
        )

        override fun fetchByTitle(
            gameTitle: String,
            platformFilter: Set<Platform>,
            maxResults: Int
        ): List<GameMetadata> {
            val title = gameTitle.removeSuffix("_base").removeSuffix("_game")
                .replace('_', ' ').trim()
            if (title.isBlank() || maxResults <= 0) return emptyList()

            return try {
                runBlocking { searchMetadata(title, platformFilter, maxResults) }.also { results ->
                    if (results.isEmpty()) {
                        log.debug("GOG DB search returned no metadata for '{}'", title)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to search GOG DB for '{}': {}", title, e.message)
                emptyList()
            }
        }

        override fun fetchById(id: String): GameMetadata? {
            val productId = id.toIntOrNull()?.toString() ?: return null
            metadataCache[productId]?.let { return it }
            return try {
                runBlocking { fetchProduct(productId) }?.let(::toMetadata)?.also {
                    metadataCache[it.originalId] = it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to fetch GOG DB product {}: {}", id, e.message)
                null
            }
        }

        private suspend fun searchMetadata(
            title: String,
            platformFilter: Set<Platform>,
            maxResults: Int
        ): List<GameMetadata> {
            val metadataById = linkedMapOf<String, GameMetadata>()
            val attemptedProductIds = mutableSetOf<String>()
            val searchQuery = prepareSearchTitle(title).ifBlank { title }
            CatalogPagination.collect(
                pageSize = CATALOG_PAGE_SIZE,
                fetchPage = { searchAfter ->
                    val response = client.get(CATALOG_URL) {
                        header("User-Agent", USER_AGENT)
                        parameter("limit", CATALOG_PAGE_SIZE)
                        parameter("order", "asc:externalProductId")
                        parameter("productType", "in:game,pack")
                        parameter("countryCode", "US")
                        parameter("locale", "en-US")
                        parameter("currencyCode", "USD")
                        parameter("query", searchQuery)
                        parameter("searchAfter", searchAfter)
                    }
                    if (response.status.value !in 200..299) {
                        throw IllegalStateException("GOG catalog search returned HTTP ${response.status.value}")
                    }
                    val page = response.body<CatalogResponse>()
                    CatalogPage(page.products, page.productCount)
                },
                shouldStop = { products ->
                    val candidates = rankSearchResults(searchQuery, products, products.size)
                        .filter { matchesPlatforms(it.platforms(), platformFilter) }
                    for (candidate in candidates) {
                        if (metadataById.size >= maxResults) break
                        if (!attemptedProductIds.add(candidate.id)) continue

                        try {
                            val metadata = metadataCache[candidate.id] ?: fetchProduct(candidate.id)
                                ?.let(::toMetadata)
                                ?.also { metadataCache[it.originalId] = it }
                            if (metadata != null && matchesPlatforms(metadata.platforms.orEmpty(), platformFilter)) {
                                metadataById[candidate.id] = metadata
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn("Failed to fetch GOG DB product {}: {}", candidate.id, e.message)
                        }
                    }
                    metadataById.size >= maxResults
                }
            )
            return rankMetadataResults(searchQuery, metadataById.values, maxResults)
        }

        private suspend fun fetchProduct(id: String): GogDbProduct? {
            if (id.toIntOrNull() == null) {
                log.warn("Skipping invalid GOG DB product ID '{}'", id)
                return null
            }
            val response = client.get("$GOGDB_DATA_URL/products/$id/product.json") {
                header("User-Agent", USER_AGENT)
            }
            if (response.status.value !in 200..299) {
                log.warn("GOG DB product lookup returned HTTP {} for {}", response.status.value, id)
                return null
            }
            return response.body()
        }

        private fun CatalogProduct.platforms(): Set<Platform> =
            operatingSystems.mapNotNull(::toPlatform).toSet()

        private fun matchesPlatforms(
            availablePlatforms: Set<Platform>,
            platformFilter: Set<Platform>
        ): Boolean =
            platformFilter.isEmpty() || availablePlatforms.isEmpty() ||
                availablePlatforms.intersect(platformFilter).isNotEmpty()

        companion object {
            private const val CATALOG_URL = "https://catalog.gog.com/v1/catalog"
            private const val GOGDB_DATA_URL = "https://www.gogdb.org/data"
            private const val USER_AGENT = "Gameyfin-GOGDB-Plugin/0.1 (metadata provider)"
            private const val CATALOG_PAGE_SIZE = 48
            private const val METADATA_CACHE_SIZE = 256
            private const val METADATA_CACHE_TTL_MILLIS = 2 * 60 * 60 * 1000L
        }
    }
}

private fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 15_000
    }
    expectSuccess = false
}

@Serializable
internal data class CatalogResponse(
    @SerialName("productCount") val productCount: Int? = null,
    val products: List<CatalogProduct> = emptyList()
)

@Serializable
internal data class CatalogProduct(
    val id: String,
    val title: String,
    @SerialName("operatingSystems") val operatingSystems: List<String> = emptyList()
)

@Serializable
internal data class GogDbProduct(
    val id: Int,
    val title: String,
    val description: String? = null,
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val publisher: String? = null,
    @SerialName("global_date") val globalDate: String? = null,
    @SerialName("store_date") val storeDate: String? = null,
    @SerialName("cs_systems") val csSystems: List<String> = emptyList(),
    @SerialName("comp_systems") val compSystems: List<String> = emptyList(),
    val features: List<GogDbNamedValue> = emptyList(),
    val properties: List<GogDbNamedValue> = emptyList(),
    val tags: List<GogDbNamedValue> = emptyList(),
    @SerialName("image_boxart") val imageBoxart: String? = null,
    @SerialName("image_background") val imageBackground: String? = null,
    val screenshots: List<String> = emptyList(),
    val videos: List<GogDbVideo> = emptyList()
)

@Serializable
internal data class GogDbNamedValue(
    val name: String
)

@Serializable
internal data class GogDbVideo(
    @SerialName("video_url") val videoUrl: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val provider: String? = null
)
