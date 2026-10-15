package io.github.shade1982.gameyfin.plugins.gogdb

import kotlinx.serialization.json.Json
import org.gameyfin.pluginapi.gamemetadata.GameFeature
import org.gameyfin.pluginapi.gamemetadata.Genre
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.gameyfin.pluginapi.gamemetadata.Theme
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val testJson = Json { ignoreUnknownKeys = true }

class GogDbSupportTest {
    @Test
    fun `exact catalog title is ranked ahead of fuzzy matches`() {
        val results = rankSearchResults(
            title = "Portal",
            results = listOf(
                CatalogProduct("1", "Portal Stories: VR"),
                CatalogProduct("2", "Portal"),
                CatalogProduct("3", "Portal 2")
            ),
            maxResults = 3
        )

        assertEquals("Portal", results.first().title)
    }

    @Test
    fun `search ranking respects score threshold and result limit`() {
        val results = rankSearchResults(
            title = "Portal",
            results = listOf(
                CatalogProduct("1", "Portal 2"),
                CatalogProduct("2", "A completely unrelated game")
            ),
            maxResults = 1
        )

        assertEquals("Portal 2", results.single().title)
        assertTrue(rankSearchResults("Portal", results, maxResults = 0).isEmpty())
    }

    @Test
    fun `ranking ignores edition noise and prefers the closest title`() {
        val results = rankSearchResults(
            title = "Age of Mythology - Retold (Premium Edition)",
            results = listOf(
                CatalogProduct("1", "Age of Chivalry"),
                CatalogProduct("2", "Age of Mythology: Retold"),
                CatalogProduct("3", "Age of Empires IV")
            ),
            maxResults = 3
        )

        assertEquals("Age of Mythology: Retold", results.first().title)
    }

    @Test
    fun `metadata mapping converts product details and image hashes`() {
        val hash = "a".repeat(64)
        val metadata = toMetadata(
            GogDbProduct(
                id = 123,
                title = "Test Game",
                description = "<p>A &amp; B</p>",
                developers = listOf("Developer"),
                publishers = listOf("Publisher"),
                globalDate = "2020-04-05T00:00:00+00:00",
                csSystems = listOf("windows", "linux", "unsupported"),
                properties = listOf(GogDbNamedValue("Puzzle")),
                features = listOf(GogDbNamedValue("Single-player")),
                tags = listOf(
                    GogDbNamedValue("Science Fiction"),
                    GogDbNamedValue("Cozy"),
                    GogDbNamedValue("Adventure")
                ),
                imageBoxart = hash,
                screenshots = listOf(hash),
                videos = listOf(
                    GogDbVideo("https://www.youtube.com/embed/example", provider = "youtube")
                )
            )
        )

        assertEquals("123", metadata.originalId)
        assertEquals("A & B", metadata.description)
        assertEquals(Instant.parse("2020-04-05T00:00:00Z"), metadata.release)
        assertEquals(setOf(Platform.PC_MICROSOFT_WINDOWS, Platform.LINUX), metadata.platforms)
        assertTrue(Genre.PUZZLE in metadata.genres.orEmpty())
        assertEquals(setOf(GameFeature.SINGLEPLAYER), metadata.features)
        assertTrue(Theme.SCIENCE_FICTION in metadata.themes.orEmpty())
        assertTrue(Genre.ADVENTURE in metadata.genres.orEmpty())
        assertEquals(setOf("Cozy"), metadata.keywords)
        val coverUrl = requireNotNull(imageUrl(hash))
        assertEquals(setOf(coverUrl), metadata.coverUrls)
        assertEquals(setOf(coverUrl), metadata.screenshotUrls)
        assertEquals(setOf(java.net.URI.create("https://www.youtube.com/embed/example")), metadata.videoUrls)
    }

    @Test
    fun `GOG DB snake case product fields deserialize correctly`() {
        val hash = "b".repeat(64)
        val product = testJson.decodeFromString<GogDbProduct>(
            """
            {
              "id": 123,
              "title": "Test Game",
              "global_date": "2020-04-05T00:00:00+00:00",
              "cs_systems": ["windows"],
              "comp_systems": ["linux"],
              "image_boxart": "$hash",
              "image_background": "$hash",
              "screenshots": ["$hash"],
              "features": [{"id": "single", "name": "Single-player"}],
              "videos": [{"provider": "youtube", "video_url": "https://www.youtube.com/embed/example", "thumbnail_url": "https://img.youtube.com/example.jpg"}],
              "properties": [{"name": "Puzzle", "slug": "puzzle"}],
              "tags": [{"id": 1, "level": 1, "name": "Adventure", "slug": "adventure"}]
            }
            """.trimIndent()
        )

        assertEquals("2020-04-05T00:00:00+00:00", product.globalDate)
        assertEquals(listOf("windows"), product.csSystems)
        assertEquals(hash, product.imageBoxart)
        assertEquals("Adventure", product.tags.single().name)
        assertEquals("Single-player", product.features.single().name)
        assertEquals("https://www.youtube.com/embed/example", product.videos.single().videoUrl)
    }

