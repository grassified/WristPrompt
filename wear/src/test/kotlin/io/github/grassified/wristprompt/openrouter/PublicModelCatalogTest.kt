package io.github.grassified.wristprompt.openrouter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer


import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.zip.GZIPOutputStream

internal fun catalogRow(n: Int, extra: String = "", name: String = "Fixture $n") =
    """{"id":"example/model-$n:free","canonical_slug":"example/fixed-$n","name":"$name"$extra}"""

internal fun catalogPage(
    rows: List<String>,
    total: Int = rows.size,
    next: String = "null",
) = """{"data":[${rows.joinToString()}],"total_count":$total,"links":{"next":$next}}"""

internal fun response(request: Request, body: ResponseBody, status: Int = 200): Response.Builder =
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("Fixture")
        .code(status).body(body)

class PublicModelCatalogTest {
    @Test fun fixedAnonymousGetsFollowOnlyCalculatedOffsetsAndSortByNameThenId() = runBlocking {
        var pages = 0
        val first = (0 until PAGE_SIZE).reversed().map { catalogRow(it) }
        val catalog = fixture { request ->
            val offset = requireNotNull(request.url.queryParameter("offset")).toInt()
            assertEquals(pages++ * PAGE_SIZE, offset)
            assertEquals(PAGE_SIZE.toString(), request.url.queryParameter("limit"))
            assertEquals("text", request.url.queryParameter("output_modalities"))
            val body = if (offset == 0) catalogPage(first, PAGE_SIZE + 1, "\"https://elsewhere.invalid/arbitrary\"")
            else catalogPage(listOf(catalogRow(PAGE_SIZE, name = "Fixture 0")), PAGE_SIZE + 1)
            response(request, body.toResponseBody()).build()
        }
        val result = catalog.load() as PublicCatalogResult.Loaded
        assertEquals(2, pages)
        assertEquals(PAGE_SIZE + 1, result.candidates.size)
        assertEquals(listOf("example/model-0:free", "example/model-64:free"), result.candidates.take(2).map { it.id })
        assertEquals("example/fixed-0", result.candidates.first().canonicalSlug)
    }

    @Test fun emptyAndModelWideMetadataWithoutEndpointRecords() = runBlocking {
        assertEquals(PublicCatalogResult.Loaded(emptyList()), body(catalogPage(emptyList())))
        val extra = ""","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
            "context_length":128000,"supported_parameters":["max_tokens"],"top_provider":{"max_completion_tokens":4096},
            "endpoints":["/api/v1/chat/completions","/api/v1/responses"]"""
        val loaded = body(catalogPage(listOf(catalogRow(1, extra), catalogRow(2)))) as PublicCatalogResult.Loaded
        val (known, unknown) = loaded.candidates
        assertEquals(listOf("text", "image"), known.inputModalities)
        assertEquals(listOf("text"), known.outputModalities)
        assertEquals(listOf("max_tokens"), known.supportedParameters)
        assertEquals(128000, known.contextTokens)
        assertEquals(4096, known.topProviderCompletionTokens)
        assertNull(unknown.outputModalities)
        assertNull(unknown.contextTokens)
        assertNull(unknown.topProviderCompletionTokens)
    }

