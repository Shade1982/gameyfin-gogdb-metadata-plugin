package io.github.shade1982.gameyfin.plugins.gogdb

import kotlinx.serialization.Serializable
import org.gameyfin.pluginapi.gamemetadata.GameMetadata
import org.gameyfin.pluginapi.gamemetadata.GameFeature
import org.gameyfin.pluginapi.gamemetadata.Genre
import org.gameyfin.pluginapi.gamemetadata.Platform
import org.gameyfin.pluginapi.gamemetadata.Theme
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

internal fun rankSearchResults(
    title: String,
    results: Iterable<CatalogProduct>,
    maxResults: Int,
    minimumScore: Int = 100
): List<CatalogProduct> {
    if (maxResults <= 0) return emptyList()
    return results
        .map { it to titleMatchScore(title, it.title) }
        .filter { it.second >= minimumScore }
        .sortedWith(compareByDescending<Pair<CatalogProduct, Int>> {
            it.second
        }.thenByDescending {
            normalizeSearchTitle(it.first.title).startsWith(normalizeSearchTitle(title))
        }.thenByDescending { it.first.title.length })
        .take(maxResults)
        .map { it.first }
}

internal fun rankMetadataResults(
    title: String,
    results: Iterable<GameMetadata>,
    maxResults: Int,
    minimumScore: Int = 100
): List<GameMetadata> {
    if (maxResults <= 0) return emptyList()
    return results
        .map { it to titleMatchScore(title, it.title) }
        .filter { it.second >= minimumScore }
        .sortedWith(compareByDescending<Pair<GameMetadata, Int>> {
            it.second
        }.thenByDescending {
            normalizeSearchTitle(it.first.title).startsWith(normalizeSearchTitle(title))
        }.thenByDescending { it.first.title.length })
        .take(maxResults)
        .map { it.first }
}

internal fun prepareSearchTitle(title: String): String = normalizeSearchTitle(title)

internal fun titleMatchScore(query: String, candidateTitle: String): Int {
    val queryNormalized = normalizeSearchTitle(query)
    val candidateNormalized = normalizeSearchTitle(candidateTitle)
    if (queryNormalized.isBlank() || candidateNormalized.isBlank()) return 0
    if (queryNormalized == candidateNormalized) return 1000

    val queryTerms = searchableTerms(queryNormalized)
    val candidateTerms = searchableTerms(candidateNormalized)
    if (queryTerms.isEmpty() || candidateTerms.isEmpty()) return 0

    val queryCore = queryTerms.joinToString(" ")
    val candidateCore = candidateTerms.joinToString(" ")
    // Edition words are ignored: the base game must match, whatever the edition.
    if (queryCore == candidateCore) return 500
    val allTermsPresent = queryTerms.all { it in candidateTerms }
    val fuzzyScore = me.xdrop.fuzzywuzzy.FuzzySearch.ratio(queryCore, candidateCore)
    return when {
        allTermsPresent -> 200 + fuzzyScore + if (candidateCore.startsWith(queryCore)) 30 else 0
        fuzzyScore >= 90 -> 100 + fuzzyScore
        else -> 0
    }
}

private fun normalizeSearchTitle(title: String): String = title
    .replace(Regex("""(?i)\([^)]*\)|\[[^]]*]"""), " ")
    .replace(Regex("""[^\p{L}\p{Nd}]+"""), " ")
    .lowercase()
    .replace(Regex("""\s+"""), " ")
    .trim()

private fun searchableTerms(normalizedTitle: String): List<String> = normalizedTitle
    .split(' ')
    .map(String::trim)
    .filter(String::isNotBlank)
    .filterNot { it in TITLE_NOISE_WORDS }

private val TITLE_NOISE_WORDS = setOf(
    "edition",
    "premium",
    "deluxe",
    "definitive",
    "complete",
    "enhanced",
    "remastered",
    "remake",
    "ultimate",
    "collector",
    "special",
    "goty",
    "anniversary",
    "of",
    "the",
    "and",
    "a",
    "an",
    "for",
    "to",
    "in",
    "on"
)

internal data class CatalogPage(
    val products: List<CatalogProduct>,
    val productCount: Int?
)

internal object CatalogPagination {
    suspend fun collect(
        pageSize: Int,
        fetchPage: suspend (searchAfter: String) -> CatalogPage,
        shouldStop: suspend (List<CatalogProduct>) -> Boolean
    ): List<CatalogProduct> {
        require(pageSize > 0)
        val collected = mutableListOf<CatalogProduct>()
        val seenIds = mutableSetOf<String>()
        var searchAfter = "0"

        while (true) {
            val page = fetchPage(searchAfter)
            if (page.products.isEmpty()) return collected

            val previousSize = collected.size
            page.products.forEach { product ->
                if (seenIds.add(product.id)) collected += product
            }
            if (shouldStop(collected)) return collected
            if (collected.size == previousSize) return collected

            val lastId = page.products.last().id
            if (lastId == searchAfter) return collected
            searchAfter = lastId

            val totalCount = page.productCount
            if (totalCount != null && collected.size >= totalCount) return collected
            if (page.products.size < pageSize && totalCount == null) return collected
        }
    }
}

