package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.auth.CredentialMode
import kotlinx.serialization.json.JsonObject

/** Request policy for a selected OpenAI model and billing route. Holds no credential or draft state. */
internal class OpenAiRequestAdapter(private val model: AiModel, private val mode: CredentialMode) {
    fun validateConfiguration(configuration: InferenceConfiguration) =
        InferenceControls.validate(configuration, model, mode)

    /** Returns whether the caller must resolve conversation-owned images before encoding. */
    fun preflightChat(input: List<JsonObject>, configuration: InferenceConfiguration): Boolean {
        if (ImageInput.hasImages(input)) {
            OpenAiImageCapabilities.validate(model.slug, mode, input)
            return true
        }
        encodeChat(input, configuration)
        return false
    }

    /** Run against the current catalog after any credential refresh, before calling the provider. */
    fun validateDispatch(input: List<JsonObject>?, configuration: InferenceConfiguration, imageGeneration: Boolean) {
        if (imageGeneration) encodeImageGeneration(checkNotNull(input))
        validateConfiguration(configuration)
        if (input != null) OpenAiImageCapabilities.validate(model.slug, mode, input)
    }

    fun encodeChat(input: List<JsonObject>, configuration: InferenceConfiguration): String =
        ResponsesProtocol.request(model.slug, input, configuration, model.thinking, mode)

    fun encodeImageGeneration(input: List<JsonObject>): String =
        OpenAiGeneration.request(model, mode, input)
}
