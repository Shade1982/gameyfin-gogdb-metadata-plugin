package io.github.shade1982.gameyfin.plugins.gogdb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val httpTestJson = Json { ignoreUnknownKeys = true }

class GogDbHttpTest {
    @Test
    fun `title search follows catalog cursors fetches GoGDB records and caches results`() {
        val cursors = mutableListOf<String?>()
        val productRequests = mutableListOf<String>()
        val client = mockClient { request ->
            when {
                request.url.encodedPath == "/v1/catalog" -> {
                    cursors += request.url.parameters["searchAfter"]
                    val products = when (request.url.parameters["searchAfter"]) {
                        "0" -> listOf(
                            CatalogProduct("101", "Portal 2"),
                            CatalogProduct("102", "Portal Stories")
                        )
                        "102" -> listOf(CatalogProduct("103", "Portal"))
                        else -> emptyList()
                    }
                    respondJson(httpTestJson.encodeToString(CatalogResponse(3, products)))
                }
                request.url.encodedPath.startsWith("/data/products/") -> {
                    val id = request.url.encodedPath.split('/')[3]
                    productRequests += id
                    val name = when (id) {
                        "101" -> "Portal 2"
                        "102" -> "Portal Stories"
                        else -> "Portal"
                    }
                    respondJson(httpTestJson.encodeToString(GogDbProduct(id.toInt(), name)))
                }
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val provider = GogDbPlugin.GogDbMetadataProvider(client)

        try {
            val results = provider.fetchByTitle("Portal", emptySet(), 3)

            assertEquals(listOf<String?>("0", "102"), cursors)
            assertEquals(listOf("101", "102", "103"), productRequests)
            assertEquals("Portal", results.first().title)
            assertEquals(3, results.size)

            val repeated = provider.fetchByTitle("Portal", emptySet(), 3)
            assertEquals(3, repeated.size)
            assertEquals(listOf("101", "102", "103"), productRequests)
        } finally {
            client.close()
        }
    }

    @Test
    fun `catalog errors return no results and do not fetch product data`() {
        var requests = 0
        val client = mockClient {
            requests++
            respondJson("{}", HttpStatusCode.ServiceUnavailable)
        }
        val provider = GogDbPlugin.GogDbMetadataProvider(client)

        try {
            assertTrue(provider.fetchByTitle("Portal", emptySet(), 1).isEmpty())
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun `missing GoGDB product responses are skipped`() {
        val client = mockClient { request ->
            if (request.url.encodedPath == "/v1/catalog") {
                respondJson(
                    httpTestJson.encodeToString(
                        CatalogResponse(1, listOf(CatalogProduct("123", "Portal")))
                    )
                )
            } else {
                respondJson("{}", HttpStatusCode.NotFound)
            }
        }
        val provider = GogDbPlugin.GogDbMetadataProvider(client)

        try {
            assertTrue(provider.fetchByTitle("Portal", emptySet(), 1).isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun `search continues past unavailable GoGDB records until metadata limit is met`() {
        val cursors = mutableListOf<String?>()
        val client = mockClient { request ->
            when (request.url.encodedPath) {
                "/v1/catalog" -> {
                    cursors += request.url.parameters["searchAfter"]
                    val product = when (request.url.parameters["searchAfter"]) {
                        "0" -> listOf(CatalogProduct("101", "Portal 2"))
                        "101" -> listOf(CatalogProduct("102", "Portal"))
                        else -> emptyList()
                    }
                    respondJson(httpTestJson.encodeToString(CatalogResponse(2, product)))
                }
                "/data/products/101/product.json" ->
                    respondJson("{}", HttpStatusCode.NotFound)
                "/data/products/102/product.json" ->
                    respondJson(httpTestJson.encodeToString(GogDbProduct(102, "Portal")))
                else -> error("Unexpected URL: ${request.url}")
            }
        }
        val provider = GogDbPlugin.GogDbMetadataProvider(client)

        try {
            val results = provider.fetchByTitle("Portal", emptySet(), 1)

            assertEquals(listOf<String?>("0", "101"), cursors)
            assertEquals("Portal", results.single().title)
        } finally {
            client.close()
        }
    }

    private fun mockClient(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData
        ) -> io.ktor.client.request.HttpResponseData
    ): HttpClient = HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) {
            json(httpTestJson)
        }
        expectSuccess = false
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )
}
