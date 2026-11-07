package io.github.grassified.wristprompt.openai

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiStreamBaselineTest {
    @Test fun stopClosesTheSocketAndOnlyCompletedExchangesReachTheNextRequest() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 5, loopback).use { server ->
            server.soTimeout = 10_000
            val peerSawClose = CompletableDeferred<Unit>()
            val traffic = async(Dispatchers.IO) {
                val attempts = mutableListOf<JsonObject>()
                val catalog = """{"models":[{"slug":"fixture-model","display_name":"Fixture","visibility":"list"}]}"""
                val responses = listOf(catalog, completed, delta + failed, delta, delta, completed)
                for ((index, body) in responses.withIndex()) {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val source = socket.source().buffer()
                        val requestLine = source.readUtf8LineStrict()
                        assertEquals(if (index == 0) "GET /v1/models HTTP/1.1" else "POST /v1/responses HTTP/1.1", requestLine)
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = source.readUtf8LineStrict()
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        assertEquals("Bearer synthetic-loopback-plan", headers["authorization"])
                        if (index != 0) {
                            val requestBody = source.readUtf8(checkNotNull(headers["content-length"]).toLong())
                            attempts += Json.parseToJsonElement(requestBody).jsonObject
                        }
                        val awaitingStop = index == 4
                        val bytes = body.encodeToByteArray()
                        val length = if (awaitingStop) bytes.size + 1000 else bytes.size
                        val type = if (index == 0) "application/json" else "text/event-stream"
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                            "Content-Length: $length\r\nConnection: close\r\n\r\n").encodeToByteArray())
                        output.write(bytes)
                        output.flush()
                        if (awaitingStop) {
                            // Keep the response unfinished. EOF/reset here must come from the client.
                            try { assertTrue(source.exhausted()) } catch (_: SocketException) { }
                            peerSawClose.complete(Unit)
                        }
                    }
                }
                attempts
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { main ->
                val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                    .retryOnConnectionFailure(false).followRedirects(false).addInterceptor { chain ->
                        val request = chain.request()
                        assertEquals("api.openai.com", request.url.host)
                        assertEquals("https", request.url.scheme)
                        val url = request.url.newBuilder().scheme("http").host("127.0.0.1").port(server.localPort).build()
                        chain.proceed(request.newBuilder().url(url).build())
                    }.build()
                val session = InferenceSession(
                    { CredentialLease("synthetic-loopback-account", 1, "synthetic-loopback-plan", Long.MAX_VALUE) },
                    OpenAiProvider(OpenAiHttp(client)), { true },
                )
                Dispatchers.setMain(main)
                try {
                    withContext(main) {
                        val vm = InferenceViewModel(MutableStateFlow(InferenceAccessState(
                            client = "synthetic-loopback-account", generation = 1, usable = true)), session)
                        try {
                            withTimeout(10_000) {
                                vm.state.first { it.stage == ProofStage.READY }
                                send(vm, "completed question")
                                val first = vm.chat.first { it.stage == ChatStage.COMPLETED }
                                assertEquals("final answer", first.messages.last().text)

                                send(vm, "failed question")
                                val failure = vm.chat.first { it.stage == ChatStage.FAILED }
                                assertEquals(ChatMessageStatus.EXCLUDED, failure.messages.last().status)

                                send(vm, "interrupted question")
                                val interrupted = vm.chat.first { it.stage == ChatStage.FAILED }
                                assertEquals(ApiProblem.INTERRUPTED, interrupted.problem)
                                assertEquals("streamed partial", interrupted.partial)

                                send(vm, "stopped question")
                                vm.chat.first { it.stage == ChatStage.RECEIVING && it.partial == "streamed partial" }
                                assertFalse(peerSawClose.isCompleted)
                                vm.stop()
                                assertEquals(ChatStage.CANCELLED, vm.chat.value.stage)
                                assertEquals(ChatMessageStatus.EXCLUDED, vm.chat.value.messages.last().status)
                                peerSawClose.await()

                                send(vm, "explicit follow-up")
                                vm.chat.first { it.stage == ChatStage.COMPLETED }
                            }
                        } finally {
                            // A failing socket peer can cancel the test while Main is still in use.
                            withContext(NonCancellable) { vm.viewModelScope.coroutineContext.job.cancelAndJoin() }
                        }
                    }
                } finally {
                    Dispatchers.resetMain()
                    client.connectionPool.evictAll()
                }
            }
            val bodies = traffic.await()
            val replay = Json.parseToJsonElement("""[
                {"role":"user","content":"completed question"},
                {"type":"reasoning","id":"rs_synthetic","encrypted_content":"synthetic-opaque-item"},
                {"type":"message","role":"assistant","status":"completed",
                 "content":[{"type":"output_text","text":"final answer"}]},
                {"role":"user","content":"explicit follow-up"}
            ]""")
            assertEquals(5, bodies.size)
            assertEquals(replay, bodies.last()["input"])
            bodies.forEach { body ->
                assertEquals(JsonPrimitive(false), body["store"])
                assertEquals(JsonPrimitive(true), body["stream"])
                assertFalse("previous_response_id" in body)
            }
        }
    }

    private fun send(vm: InferenceViewModel, question: String) {
        vm.draft(question)
        vm.send()
    }

    private companion object {
        const val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"streamed partial\"}\n\n"
        const val failed = "data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\"}}}\n\n"
        val completed = delta + "data: " + """
            {"type":"response.completed","response":{"status":"completed","output":[
              {"type":"reasoning","id":"rs_synthetic","encrypted_content":"synthetic-opaque-item"},
              {"type":"message","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":"final answer"}]}]}}
        """.trimIndent().replace("\n", "") + "\n\n"
    }
}
