package io.github.grassified.wristprompt.storage

import io.github.grassified.wristprompt.auth.CredentialMode
import io.github.grassified.wristprompt.bridge.SafeDiagnostics
import io.github.grassified.wristprompt.openai.ConversationSnapshot
import kotlinx.serialization.json.*
import java.util.UUID

class ConversationStorageException : IllegalStateException("Conversation storage unavailable")

/** Stable SQLite identity. Unknown non-null metadata can describe display-only history. */
data class ConversationKey(
    val id: String, val owner: String, val route: String, val model: String,
    val provider: String?, val replayFormat: String?,
) {
    val supportsReplay get() = OpenAiHistory.supports(this)
    fun validate() {
        require(UUID.fromString(id).toString() == id && UUID.fromString(owner).toString() == owner)
        require(route.length in 1..256 && model.length in 1..256)
        require(provider != null && provider.length in 1..256 && replayFormat != null && replayFormat.length in 1..256)
    }
    override fun toString() = SafeDiagnostics.REDACTED
}

data class HistoryScope(val owner: String, val mode: CredentialMode) {
    fun canView(key: ConversationKey) = key.owner == owner && key.provider != null && key.replayFormat != null &&
        (key.route == mode.name || key.route !in OpenAiHistory.routes)
    fun canContinue(key: ConversationKey, model: String) =
        key.owner == owner && key.route == mode.name && key.model == model && key.supportsReplay
    override fun toString() = SafeDiagnostics.REDACTED
}

data class ConversationEntry(
    val id: String = UUID.randomUUID().toString(), val owner: String, val route: String, val model: String,
    val title: String, val updated: Long, val revision: Long = 0, val snapshot: ConversationSnapshot,
    val provider: String? = OpenAiHistory.PROVIDER, val replayFormat: String? = OpenAiHistory.REPLAY_FORMAT,
    val replayAvailable: Boolean = true,
) {
    val key get() = ConversationKey(id, owner, route, model, provider, replayFormat)
    fun summary() = ConversationSummary(key, title, updated, revision)
    override fun toString() = SafeDiagnostics.REDACTED
}

data class ConversationSummary(val key: ConversationKey, val title: String, val updated: Long, val revision: Long) {
    val id get() = key.id
    val model get() = key.model
    val route get() = key.route
    override fun toString() = SafeDiagnostics.REDACTED
}

interface ConversationStore {
    suspend fun owner(identityHash: String): String
    suspend fun save(entry: ConversationEntry): ConversationEntry
    suspend fun load(key: ConversationKey): ConversationEntry?
    suspend fun list(scope: HistoryScope): List<ConversationSummary>
    suspend fun delete(key: ConversationKey, revision: Long)
    suspend fun rename(key: ConversationKey, revision: Long, title: String)
    suspend fun isDeleted(key: ConversationKey): Boolean
    suspend fun sentArchiveWasDeleted(id: String): Boolean
    /** Null means uncertain ownership. An empty set is affirmative evidence of no owned files. */
    suspend fun generatedFilesForRecovery(id: String, owner: String): Set<String>?
    /** Check a live revision or a fresh UUID before creating or materializing a file reservation. */
    suspend fun requireReplayTarget(entry: ConversationEntry)
}

/** The routes and codec understood by the original OpenAI history writer. */
internal object OpenAiHistory {
    const val PROVIDER = "openai"
    const val REPLAY_FORMAT = "openai_responses_v1"
    val routes = setOf(CredentialMode.CHATGPT_PLAN.name, CredentialMode.OPENAI_API_KEY.name)
    fun supports(key: ConversationKey) =
        key.provider == PROVIDER && key.replayFormat == REPLAY_FORMAT && key.route in routes
}

/** Strict bounded codec. Unknown versions/types are errors, never silent data loss. */
object ConversationCodec {
    const val MAX_ENTRY_BYTES = 1_048_576
    val json = Json { encodeDefaults = true }
    fun validate(entry: ConversationEntry) {
        entry.key.validate()
        require(entry.key.supportsReplay && entry.replayAvailable)
        validateSummary(entry.summary())
        entry.snapshot.validate()
    }
    internal fun validateSummary(summary: ConversationSummary) {
        require(summary.title.isNotBlank() && summary.title.length <= 120)
        require(summary.updated >= 0 && summary.revision >= 0)
    }
    fun encode(entry: ConversationEntry): Pair<String, String> {
        validate(entry)
        val body = json.encodeToString(ConversationSnapshot.serializer(), entry.snapshot.copy(replay = emptyList()))
        val replay = JsonArray(entry.snapshot.replay).toString()
        require(body.encodeToByteArray().size + replay.encodeToByteArray().size <= MAX_ENTRY_BYTES)
        return body to replay
    }
    fun decode(body: String, replay: String): ConversationSnapshot {
        require(body.encodeToByteArray().size + replay.encodeToByteArray().size <= MAX_ENTRY_BYTES)
        return decodeReplay(decodeDisplay(body), replay)
    }
    internal fun decodeDisplay(body: String): ConversationSnapshot {
        require(body.encodeToByteArray().size <= MAX_ENTRY_BYTES)
        requireBoundedDepth(body)
        val snapshot = json.decodeFromString(ConversationSnapshot.serializer(), body)
        require(snapshot.replay.isEmpty())
        return snapshot.also { it.validateDisplay() }
    }
    internal fun decodeReplay(display: ConversationSnapshot, replay: String): ConversationSnapshot {
        require(replay.encodeToByteArray().size <= ConversationSnapshot.MAX_REPLAY_BYTES)
        requireBoundedDepth(replay)
        return display.copy(replay = Json.parseToJsonElement(replay).jsonArray.map { it.jsonObject }).also { it.validateReplay() }
    }
    private fun requireBoundedDepth(source: String) {
        var depth = 0; var quoted = false; var escaped = false
        source.forEach { c ->
            if (escaped) { escaped = false }
            else if (quoted && c == '\\') escaped = true
            else if (c == '"') quoted = !quoted
            else if (!quoted) when (c) {
                '{', '[' -> { depth++; require(depth <= 32) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }
}