internal class BoundedCache<K, V>(
    private val maxSize: Int,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    init {
        require(maxSize > 0)
        require(ttlMillis > 0)
    }

    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val entries = object : LinkedHashMap<K, Entry<V>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > maxSize
    }

    @Synchronized
    operator fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= nowMillis()) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    operator fun set(key: K, value: V) {
        val now = nowMillis()
        entries.entries.removeIf { it.value.expiresAt <= now }
        entries[key] = Entry(value, now + ttlMillis)
    }
}

internal fun toPlatform(system: String): Platform? = when (system.lowercase()) {
    "windows" -> Platform.PC_MICROSOFT_WINDOWS
    "linux" -> Platform.LINUX
    "osx", "mac", "macos" -> Platform.MAC
    else -> null
}

internal fun imageUrl(hash: String?): URI? {
    if (hash == null || !hash.matches(Regex("[a-fA-F0-9]{64}"))) return null
    return URI.create("https://images.gog-statics.com/$hash.jpg")
}

internal fun parseReleaseDate(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching {
            LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC)
        }.getOrNull()
}

internal fun cleanDescription(description: String?): String? {
    if (description.isNullOrBlank()) return null
    val plainText = description
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6])\\s*>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .decodeHtmlEntities()
        .trim()
    return plainText.takeIf(String::isNotBlank)
}

internal fun toMetadata(product: GogDbProduct): GameMetadata = GameMetadata(
    originalId = product.id.toString(),
    title = product.title,
    description = cleanDescription(product.description),
    coverUrls = imageUrl(product.imageBoxart)?.let(::setOf),
    release = parseReleaseDate(product.globalDate ?: product.storeDate),
    developedBy = product.developers.toSet().takeIf { it.isNotEmpty() },
    publishedBy = (product.publishers + listOfNotNull(product.publisher))
        .toSet().takeIf { it.isNotEmpty() },
    platforms = (product.csSystems + product.compSystems)
        .mapNotNull(::toPlatform).toSet().takeIf { it.isNotEmpty() },
    genres = (product.properties + product.tags).mapNotNull { mapGenre(it.name) }.toSet(),
    themes = (product.properties + product.tags).mapNotNull { mapTheme(it.name) }.toSet(),
    features = product.features.mapNotNull { mapFeature(it.name) }.toSet(),
    keywords = (product.properties + product.tags)
        .map(GogDbNamedValue::name)
        .filter { mapGenre(it) == null && mapTheme(it) == null }
        .toSet().takeIf { it.isNotEmpty() },
    headerUrls = imageUrl(product.imageBackground)?.let(::setOf),
    screenshotUrls = product.screenshots.mapNotNull(::imageUrl).toSet().takeIf { it.isNotEmpty() },
    videoUrls = product.videos.mapNotNull { safeWebUri(it.videoUrl) }.toSet().takeIf { it.isNotEmpty() },
    userRating = null
)

internal fun mapGenre(value: String): Genre? {
    val normalized = value.normalizedEnumName()
    return when (normalized) {
        "SIMULATION", "SIM" -> Genre.SIMULATOR
        "RPG", "ROLEPLAYING", "CRPG", "JRPG" -> Genre.ROLE_PLAYING
        "SPORTS" -> Genre.SPORT
        "RTS" -> Genre.REAL_TIME_STRATEGY
        "TURNBASED" -> Genre.TURN_BASED_STRATEGY
        else -> enumValue<Genre>(value)?.takeUnless { it == Genre.UNKNOWN }
    }
}

internal fun mapTheme(value: String): Theme? {
    val normalized = value.normalizedEnumName()
    return when (normalized) {
        "SCIFI", "SCIENCEFICTION", "SPACE", "CYBERPUNK", "STEAMPUNK", "POSTAPOCALYPTIC" ->
            Theme.SCIENCE_FICTION
        "OPENWORLD" -> Theme.OPEN_WORLD
        "WORLDWAR", "MILITARY", "WAR" -> Theme.WARFARE
        else -> enumValue<Theme>(value)?.takeUnless { it == Theme.UNKNOWN }
    }
}

internal fun mapFeature(value: String): GameFeature? {
    val normalized = value.normalizedEnumName()
    return when (normalized) {
        "SINGLE" -> GameFeature.SINGLEPLAYER
        "MULTI" -> GameFeature.MULTIPLAYER
        else -> enumValue<GameFeature>(value)
    }
}

private inline fun <reified T : Enum<T>> enumValue(value: String): T? {
    val normalized = value.normalizedEnumName()
    return enumValues<T>().firstOrNull {
        it.name.normalizedEnumName() == normalized
    }
}

private fun String.normalizedEnumName(): String = filter(Char::isLetterOrDigit).uppercase()

private fun safeWebUri(value: String): URI? = runCatching {
    URI.create(value).takeIf { it.scheme == "https" || it.scheme == "http" }
}.getOrNull()

private fun String.decodeHtmlEntities(): String {
    val namedEntities = mapOf(
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&nbsp;" to " "
    )
    var decoded = this
    namedEntities.forEach { (entity, replacement) ->
        decoded = decoded.replace(entity, replacement, ignoreCase = true)
    }
    return Regex("&#(?:x([0-9a-fA-F]+)|([0-9]+));").replace(decoded) { match ->
        val codePoint = match.groupValues[1].takeIf(String::isNotEmpty)?.toIntOrNull(16)
            ?: match.groupValues[2].toIntOrNull()
        if (codePoint != null && Character.isValidCodePoint(codePoint)) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }
}
