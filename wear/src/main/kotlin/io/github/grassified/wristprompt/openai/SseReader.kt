package io.github.grassified.wristprompt.openai

import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException

/** Bounded SSE framing: UTF-8, BOM, comments, multiline data, LF/CRLF/CR and split reads. */
class SseReader(input: InputStream, private val generation: Boolean = false) {
    private val bounded = object : java.io.FilterInputStream(input) {
        private var readBytes = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it > 0) count(it) }
        private fun count(amount: Int) {
            readBytes += amount
            if (generation && readBytes > GenerationOutput.MAX_STREAM) GenerationOutput.invalid()
        }
    }
    private val reader = InputStreamReader(bounded, Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    private var first = true
    private var afterCr = false
    private fun line(): String? {
        val text = StringBuilder()
        while (true) {
            val code = try { reader.read() }
            catch (_: CharacterCodingException) { throw ApiFailure(ApiProblem.PROTOCOL, protocolDetail = ProtocolDetail.INVALID_UTF8) }
            if (code == -1) return if (text.isEmpty()) null else text.toString()
            if (first) { first = false; if (code == 0xfeff) continue }
            if (afterCr) { afterCr = false; if (code == 10) continue }
            if (code == 13) { afterCr = true; return text.toString() }
            if (code == 10) return text.toString()
            text.append(code.toChar())
            if (text.length > if (generation) GenerationOutput.MAX_FRAME else 65_536) throw ApiFailure(ApiProblem.PROTOCOL, protocolDetail = ProtocolDetail.SSE_LINE_LIMIT)
        }
    }
    fun next(): ResponseEvent? {
        var event: String? = null
        val data = StringBuilder()
        var frameSize = 0
        while (true) {
            val line = line() ?: return null // EOF cannot dispatch an unterminated event.
            frameSize += line.length + 1
            if (frameSize > if (generation) GenerationOutput.MAX_FRAME.toLong() else ResponsesProtocol.MAX_BODY_BYTES) throw ApiFailure(ApiProblem.PROTOCOL, protocolDetail = ProtocolDetail.SSE_FRAME_LIMIT)
            if (line.isEmpty()) {
                if (data.isNotEmpty()) {
                    val body = data.dropLast(1).toString()
                    if (body == "[DONE]") return null
                    return ResponseEvents.decode(event, body, generation)
                }
                frameSize = 0; event = null
            } else if (!line.startsWith(':')) {
                val field = line.substringBefore(':')
                val value = line.substringAfter(':', "").removePrefix(" ")
                when (field) {
                    "event" -> event = value
                    "data" -> data.append(value).append('\n')
                }
            }
        }
    }
}
