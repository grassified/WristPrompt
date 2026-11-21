package io.github.grassified.wristprompt.openrouter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException

class DebugEndpointProtocolTest {
    @Test fun descriptiveRowsPreserveDuplicatesAndAmbiguousVariants() = runBlocking {
        val east = """{"model_id":"example/permanent","provider_name":"Example Cloud",
            "tag":"example","name":"US variant","service_tier":"priority","quantization":"fp8",
            "context_length":32000,"max_prompt_tokens":30000,"max_completion_tokens":2048,
            "supported_parameters":["temperature","max_tokens"],
            "pricing":{"prompt":"0.0000005","completion":"1e-6","input_audio_cache":"0.00001","discount":0},
            "routing_slug":"example/us-east","zdr":true,"byok":true}"""
        val west = east.replace("US variant", "EU variant").replace("us-east", "eu-west")
        val rows = (load(envelope(east, west, east)) as EndpointDetails.Loaded).rows
        assertEquals(3, rows.size)
        assertEquals(rows[0], rows[2])
        assertEquals(listOf("example", "example", "example"), rows.map { it.reportedTag })
        assertEquals(listOf("US variant", "EU variant", "US variant"), rows.map { it.endpointName })
        assertEquals("Example Cloud", rows[0].providerName)
        assertEquals("example/permanent", rows[0].modelId)
        assertEquals("priority", rows[0].serviceTier)
        assertEquals("fp8", rows[0].quantization)
        assertEquals(32000, rows[0].contextTokens)
        assertEquals(30000, rows[0].maxPromptTokens)
        assertEquals(2048, rows[0].maxCompletionTokens)
        assertEquals(listOf("temperature", "max_tokens"), rows[0].supportedParameters)
        assertEquals(
            mapOf(EndpointPrice.INPUT to "0.0000005", EndpointPrice.OUTPUT to "1e-6", EndpointPrice.AUDIO_CACHE to "0.00001"),
            rows[0].prices,
        )
        assertFalse(rows.toString().contains("example/us-east"))
    }

    @Test fun emptyAndMissingFieldsRemainDistinctFromAnExplicitEmptyParameterList() = runBlocking {
        assertEquals(EndpointDetails.Loaded(emptyList()), load(envelope()))
        val rows = (load(envelope(
            """{"provider_name":null,"quantization":null,"max_completion_tokens":null,"unknown":{"anything":true}}""",
            """{"model_id":"example/model:free","supported_parameters":[]}""",
        )) as EndpointDetails.Loaded).rows
        assertEquals(PublicEndpointMetadata(null, null, null, null, null, null, null, null, null, null, emptyMap()), rows[0])
        assertEquals(emptyList<String>(), rows[1].supportedParameters)
    }

    @Test fun unsafePathsNeverEnqueueAndValidVariantsRemainOneSegment() = runBlocking {
        var count = 0
        val protocol = DebugEndpointProtocol(OkHttpClient.Builder().addInterceptor {
            assertEndpointRequest(it.request())
            count++
            response(it.request(), envelope().toResponseBody()).build()
        })
        for (id in listOf("https://unsafe.invalid/other", "example/../other", "example/..",
            "example/%2Fescape", "example/model?query", "example/model#fragment",
            "example\\model", "example/model/elsewhere", "example/\u0000",
        )) {
            assertEquals(EndpointDetails.Failed(CatalogProblem.MALFORMED), protocol.load(candidate.copy(id = id)))
        }
        assertEquals(0, count)
        assertEquals(EndpointDetails.Loaded(emptyList()), protocol.load(candidate))
        assertEquals(1, count)
    }

    @Test fun rejectsWrongIdentityAndMalformedPayloads() = runBlocking {
        for (body in listOf(
            envelope().replace(candidate.id, candidate.canonicalSlug),
            envelope("""{"model_id":"example/other"}"""),
            envelope("""{"supported_parameters":false}"""),
            envelope("""{"provider_name":"bad\u202e"}"""),
            envelope("""{"pricing":{"prompt":"NaN"}}"""),
            """{"data":{"id":"example/model:free","endpoints":null}}""",
            "{broken",
        )) {
            assertEquals(EndpointDetails.Failed(CatalogProblem.MALFORMED), load(body))
        }
    }

