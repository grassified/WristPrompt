package io.github.grassified.wristprompt.openrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Descriptive public metadata. This type conveys no account or routing permission. */
internal data class PublicModelCandidate(
    val id: String,
    val canonicalSlug: String,
    val name: String,
    val inputModalities: List<String>?,
    val outputModalities: List<String>?,
    val supportedParameters: List<String>?,
    val contextTokens: Int?,
    val topProviderCompletionTokens: Int?,
)

internal data class PublicCatalogPage(
    val candidates: List<PublicModelCandidate>,
    val total: Int,
    val hasNext: Boolean,
)

internal fun parsePublicCatalogPage(bytes: ByteArray): PublicCatalogPage {
    val root = parseBoundedPublicObject(bytes)
    val rows = root["data"] as? JsonArray ?: fail(CatalogProblem.MALFORMED)
    if (rows.size > PAGE_SIZE) fail(CatalogProblem.TOO_LARGE)
    val total = root.number("total_count")?.takeIf { it >= 0 } ?: fail(CatalogProblem.MALFORMED)
    // Use only the presence of a next page. Never turn server strings into request URLs.
    val links = root["links"] as? JsonObject ?: fail(CatalogProblem.MALFORMED)
    val next = links["next"]
    val hasNext = when {
        next == null || next == JsonNull -> false
        next is JsonPrimitive && next.isString && next.content.isNotBlank() -> true
        else -> fail(CatalogProblem.MALFORMED)
    }
    return PublicCatalogPage(rows.map { model(it as? JsonObject ?: fail(CatalogProblem.MALFORMED)) }, total, hasNext)
}

internal fun parseBoundedPublicObject(bytes: ByteArray): JsonObject {
    val source = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        fail(CatalogProblem.MALFORMED)
    }
    checkJsonBounds(source)
    return try {
        Json.parseToJsonElement(source) as? JsonObject
    } catch (_: IllegalArgumentException) {
        fail(CatalogProblem.MALFORMED)
    } ?: fail(CatalogProblem.MALFORMED)
}

private val modelId = Regex("~?[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.:+-]*")
internal fun isPublicModelId(value: String) = value.length <= 160 && modelId.matches(value)

private val capability = Regex("[a-zA-Z0-9_.:-]+")

private fun model(row: JsonObject): PublicModelCandidate {
    val architecture = row.optionalObject("architecture")
    val topProvider = row.optionalObject("top_provider")
    val name = row.requiredText("name", 160)
    if (name.any { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } ||
        !Charsets.UTF_8.newEncoder().canEncode(name)) fail(CatalogProblem.MALFORMED)
    return PublicModelCandidate(
        id = row.requiredText("id", 160).also { if (!isPublicModelId(it)) fail(CatalogProblem.MALFORMED) },
        canonicalSlug = row.requiredText("canonical_slug", 160).also { if (!isPublicModelId(it)) fail(CatalogProblem.MALFORMED) },
        name = name,
        inputModalities = architecture?.labels("input_modalities", 16),
        outputModalities = architecture?.labels("output_modalities", 16),
        supportedParameters = row.labels("supported_parameters", 64),
        contextTokens = row.positiveLimit("context_length"),
        topProviderCompletionTokens = topProvider?.positiveLimit("max_completion_tokens"),
    )
}

internal fun JsonObject.requiredText(key: String, limit: Int) =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.also { if (it.length > limit) fail(CatalogProblem.TOO_LARGE) }
        ?.takeIf(String::isNotBlank) ?: fail(CatalogProblem.MALFORMED)

internal fun JsonObject.optionalText(key: String, limit: Int): String? {
    val value = get(key)
    if (value == null || value == JsonNull) return null
    val text = requiredText(key, limit)
    if (text.any { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' } ||
        !Charsets.UTF_8.newEncoder().canEncode(text)) fail(CatalogProblem.MALFORMED)
    return text
}

internal fun JsonObject.optionalObject(key: String): JsonObject? = when (val value = get(key)) {
    null, JsonNull -> null
    is JsonObject -> value
    else -> fail(CatalogProblem.MALFORMED)
}

internal fun JsonObject.labels(key: String, limit: Int): List<String>? {
    val value = get(key) ?: return null
    if (value == JsonNull) return null
    val array = value as? JsonArray ?: fail(CatalogProblem.MALFORMED)
    if (array.size > limit) fail(CatalogProblem.TOO_LARGE)
    return array.map {
        val label = (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail(CatalogProblem.MALFORMED)
        if (label.length > 64) fail(CatalogProblem.TOO_LARGE)
        if (!capability.matches(label)) fail(CatalogProblem.MALFORMED)
        label
    }.distinct()
}

private fun JsonObject.number(key: String): Int? = when (val value = get(key)) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeUnless { it.isString }?.intOrNull ?: fail(CatalogProblem.MALFORMED)
    else -> fail(CatalogProblem.MALFORMED)
}

internal fun JsonObject.positiveLimit(key: String) = number(key)?.takeIf { it > 0 }

/** Cap nesting and raw string size before handing untrusted input to the JSON tree parser. */
private fun checkJsonBounds(source: String) {
    var depth = 0
    var start = -1
    var escape = false
    for ((index, char) in source.withIndex()) {
        if (start >= 0) {
            if (index - start > 8192) fail(CatalogProblem.TOO_LARGE)
            when {
                escape -> escape = false
                char == '\\' -> escape = true
                char == '"' -> start = -1
            }
        } else {
            when (char) {
                '{', '[' -> if (++depth > 24) fail(CatalogProblem.TOO_LARGE)
                '}', ']' -> if (--depth < 0) fail(CatalogProblem.MALFORMED)
                '"' -> start = index
            }
        }
    }
    if (depth != 0 || start >= 0) fail(CatalogProblem.MALFORMED)
}
