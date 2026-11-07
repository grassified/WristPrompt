package io.github.grassified.wristprompt.openai

import io.github.grassified.wristprompt.storage.*
import java.util.UUID

/** Synthetic memory store for visible history interaction; no Android files or credentials. */
internal class DebugHistoryStore(readOnly: Boolean = false) : ConversationStore {
    private val owner = UUID(0, 1).toString()
    private val entries = linkedMapOf<String, ConversationEntry>()
    private val deleted = mutableSetOf<String>()
    init {
        repeat(if (readOnly) 1 else 25) { index ->
            val chat = ChatConversation().apply {
                draft("Synthetic saved question ${index + 1}"); begin()
                complete(StreamResult().apply { accept(ResponseEvent.Completed("# Saved answer ${index + 1}\n\nThis is synthetic saved conversation text.")); end() })
            }
            val id = UUID(0, index.toLong() + 10).toString()
            entries[id] = ConversationEntry(id, owner, "CHATGPT_PLAN", "debug-1",
                "Chat ${index + 1}: School summary and coding notes", index.toLong(), 1,
                if (readOnly) chat.snapshot().copy(replay = emptyList()) else chat.snapshot(),
                replayFormat = if (readOnly) "synthetic-future" else OpenAiHistory.REPLAY_FORMAT, replayAvailable = !readOnly)
        }
    }
    override suspend fun owner(identityHash: String) = owner
    override suspend fun save(entry: ConversationEntry): ConversationEntry {
        require(entry.owner == owner && entry.id !in deleted && entry.revision == (entries[entry.id]?.revision ?: 0))
        return entry.copy(revision = entry.revision + 1).also { entries[entry.id] = it }
    }
    override suspend fun load(key: ConversationKey) = entries[key.id]?.takeIf { it.key == key }
    override suspend fun list(scope: HistoryScope) = entries.values.filter { scope.canView(it.key) }.sortedByDescending { it.updated }
        .map { it.summary() }
    override suspend fun delete(key: ConversationKey, revision: Long) {
        val entry = checkNotNull(load(key)); require(entry.revision == revision)
        entries.remove(key.id); deleted.add(key.id)
    }
    override suspend fun rename(key: ConversationKey, revision: Long, title: String) {
        val entry = checkNotNull(load(key)); require(entry.revision == revision && title.isNotBlank() && title.length <= 120)
        entries[key.id] = entry.copy(title = title, revision = revision + 1)
    }
    override suspend fun isDeleted(key: ConversationKey) = false
    override suspend fun sentArchiveWasDeleted(id: String) = false
    override suspend fun generatedFilesForRecovery(id: String, owner: String): Set<String>? = null
    override suspend fun requireReplayTarget(entry: ConversationEntry) = throw ConversationStorageException()
}
