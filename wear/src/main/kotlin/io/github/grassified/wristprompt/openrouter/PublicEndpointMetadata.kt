package io.github.grassified.wristprompt.openrouter

/** Public descriptions with no account eligibility or routing selector. */
internal data class PublicEndpointMetadata(
    val providerName: String?,
    val reportedTag: String?,
    val endpointName: String?,
    val modelId: String?,
    val supportedParameters: List<String>?,
    val contextTokens: Int?,
    val maxPromptTokens: Int?,
    val maxCompletionTokens: Int?,
    val quantization: String?,
    val serviceTier: String?,
    val prices: Map<EndpointPrice, String>,
)

internal enum class EndpointPrice(val field: String, val label: String, val unit: String) {
    INPUT("prompt", "Input", "token"),
    OUTPUT("completion", "Output", "token"),
    REQUEST("request", "Request", "request"),
    INPUT_IMAGE("image", "Input image", "image"),
    OUTPUT_IMAGE("image_output", "Output image", "image"),
    IMAGE_TOKEN("image_token", "Image token", "token"),
    AUDIO("audio", "Audio input", "token"),
    AUDIO_OUTPUT("audio_output", "Audio output", "token"),
    CACHE_READ("input_cache_read", "Cache read", "token"),
    CACHE_WRITE("input_cache_write", "Cache write", "token"),
    CACHE_WRITE_HOUR("input_cache_write_1h", "One-hour cache write", "token"),
    AUDIO_CACHE("input_audio_cache", "Audio cache read", "token"),
    REASONING("internal_reasoning", "Internal reasoning", "token"),
    WEB_SEARCH("web_search", "Web search", "search"),
}

internal sealed interface EndpointDetails {
    data object Unavailable : EndpointDetails
    data object Idle : EndpointDetails
    data object Loading : EndpointDetails
    data class Loaded(val rows: List<PublicEndpointMetadata>) : EndpointDetails
    data class Failed(val problem: CatalogProblem) : EndpointDetails
}

internal fun interface PublicEndpointSource {
    suspend fun load(candidate: PublicModelCandidate): EndpointDetails
}