    @Test fun rejectsEndpointParameterStringDepthAndByteOverflows() = runBlocking {
        val tooLarge = listOf(
            envelope(*Array(65) { "{}" }),
            envelope("""{"tag":"${"a".repeat(161)}"}"""),
            envelope("""{"supported_parameters":[${List(65) { "\"temperature\"" }.joinToString()}]}"""),
            envelope("""{"other":"${"x".repeat(8193)}"}"""),
            "[".repeat(25) + "]".repeat(25),
            " ".repeat(256 * 1024 + 1),
        )
        for (body in tooLarge) assertEquals(EndpointDetails.Failed(CatalogProblem.TOO_LARGE), load(body))
    }

    @Test fun safeStatusErrorsAndTimeout() = runBlocking {
        for ((status, problem) in mapOf(
            401 to CatalogProblem.ACCESS_DENIED, 403 to CatalogProblem.ACCESS_DENIED,
            404 to CatalogProblem.NOT_FOUND, 429 to CatalogProblem.RATE_LIMITED,
            500 to CatalogProblem.SERVICE, 503 to CatalogProblem.SERVICE,
        )) {
            assertEquals(EndpointDetails.Failed(problem), load("server details discarded", status))
        }
        val timeout = DebugEndpointProtocol(OkHttpClient.Builder().addInterceptor {
            assertEndpointRequest(it.request())
            throw SocketTimeoutException()
        })
        assertEquals(EndpointDetails.Failed(CatalogProblem.TIMEOUT), timeout.load(candidate))
    }

    @Test fun transportRejectsCrossHostRedirectAndDoesNotRetry503() = runBlocking {
        for ((status, problem) in listOf(
            "302 Found\r\nLocation: https://elsewhere.invalid/leak" to CatalogProblem.REDIRECT,
            "503 Unavailable\r\nRetry-After: 0" to CatalogProblem.SERVICE,
        )) {
            socketServer().use { server ->
                val peer = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        readRequest(socket)
                        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encodeToByteArray())
                    }
                    server.soTimeout = 250
                    assertThrows(SocketTimeoutException::class.java) { server.accept().close() }
                }
                assertEquals(EndpointDetails.Failed(problem), localClient(server).load(candidate))
                peer.await()
            }
        }
    }

    @Test fun cancellationClosesActiveEndpointHttpBody() = runBlocking {
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
                        // TCP reset also proves closure.
                    }
                }
            }
            val load = async { localClient(server).load(candidate) }
            withTimeout(5000) { bodyStarted.await() }
            load.cancelAndJoin()
            withTimeout(5000) { peer.await() }
        }
    }

    private fun socketServer() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }

    private fun localClient(server: ServerSocket) = DebugEndpointProtocol(
        OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor {
            val request = it.request()
            assertEndpointRequest(request)
            val url = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
            it.proceed(request.newBuilder().url(url).build())
        },
    )

    private fun readRequest(socket: java.net.Socket): java.io.BufferedReader {
        socket.soTimeout = 5000
        val reader = socket.getInputStream().bufferedReader()
        assertEquals("GET /api/v1/models/example/model:free/endpoints HTTP/1.1", reader.readLine())
        val headers = generateSequence(reader::readLine).takeWhile { it.isNotEmpty() }.toList()
        assertTrue(headers.none { it.startsWith("Authorization:", ignoreCase = true) })
        return reader
    }

    private fun assertEndpointRequest(request: Request) {
        assertEquals("https://openrouter.ai/api/v1/models/example/model:free/endpoints", request.url.toString())
        assertEquals(listOf("api", "v1", "models", "example", "model:free", "endpoints"), request.url.pathSegments)
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertEquals("identity", request.header("Accept-Encoding"))
    }

    private suspend fun load(json: String, status: Int = 200) =
        DebugEndpointProtocol(OkHttpClient.Builder().addInterceptor {
            assertEndpointRequest(it.request())
            response(it.request(), json.toResponseBody(), status).build()
        }).load(candidate)

    private fun envelope(vararg rows: String) =
        """{"data":{"id":"${candidate.id}","endpoints":[${rows.joinToString()}]}}"""

    private val candidate = PublicModelCandidate(
        "example/model:free", "example/permanent", "Example", null, null, null, null, null,
    )
}
