package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.auth.CredentialMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Run against the real SSE decoder, provider and session on both sides of the extraction. */
class OpenAiTerminalParityTest {
    @Test fun firstCompletionOwnsTextAndOpaqueOutputDespiteUnexpectedAndDuplicateEvents() = runBlocking {
        val fixture = Fixture(delta + toolProgress + completed + completed + frame("{"))
        val progress = mutableListOf<Pair<String, Boolean>>()
        val result = fixture.chat { progress += it.output to it.completed }

        assertTrue(result.completed)
        assertEquals(listOf("partial" to false, "final" to true), progress)
        assertEquals(Json.parseToJsonElement(reasoning).jsonObject, result.outputItems!!.first())
        assertEquals(1, fixture.posts)
    }

    @Test fun nonSuccessfulTerminalsKeepOnlyDisplayProgressAndNeverRetry() = runBlocking {
        val cases = listOf(
            Triple("failed before completion", failed + completed, ApiProblem.TEMPORARY),
            Triple("incomplete before completion", incomplete + completed, ApiProblem.INCOMPLETE),
            Triple("malformed JSON", frame("{") + completed, ApiProblem.PROTOCOL),
            Triple("missing response", frame("""{"type":"response.completed"}"""), ApiProblem.PROTOCOL),
            Triple("wrong status", frame("""{"type":"response.completed","response":{"status":"failed"}}"""), ApiProblem.PROTOCOL),
            Triple("mismatched event name", "event: response.failed\n$completed", ApiProblem.PROTOCOL),
            Triple("premature EOF", "", ApiProblem.INTERRUPTED),
            Triple("unterminated completion", completed.trimEnd(), ApiProblem.INTERRUPTED),
            Triple("done sentinel", frame("[DONE]") + completed, ApiProblem.INTERRUPTED),
            Triple("unsupported tool progress", toolProgress, ApiProblem.INTERRUPTED),
        )
        for ((label, tail, expected) in cases) {
            val fixture = Fixture(delta + tail)
            val progress = mutableListOf<Pair<String, Boolean>>()
            try {
                fixture.chat { progress += it.output to it.completed }
                fail("Unexpected completion for $label")
            } catch (failure: ApiFailure) {
                assertEquals(label, expected, failure.problem)
            }
            assertEquals(label, listOf("partial" to false), progress)
            assertEquals(label, 1, fixture.posts)
        }
    }

    @Test fun imageResultRequiresResponseCompletionWithOneValidGeneratedOutput() = runBlocking {
        val imageProgress = frame("""{"type":"response.image_generation_call.completed","item_id":"ig_synthetic"}""")
        val image = Fixture(imageProgress + completion(
            """[{"type":"image_generation_call","id":"ig_synthetic","status":"completed","result":"AQID"}]""",
        ), CredentialMode.OPENAI_API_KEY)
        val result = image.generate()
        assertTrue(result.completed)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.generated!!.bytes)
        assertEquals(Json.parseToJsonElement("""{"type":"image_generation_call","id":"ig_synthetic"}"""),
            result.outputItems!!.single())
        assertEquals(1, image.posts)

        for ((tail, problem) in listOf("" to ApiProblem.INTERRUPTED, completed to ApiProblem.GENERATION_OUTPUT)) {
            val invalid = Fixture(imageProgress + tail, CredentialMode.OPENAI_API_KEY)
            try {
                invalid.generate { fail("Invalid image published completion") }
                fail("Invalid image returned completion")
            } catch (failure: ApiFailure) {
                assertEquals(problem, failure.problem)
            }
            assertEquals(1, invalid.posts)
        }
    }

    private class Fixture(body: String, mode: CredentialMode = CredentialMode.CHATGPT_PLAN) {
        private val lease = CredentialLease("fixture-account", 1, "synthetic-bearer", Long.MAX_VALUE, mode)
        private val model = AiModel("gpt-6.1-sol", "Fixture", imageGeneration = OpenAiGeneration.capability("gpt-6.1-sol", mode))
        private val catalog = PreparedCatalog(lease, listOf(model))
        var posts = 0; private set
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals(ResponsesProtocol.RESPONSES_URL, request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("Bearer synthetic-bearer", request.header("Authorization"))
            posts++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        private val session = InferenceSession({ lease }, OpenAiProvider(OpenAiHttp(client))) { it == lease }
        private val input = listOf(ImageInput.user("Synthetic question"))

        suspend fun chat(progress: suspend (StreamResult) -> Unit) = session.chat(catalog, model.slug, input, onProgress = progress)
        suspend fun generate(progress: suspend (StreamResult) -> Unit = {}) = session.image(catalog, model.slug, input, progress)
    }

    private companion object {
        fun frame(json: String) = "data: $json\n\n"
        fun completion(items: String) = frame("""{"type":"response.completed","response":{"status":"completed","output":$items}}""")

        const val reasoning = """{"type":"reasoning","id":"rs_synthetic","encrypted_content":"opaque","summary":[]}"""
        val delta = frame("""{"type":"response.output_text.delta","delta":"partial"}""")
        val toolProgress = frame("""{"type":"response.function_call_arguments.done","arguments":"{}"}""")
        val failed = frame("""{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error"}}}""")
        val incomplete = frame("""{"type":"response.incomplete","response":{"status":"incomplete"}}""")
        val completed = completion("""[$reasoning,{"type":"message","role":"assistant","content":[{"type":"output_text","text":"final"}]}]""")
    }
}
