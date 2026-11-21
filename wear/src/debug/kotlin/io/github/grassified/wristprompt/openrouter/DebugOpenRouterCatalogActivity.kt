package io.github.grassified.wristprompt.openrouter

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

/** Drives the real browser and HTTP decoder with closed, synthetic responses. */
class DebugOpenRouterCatalogActivity : ComponentActivity() {
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
        setContent {
            val model: OpenRouterCatalogViewModel = viewModel(factory = viewModelFactory {
                initializer {
                    OpenRouterCatalogViewModel(
                        MutableStateFlow(RouteSelectionState(RouteSelection(InferenceRoute.OPENROUTER_API_KEY, 0))),
                        MutableStateFlow(OpenRouterKeyState(generation = 0)),
                        OpenRouterPublicCatalog(client),
                    )
                }
            })
            WristPromptTheme {
                AppScaffold(timeText = {}) { OpenRouterCatalogScreen(model, ::finish) }
            }
        }
    }

    private companion object {
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