    @Test fun duplicateMissingAndChangingPagesFailAtomically() = runBlocking {
        val rows = (0 until PAGE_SIZE).map { catalogRow(it) }
        for (last in listOf(
            catalogPage(listOf(catalogRow(1)), PAGE_SIZE + 1),
            catalogPage(emptyList(), PAGE_SIZE + 1),
            catalogPage(listOf(catalogRow(PAGE_SIZE)), PAGE_SIZE + 2),
            catalogPage(listOf(catalogRow(PAGE_SIZE)), PAGE_SIZE + 1, "\"/spurious\""),
        )) {
            var page = 0
            val catalog = fixture { request ->
                response(request, (if (page++ == 0) catalogPage(rows, PAGE_SIZE + 1, "\"/next\"") else last).toResponseBody())
                    .build()
            }
            assertEquals(PublicCatalogResult.Failed(CatalogProblem.CHANGED), catalog.load())
            assertEquals(2, page)
        }
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.CHANGED), body(catalogPage(listOf(catalogRow(0)), 2)))
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.CHANGED), body(catalogPage(List(2) { catalogRow(0) })))
    }

    @Test fun oversizedBodyRowsTotalStringsAndDepthFail() = runBlocking {
        val largeString = "x".repeat(8193)
        val largeBody = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source() = buffer
            val buffer = Buffer().write(ByteArray(256 * 1024 + 1))
        }
        val invalid = listOf(
            catalogPage(emptyList(), 1025),
            catalogPage(List(PAGE_SIZE + 1) { catalogRow(it) }),
            catalogPage(listOf(catalogRow(1, name = "x".repeat(161)))),
            catalogPage(listOf(catalogRow(1, ""","extra":"$largeString"""".trimEnd()))),
            "[".repeat(25) + "]".repeat(25),
        )
        for (json in invalid) {
            assertEquals(PublicCatalogResult.Failed(CatalogProblem.TOO_LARGE), body(json))
        }
        val streamed = fixture { response(it, largeBody).build() }
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.TOO_LARGE), streamed.load())
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.TOO_LARGE), body(" ".repeat(256 * 1024 + 1)))
    }

    @Test fun rejectsInvalidUtf8AndMalformedOrUnsupportedData() = runBlocking {
        val malformed = listOf(
            "{no",
            "[]",
            """{"data":[],"total_count":"0","links":{}}""",
            """{"data":[],"total_count":0,"links":{"next":false}}""",
            catalogPage(listOf("42")),
            catalogPage(listOf(catalogRow(1, name = "\\u202eMisleading"))),
            catalogPage(listOf(catalogRow(1, name = "\\ud800"))),
            catalogPage(listOf(catalogRow(1).replace("example/model-1:free", "../arbitrary"))),
            catalogPage(listOf(catalogRow(1, ""","architecture":{"output_modalities":"text"}"""))),
        )
        for (json in malformed) assertEquals(json, PublicCatalogResult.Failed(CatalogProblem.MALFORMED), body(json))
        val bytes = catalogPage(listOf(catalogRow(1, name = "é"))).encodeToByteArray()
        bytes[bytes.indexOf(0xc3.toByte()) + 1] = 0
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.MALFORMED),
            fixture { response(it, bytes.toResponseBody()).build() }.load())
    }

    @Test fun rejectsCompressedResponseBeforeDecompression() = runBlocking {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(ByteArray(1024 * 1024)) }
        }.toByteArray()
        val catalog = fixture { response(it, compressed.toResponseBody()).header("Content-Encoding", "gzip").build() }
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.UNSUPPORTED_ENCODING), catalog.load())
    }

    @Test fun safeHttpAndConnectionErrors() = runBlocking {
        for ((status, expected) in listOf(
            302 to CatalogProblem.REDIRECT, 401 to CatalogProblem.ACCESS_DENIED,
            403 to CatalogProblem.ACCESS_DENIED, 404 to CatalogProblem.NOT_FOUND,
            408 to CatalogProblem.TIMEOUT, 429 to CatalogProblem.RATE_LIMITED,
            500 to CatalogProblem.SERVICE, 503 to CatalogProblem.SERVICE,
        )) {
            val catalog = fixture { response(it, "discard me".toResponseBody(), status).build() }
            assertEquals(PublicCatalogResult.Failed(expected), catalog.load())
        }
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.TIMEOUT), fixture { throw SocketTimeoutException() }.load())
        assertEquals(PublicCatalogResult.Failed(CatalogProblem.NETWORK), fixture { throw IOException() }.load())
    }

    @Test fun realTransportNeverFollowsRedirectOrRetries503() = runBlocking {
        for ((head, expected) in listOf(
            "302 Found\r\nLocation: https://elsewhere.invalid/leak" to CatalogProblem.REDIRECT,
            "503 Unavailable\r\nRetry-After: 0" to CatalogProblem.SERVICE,
        )) {
            socketServer().use { server ->
                val peer = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        readRequest(socket)
                        socket.getOutputStream().write("HTTP/1.1 $head\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    }
                    server.soTimeout = 250
                    assertThrows(SocketTimeoutException::class.java) { server.accept().close() }
                }
                assertEquals(PublicCatalogResult.Failed(expected), localClient(server).load())
                peer.await()
            }
        }
    }

    @Test fun cancellationClosesAnInProgressHttpBody() = runBlocking {
        socketServer().use { server ->
            val bodyStarted = CompletableDeferred<Unit>()
            val peer = async(Dispatchers.IO) {
                server.accept().use { socket ->
                    val reader = readRequest(socket)
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n{".encodeToByteArray())
                    bodyStarted.complete(Unit)
                    try {
                        assertNull(reader.readLine())
                    } catch (_: SocketException) {
                        // A reset also proves transport closure.
                    }
                }
            }
            val loading = async { localClient(server).load() }
            withTimeout(5000) { bodyStarted.await() }
            loading.cancelAndJoin()
            withTimeout(5000) { peer.await() }
        }
    }

    private suspend fun body(json: String) = fixture { response(it, json.toResponseBody()).build() }.load()

    private fun fixture(handler: (Request) -> Response) = OpenRouterPublicCatalog(OkHttpClient.Builder().addInterceptor {
        assertPublicRequest(it.request())
        handler(it.request())
    })

    private fun socketServer() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }

    private fun localClient(server: ServerSocket) = OpenRouterPublicCatalog(
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor {
            val request = it.request()
            assertPublicRequest(request)
            val url = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
            it.proceed(request.newBuilder().url(url).build())
        },
    )

    private fun readRequest(socket: java.net.Socket): java.io.BufferedReader {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader()
        assertTrue(reader.readLine().startsWith("GET /api/v1/models?"))
        val headers = generateSequence(reader::readLine).takeWhile { it.isNotEmpty() }.toList()
        assertTrue(headers.none { it.startsWith("Authorization:", ignoreCase = true) })
        return reader
    }
}

internal fun assertPublicRequest(request: Request) {
    assertEquals("https", request.url.scheme)
    assertEquals("openrouter.ai", request.url.host)
    assertEquals(443, request.url.port)
    assertEquals("/api/v1/models", request.url.encodedPath)
    assertEquals("GET", request.method)
    assertNull(request.body)
    assertNull(request.header("Authorization"))
    assertNull(request.header("Cookie"))
    assertEquals("identity", request.header("Accept-Encoding"))
}
