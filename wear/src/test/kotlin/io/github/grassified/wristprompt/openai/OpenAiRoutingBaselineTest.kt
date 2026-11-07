package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.auth.*
import io.github.grassified.wristprompt.bridge.*
import io.github.grassified.wristprompt.storage.SecretRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OpenAiRoutingBaselineTest {
    @Test fun explicitBillingRouteOwnsDiscoveryModelPreferenceAndWireRequest() = runBlocking {
        val planCatalog = """{"models":[
            {"slug":"gpt-6.1-sol","display_name":"Plan display name","visibility":"list"},
            {"slug":"hidden-model","display_name":"Hidden","visibility":"hidden"},
            {"slug":"second-model","display_name":"Second","visibility":"list"}]}"""
        val apiCatalog = """{"data":[{"id":"gpt-6.1-sol"},{"id":"gpt-6.1-sol-unknown"}]}"""
        withRoutes { router, keys ->
            val requests = mutableListOf<WireRequest>()
            val http = OpenAiHttp(OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val bearer = request.header("Authorization")
                val body = request.body?.let { payload ->
                    Buffer().also(payload::writeTo).readUtf8()
                }
                requests += WireRequest(request.method, request.url.toString(), bearer,
                    request.header("Accept"), request.headers.names(), request.body?.contentType()?.toString(), body)
                val (content, type) = when (request.method) {
                    "GET" -> (if (bearer == "Bearer synthetic-plan-token") planCatalog else apiCatalog) to "application/json"
                    "POST" -> ("data: {\"type\":\"response.output_text.delta\",\"delta\":\"synthetic answer\"}\n\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n") to "text/event-stream"
                    else -> error("Unexpected synthetic HTTP method")
                }
                // Never call proceed: the external request is observed and answered in-process.
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("Synthetic").body(content.toResponseBody(type.toMediaType())).build()
            }.build())
            val session = InferenceSession(router, OpenAiProvider(http), router::isCurrent)
            val input = Json.parseToJsonElement("""[{"role":"user","content":"A synthetic question"}]""")
                .jsonArray.map { it.jsonObject }
            val plan = session.catalog()
            assertEquals(listOf("gpt-6.1-sol", "second-model"), plan.models.map { it.slug })
            assertEquals("Plan display name", plan.models.first().displayName)
            assertNull(plan.models.first().thinking)
            assertNull(plan.models.first().imageGeneration)
            expectProblem(ApiProblem.GENERATION_UNSUPPORTED) { session.image(plan, "gpt-6.1-sol", input) {} }
            val planOwner = router.conversationIdentity(plan.credential)
            router.selectModel(plan.credential, "second-model")
            assertEquals("synthetic answer", session.chat(plan, "second-model", input) {}.output)

            keys.selectMode(CredentialMode.OPENAI_API_KEY)
            val api = session.catalog()
            assertEquals(listOf("gpt-6.1-sol", "gpt-6.1-sol-unknown"), api.models.map { it.displayName })
            assertNotEquals(planOwner, router.conversationIdentity(api.credential))
            assertFalse(router.isCurrent(plan.credential))
            assertNull(router.selectedModel(api.credential))
            val reviewed = api.models.first()
            assertNotNull(reviewed.imageGeneration)
            assertNull(api.models.last().imageGeneration)
            assertNull(api.models.last().thinking)
            val explicitThinking = InferenceConfiguration(
                controls = listOf(checkNotNull(reviewed.thinking).selection("low")),
            )
            router.selectModel(api.credential, reviewed.slug)
            assertTrue(session.chat(api, reviewed.slug, input, explicitThinking) {}.completed)
            assertEquals(reviewed.slug, router.selectedModel(api.credential))
            expectProblem(ApiProblem.NO_MODELS) { session.chat(api, "not-in-catalog", input) {} }
            expectProblem(ApiProblem.THINKING_UNSUPPORTED) {
                session.chat(api, api.models.last().slug, input, explicitThinking) {}
            }

            keys.selectMode(CredentialMode.CHATGPT_PLAN)
            val returned = router.current()
            assertEquals(planOwner, router.conversationIdentity(returned))
            assertEquals("second-model", router.selectedModel(returned))
            expectProblem(ApiProblem.STALE_ACCOUNT) { session.chat(plan, "second-model", input) {} }
            keys.selectMode(CredentialMode.OPENAI_API_KEY)
            keys.remove()
            expectProblem(ApiProblem.API_KEY_MISSING) { session.catalog() }

            assertEquals(listOf("GET", "POST", "GET", "POST"), requests.map { it.method })
            assertEquals(listOf("Bearer synthetic-plan-token", "Bearer synthetic-plan-token",
                "Bearer synthetic-api-key", "Bearer synthetic-api-key"), requests.map { it.bearer })
            requests.forEach { wire ->
                assertEquals(setOf("Authorization", "Accept"), wire.headers)
                if (wire.method == "GET") {
                    assertEquals("https://api.openai.com/v1/models", wire.url)
                    assertEquals("application/json", wire.accept)
                    assertNull(wire.body)
                } else {
                    assertEquals("https://api.openai.com/v1/responses", wire.url)
                    assertEquals("text/event-stream", wire.accept)
                    assertEquals("application/json; charset=utf-8", wire.contentType)
                }
            }
            val planJson = Json.parseToJsonElement(checkNotNull(requests[1].body))
            val apiJson = Json.parseToJsonElement(checkNotNull(requests[3].body))
            assertEquals(Json.parseToJsonElement("""{"model":"second-model","input":[{"role":"user","content":"A synthetic question"}],"store":false,"stream":true}"""), planJson)
            assertEquals(Json.parseToJsonElement("""{"model":"gpt-6.1-sol","input":[{"role":"user","content":"A synthetic question"}],"store":false,"stream":true,"reasoning":{"effort":"low"}}"""), apiJson)
        }
    }

    private suspend fun withRoutes(block: suspend (CredentialRouter, ApiKeyRepository) -> Unit) = coroutineScope {
        val now = 1_000_000L
        val profiles = WatchProfiles(MemoryRecord(), now = { now })
        val keys = ApiKeyRepository(MemoryRecord()) { now }
        val host = BridgeProtocol.newHostId()
        fun request() = AuthStartRequest(1, BridgeProtocol.newRequestId(), host, now)
        val login = request()
        profiles.prepare("synthetic-phone", login)
        profiles.accept("synthetic-phone", ProfileProtocol.encode(ProfileTransfer(1, login.requestId,
            BridgeProtocol.newRequestId(), CredentialProfile(
                RegistrationIdentity("oaiapp_test", OpenAiAuth.ISSUER, "synthetic-subject"), host,
                "Synthetic account", null, false, "synthetic-id", "synthetic-plan-token",
                "synthetic-refresh", "Bearer", 3600, now, OpenAiAuth.scopes(OpenAiAuth.REQUESTED_SCOPES)))))
        val setup = request()
        keys.prepare("synthetic-phone", setup)
        keys.accept("synthetic-phone", ProfileProtocol.encode(ApiKeyTransfer(1, setup.requestId,
            BridgeProtocol.newRequestId(), host, "synthetic-api-key")))
        val routingScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        try {
            block(CredentialRouter(profiles, keys, { error("Unexpected refresh of synthetic credential") }, routingScope), keys)
        } finally {
            routingScope.cancel()
        }
    }

    private suspend fun expectProblem(expected: ApiProblem, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $expected")
        } catch (failure: ApiFailure) {
            assertEquals(expected, failure.problem)
        }
    }

    private class MemoryRecord : SecretRecord {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(plaintext: ByteArray) { bytes = plaintext.clone() }
    }

    private data class WireRequest(
        val method: String, val url: String, val bearer: String?, val accept: String?,
        val headers: Set<String>, val contentType: String?, val body: String?,
    )
}
