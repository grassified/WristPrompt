package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.auth.CredentialMode
import kotlinx.serialization.json.JsonObject

internal val ResponseEvent.endsStream: Boolean
    get() = this is ResponseEvent.Completed || this is ResponseEvent.Failed || this == ResponseEvent.Incomplete

/** Interprets one Responses stream under the session's owning Job. */
internal class OpenAiStreamAdapter(mode: CredentialMode, private val image: Boolean) {
    private val result = StreamResult(ResponsesProtocol.expected(mode))

    suspend fun accept(event: ResponseEvent, beforeAccept: suspend () -> Unit): StreamResult? {
        if (result.completed) return null
        beforeAccept()
        result.accept(event)
        return result.takeUnless { event == ResponseEvent.Ignored }
    }

    fun finish(): StreamResult {
        result.end()
        if (image && result.generated == null) GenerationOutput.invalid()
        return result
    }
}

/** A terminal event, not received text or [DONE], decides the stream result. */
class StreamResult(private val expected: String = ResponsesProtocol.EXPECTED_TEXT) {
    var output: String = ""; private set
    var sawTextDelta: Boolean = false; private set
    var completed: Boolean = false; private set
    var outputItems: List<JsonObject>? = null; private set
    var generated: GeneratedOutput? = null; private set
    private var terminal = false
    fun accept(event: ResponseEvent) {
        check(!terminal)
        when (event) {
            is ResponseEvent.Delta -> {
                if (output.length + event.text.length > ResponsesProtocol.MAX_TEXT_CHARS) throw ApiFailure(ApiProblem.PROTOCOL, protocolDetail = ProtocolDetail.OUTPUT_LIMIT)
                output += event.text
                sawTextDelta = sawTextDelta || event.text.isNotEmpty()
            }
            is ResponseEvent.Completed -> {
                terminal = true; completed = true; outputItems = event.outputItems; generated = event.generated
                event.finalText?.let { output = it }
            }
            is ResponseEvent.Failed -> { terminal = true; throw event.failure }
            ResponseEvent.Incomplete -> { terminal = true; throw ApiFailure(ApiProblem.INCOMPLETE) }
            ResponseEvent.Ignored -> Unit
        }
    }
    fun end() { if (!completed) throw ApiFailure(ApiProblem.INTERRUPTED) }
    val proofPassed: Boolean get() = completed && sawTextDelta && output == expected
}