    @Test
    fun `catalog pagination follows cursor and stops at result count`() = kotlinx.coroutines.runBlocking {
        val cursors = mutableListOf<String>()
        val results = CatalogPagination.collect(
            pageSize = 2,
            fetchPage = { cursor ->
                cursors += cursor
                when (cursor) {
                    "0" -> CatalogPage(
                        listOf(CatalogProduct("1", "Portal 2"), CatalogProduct("2", "Portal Stories")),
                        productCount = 3
                    )
                    "2" -> CatalogPage(listOf(CatalogProduct("3", "Portal")), productCount = 3)
                    else -> error("Unexpected cursor: $cursor")
                }
            },
            shouldStop = { products ->
                rankSearchResults("Portal", products, products.size)
                    .any { it.title == "Portal" }
            }
        )

        assertEquals(listOf("0", "2"), cursors)
        assertEquals(listOf("1", "2", "3"), results.map(CatalogProduct::id))
    }

    @Test
    fun `catalog pagination stops on exhausted and repeated pages`() = kotlinx.coroutines.runBlocking {
        var requests = 0
        val exhausted = CatalogPagination.collect(
            pageSize = 2,
            fetchPage = {
                requests++
                CatalogPage(listOf(CatalogProduct("1", "Portal")), productCount = 1)
            },
            shouldStop = { false }
        )

        assertEquals(1, requests)
        assertEquals(1, exhausted.size)

        requests = 0
        val repeated = CatalogPagination.collect(
            pageSize = 1,
            fetchPage = {
                requests++
                CatalogPage(listOf(CatalogProduct("1", "Portal")), productCount = 5)
            },
            shouldStop = { false }
        )
        assertEquals(2, requests)
        assertEquals(1, repeated.size)
    }

    @Test
    fun `dates support timestamps and date-only values`() {
        assertEquals(
            Instant.parse("2021-01-02T03:04:05Z"),
            parseReleaseDate("2021-01-02T03:04:05Z")
        )
        assertEquals(
            Instant.parse("2021-01-02T00:00:00Z"),
            parseReleaseDate("2021-01-02")
        )
        assertNull(parseReleaseDate("not a date"))
    }

    @Test
    fun `description markup and common html entities are cleaned`() {
        assertEquals(
            "A & B 'game'",
            cleanDescription("<p>A &amp; B &#39;game&#39;</p><script>ignore()</script>")
        )
        assertNull(cleanDescription("  "))
    }

    @Test
    fun `unsupported platform and malformed image hash are ignored`() {
        assertNull(toPlatform("unknown"))
        assertNull(imageUrl("../bad-hash"))
        assertNull(imageUrl(null))
    }

    @Test
    fun `metadata cache evicts the least recently used item`() {
        val cache = BoundedCache<String, Int>(maxSize = 2, ttlMillis = 100)
        cache["first"] = 1
        cache["second"] = 2
        assertEquals(1, cache["first"])
        cache["third"] = 3

        assertNull(cache["second"])
        assertEquals(1, cache["first"])
        assertEquals(3, cache["third"])
    }

    @Test
    fun `metadata cache expires entries after ttl`() {
        var now = 0L
        val cache = BoundedCache<String, Int>(
            maxSize = 2,
            ttlMillis = 100,
            nowMillis = { now }
        )
        cache["game"] = 42

        now = 99
        assertEquals(42, cache["game"])
        now = 100
        assertNull(cache["game"])
    }
}

class EditionRankingTest {
    private val query = "Age of Mythology: Retold - Premium Edition"

    @kotlin.test.Test
    fun `other editions of the same game match`() {
        listOf(
            "Age of Mythology: Retold",
            "Age of Mythology: Retold - Deluxe Edition",
            "Age of Mythology: Retold (Definitive Edition)"
        ).forEach { kotlin.test.assertTrue(titleMatchScore(query, it) >= 100, it) }
    }

    @kotlin.test.Test
    fun `different games sharing generic words do not match`() {
        listOf("Age of Chivalry", "Age of Empires IV", "God of War", "SUPERHOT ONE OF US BUNDLE")
            .forEach { kotlin.test.assertTrue(titleMatchScore(query, it) < 100, it) }
    }

    @kotlin.test.Test
    fun `exact edition ranks first`() {
        kotlin.test.assertTrue(titleMatchScore(query, query) > titleMatchScore(query, "Age of Mythology: Retold"))
    }
}
