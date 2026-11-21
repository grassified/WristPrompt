package io.github.grassified.wristprompt.openrouter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.wear.compose.material3.AppScaffold
import io.github.grassified.wristprompt.auth.InferenceRoute
import io.github.grassified.wristprompt.auth.OpenRouterKeyState
import io.github.grassified.wristprompt.auth.RouteSelection
import io.github.grassified.wristprompt.auth.RouteSelectionState
import io.github.grassified.wristprompt.ui.OpenRouterCatalogScreen
import io.github.grassified.wristprompt.ui.WristPromptTheme
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Drives the real browser and HTTP decoder with closed, synthetic responses. */
class DebugOpenRouterCatalogActivity : ComponentActivity() {
    private val route = MutableStateFlow(RouteSelectionState(RouteSelection(InferenceRoute.OPENROUTER_API_KEY, 0)))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("switch-route", false)) {
            val revision = checkNotNull(route.value.selection).revision
            route.value = RouteSelectionState(RouteSelection(InferenceRoute.OPENAI_API_KEY, revision + 1))
            route.value = RouteSelectionState(RouteSelection(InferenceRoute.OPENROUTER_API_KEY, revision + 2))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = when (intent.getStringExtra("fixture")) {
            "empty" -> """{"data":[],"total_count":0,"links":{"next":null}}"""
            "oversized" -> " ".repeat(256 * 1024 + 1)
            "malformed" -> """{"data":false}"""
            else -> CATALOG
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.host == "openrouter.ai" &&
                request.url.encodedPath == "/api/v1/models" && request.header("Authorization") == null)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Synthetic catalog").body(fixture.toResponseBody()).build()
        }
        val endpointFixture = intent.getStringExtra("endpoint-fixture")
        val attempts = AtomicInteger()
        val endpoints = DebugEndpointProtocol(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.method == "GET" && request.url.host == "openrouter.ai" &&
                request.url.encodedPath in listOf("/api/v1/models/fixture/alder:free/endpoints", "/api/v1/models/fixture/birch/endpoints") &&
                request.header("Authorization") == null)
            var code = 200
            val fixtureMode = if (request.url.pathSegments[4] == "birch") "birch" else endpointFixture
            val body = when (fixtureMode) {
                "birch" -> """{"data":{"id":"fixture/birch","endpoints":[]}}"""
                "empty" -> """{"data":{"id":"fixture/alder:free","endpoints":[]}}"""
                "error" -> {
                    if (attempts.getAndIncrement() == 0) code = 503
                    ENDPOINTS
                }
                "slow" -> {
                    repeat(40) {
                        if (chain.call().isCanceled()) throw IOException("Synthetic endpoint cancelled")
                        Thread.sleep(100)
                    }
                    ENDPOINTS
                }
                else -> ENDPOINTS
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Synthetic endpoint").body(body.toResponseBody()).build()
        })
        setContent {
            val model: OpenRouterCatalogViewModel = viewModel(factory = viewModelFactory {
                initializer {
                    OpenRouterCatalogViewModel(
                        route,
                        MutableStateFlow(OpenRouterKeyState(generation = 0)),
                        OpenRouterPublicCatalog(client),
                        endpoints,
                    )
                }
            })
            WristPromptTheme {
                AppScaffold(timeText = {}) { OpenRouterCatalogScreen(model, ::finish) }
            }
        }
    }

    private companion object {
        const val ENDPOINTS = """
            {"data":{"id":"fixture/alder:free","endpoints":[
              {"name":"Endpoint F2a Fixture US","provider_name":"Example Cloud","tag":"example",
               "model_id":"fixture/alder-v1","context_length":32000,"max_completion_tokens":2048,
               "supported_parameters":["temperature","max_tokens"],"quantization":"fp8",
               "pricing":{"prompt":"0.0000005","completion":"0.000001"}},
              {"name":"Endpoint F2a Fixture EU","provider_name":"Example Cloud","tag":"example",
               "model_id":"fixture/alder-v1","context_length":32000,"service_tier":"priority"}
            ]}}
        """

        const val CATALOG = """
            {"data":[
              {"id":"fixture/alder:free","canonical_slug":"fixture/alder-v1","name":"Alder Catalog Fixture",
               "architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},
               "context_length":32000,"supported_parameters":["temperature","max_tokens"],
               "top_provider":{"max_completion_tokens":2048}},
              {"id":"fixture/birch","canonical_slug":"fixture/birch-v1","name":"Birch Catalog Fixture"}
            ],"total_count":2,"links":{"next":null}}
        """
    }
}
