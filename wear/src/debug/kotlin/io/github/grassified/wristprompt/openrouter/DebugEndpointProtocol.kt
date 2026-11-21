package io.github.grassified.wristprompt.openrouter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/** Debug-only protocol client. The production API declares bearer authentication. */
internal class DebugEndpointProtocol(builder: OkHttpClient.Builder) : PublicEndpointSource {
    private val http = PublicOpenRouterGet(builder)

    override suspend fun load(candidate: PublicModelCandidate): EndpointDetails = try {
        val id = candidate.id
        if (!isPublicModelId(id)) fail(CatalogProblem.MALFORMED)
        val url = OPENROUTER_MODELS_URL.newBuilder()
            .addPathSegment(id.substringBefore('/'))
            .addPathSegment(id.substringAfter('/'))
            .addPathSegment("endpoints")
            .build()
        EndpointDetails.Loaded(http.read(url) { parseEndpoints(it, candidate) })
    } catch (failure: CatalogReadException) {
        EndpointDetails.Failed(failure.problem)
    }
}

private fun parseEndpoints(bytes: ByteArray, candidate: PublicModelCandidate): List<PublicEndpointMetadata> {
    val data = parseBoundedPublicObject(bytes).optionalObject("data") ?: fail(CatalogProblem.MALFORMED)
    if (data.requiredText("id", 160) != candidate.id) fail(CatalogProblem.MALFORMED)
    val endpoints = data["endpoints"] as? JsonArray ?: fail(CatalogProblem.MALFORMED)
    if (endpoints.size > 64) fail(CatalogProblem.TOO_LARGE)
    return endpoints.map { element ->
        val row = element as? JsonObject ?: fail(CatalogProblem.MALFORMED)
        val modelId = row.optionalText("model_id", 160)
        if (modelId != null && modelId != candidate.id && modelId != candidate.canonicalSlug) {
            fail(CatalogProblem.MALFORMED)
        }
        val pricing = row.optionalObject("pricing")
        val prices = buildMap {
            for (price in EndpointPrice.entries) {
                val value = pricing?.optionalText(price.field, 48) ?: continue
                if (!decimal.matches(value)) fail(CatalogProblem.MALFORMED)
                put(price, value)
            }
        }
        PublicEndpointMetadata(
            providerName = row.optionalText("provider_name", 120),
            reportedTag = row.optionalText("tag", 160),
            endpointName = row.optionalText("name", 200),
            modelId = modelId,
            supportedParameters = row.labels("supported_parameters", 64),
            contextTokens = row.positiveLimit("context_length"),
            maxPromptTokens = row.positiveLimit("max_prompt_tokens"),
            maxCompletionTokens = row.positiveLimit("max_completion_tokens"),
            quantization = row.optionalText("quantization", 64),
            serviceTier = row.optionalText("service_tier", 64),
            prices = prices,
        )
    }
}

private val decimal = Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?")
